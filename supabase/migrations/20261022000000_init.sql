-- FairPool server schema.
-- No user accounts: drivers, passengers and vehicles are identified by random secrets kept on
-- their own device; the server stores only SHA-256 hashes. Tables are closed to the public API
-- (RLS on, no policies) and every read or write goes through the security-definer functions below.

create extension if not exists pgcrypto;

-- Key for encrypting driver bank accounts. The private schema is not exposed through the API.
create schema if not exists private;
create table private.settings (name text primary key, value text not null);
insert into private.settings values ('bank_key', encode(gen_random_bytes(32), 'hex'));
revoke all on schema private from public;

-- ---------------------------------------------------------------- helpers

create or replace function public._hash(p text) returns text
language sql immutable set search_path = public, extensions as $$
  select encode(digest(coalesce(p, ''), 'sha256'), 'hex')
$$;

create or replace function public._secret() returns text
language sql volatile set search_path = public, extensions as $$
  select encode(gen_random_bytes(24), 'hex')
$$;

-- Short code without look-alike characters (0/O, 1/I/L), used in QR links.
create or replace function public._code(n int) returns text
language plpgsql volatile set search_path = public, extensions as $$
declare
  alphabet constant text := '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
  b bytea := gen_random_bytes(n);
  result text := '';
begin
  for i in 0 .. n - 1 loop
    result := result || substr(alphabet, (get_byte(b, i) % length(alphabet)) + 1, 1);
  end loop;
  return result;
end $$;

create or replace function public._enc(p text) returns bytea
language sql volatile security definer set search_path = public, extensions as $$
  select case when p is null then null
              else pgp_sym_encrypt(p, (select value from private.settings where name = 'bank_key')) end
$$;

create or replace function public._dec(p bytea) returns text
language sql stable security definer set search_path = public, extensions as $$
  select case when p is null then null
              else pgp_sym_decrypt(p, (select value from private.settings where name = 'bank_key')) end
$$;

-- ---------------------------------------------------------------- tables

create table public.drivers (
  id              uuid primary key default gen_random_uuid(),
  secret_hash     text not null unique,
  display_name    text not null default '운전자',
  bank_name       text,
  bank_account    bytea,                 -- encrypted with _enc
  account_holder  bytea,                 -- encrypted with _enc
  bank_consent_at timestamptz,          -- shown only to passengers whose settlement is in progress
  created_at      timestamptz not null default now()
);

create table public.vehicles (
  id              uuid primary key default gen_random_uuid(),
  secret_hash     text not null unique,  -- kept in the car's Android Keystore
  driver_id       uuid references public.drivers(id) on delete set null,
  link_code       text unique,           -- shown as a QR on the car screen until a driver claims it
  link_expires_at timestamptz,
  created_at      timestamptz not null default now(),
  revoked_at      timestamptz
);

-- 카풀 폴더: trips are stacked into the open batch, then settled together.
-- A batch goes open (요청 전) -> requested (정산 중) -> done (정산 완료); the folder keeps its
-- name and starts a new open batch.
create table public.folders (
  id          uuid primary key default gen_random_uuid(),
  code        text not null unique,
  driver_id   uuid references public.drivers(id) on delete cascade,
  vehicle_id  uuid references public.vehicles(id) on delete set null,
  name        text not null,
  created_at  timestamptz not null default now(),
  archived_at timestamptz
);

create table public.folder_batches (
  id           uuid primary key default gen_random_uuid(),
  folder_id    uuid not null references public.folders(id) on delete cascade,
  status       text not null default 'open' check (status in ('open', 'requested', 'done')),
  code         text unique,                -- the one settlement page (/s/<code>), made at 합쳐서 정산하기
  verify_fails int not null default 0,
  created_at   timestamptz not null default now(),
  requested_at timestamptz,
  done_at      timestamptz
);

-- What each passenger owes for a requested batch, fixed when the driver asks for it.
create table public.batch_payments (
  id              uuid primary key default gen_random_uuid(),
  batch_id        uuid not null references public.folder_batches(id) on delete cascade,
  device_key_hash text not null,
  name            text not null,
  amount          numeric not null,
  trips           int not null,
  device_hashes   text[] not null default '{}',  -- other phones verified on the settlement page
  paid_at         timestamptz
);

-- A saved regular carpool: "make the last trip again" and a stable link passengers keep.
create table public.carpools (
  id             uuid primary key default gen_random_uuid(),
  code           text not null unique,
  driver_id      uuid not null references public.drivers(id) on delete cascade,
  title          text not null,
  origin         jsonb,
  destination    jsonb,                  -- places are {label, area, lat, lng}
  include_driver boolean not null default true,
  seats          int not null default 4,
  rule           text not null default 'shapley',
  folder_id      uuid references public.folders(id) on delete set null,  -- folder its trips go to
  created_at     timestamptz not null default now(),
  archived_at    timestamptz
);

-- Stops of a saved carpool are kept for its next trip; a member can remove them any time
-- ("내 장소 지우기", save_place = false). One-off trips keep nothing.
create table public.carpool_members (
  id              uuid primary key default gen_random_uuid(),
  carpool_id      uuid not null references public.carpools(id) on delete cascade,
  name            text not null,
  pickup          jsonb,
  dropoff         jsonb,
  device_key_hash text,
  phone4_hash     text,
  created_at      timestamptz not null default now()
);

create table public.trips (
  id             uuid primary key default gen_random_uuid(),
  code           text not null unique,
  admin_hash     text not null,          -- secret of whoever created the trip (driver web or car)
  driver_id      uuid references public.drivers(id) on delete set null,
  vehicle_id     uuid references public.vehicles(id) on delete set null,
  carpool_id     uuid references public.carpools(id) on delete set null,
  title          text not null,
  origin         jsonb,                  -- where the car starts; shown to passengers
  destination    jsonb,                  -- driver's own destination; never shown to passengers
  include_driver boolean not null default true,
  seats          int not null default 4 check (seats between 1 and 8),
  rule           text not null default 'shapley' check (rule in ('shapley', 'distance', 'equal')),
  status         text not null default 'preparing'
                 check (status in ('preparing', 'in_progress', 'settled', 'cancelled')),
  -- live course posted by the car: {stops:[{kind, passenger_id, label, area, time, eta_min,
  -- distance_km, state}], car:{lat,lng}}; filtered per passenger in get_trip
  progress       jsonb,
  -- after settlement: 'direct' (바로 정산) or 'folder' (stacked into batch_id)
  settle_mode    text check (settle_mode in ('direct', 'folder')),
  batch_id       uuid references public.folder_batches(id) on delete set null,
  verify_fails   int not null default 0,
  created_at     timestamptz not null default now(),
  started_at     timestamptz,
  ended_at       timestamptz
);

create table public.trip_passengers (
  id              uuid primary key default gen_random_uuid(),
  trip_id         uuid not null references public.trips(id) on delete cascade,
  name            text not null,
  pickup          jsonb,                 -- null = gets on at the trip origin; cleared 24 h after the trip ends
  dropoff         jsonb,
  device_key_hash text not null,
  phone4_hash     text,                  -- last 4 digits of the phone, hashed, to re-open on another device
  save_place      boolean not null default true,  -- false after "내 장소 지우기"
  estimate        numeric,               -- expected share posted by the car while driving
  seat            text,
  joined_at       timestamptz not null default now(),
  boarded_at      timestamptz,
  alighted_at     timestamptz,
  paid_at         timestamptz,           -- 바로 정산: the driver confirmed this passenger paid
  place_removed_at timestamptz,          -- "내 장소 지우기"; the car picks it up at the next ignition
  unique (trip_id, device_key_hash)
);

-- Settlement computed in the car and published to passengers. Payload:
-- {rule, include_driver, total, fuel:{distance_km, efficiency_km_l, amount}, toll,
--  shares:[{passenger_id, name, role:'driver'|'passenger', amount, km, from, to}]}
create table public.settlements (
  trip_id    uuid primary key references public.trips(id) on delete cascade,
  payload    jsonb not null,
  settled_at timestamptz not null default now()
);

-- Trip records are never deleted, only corrected: a settlement posted again keeps the one it replaces.
create table public.settlement_revisions (
  id          uuid primary key default gen_random_uuid(),
  trip_id     uuid not null references public.trips(id) on delete cascade,
  payload     jsonb not null,
  reason      text,
  replaced_at timestamptz not null default now()
);

-- "정산에 문제가 있어요" from a passenger page.
create table public.trip_issues (
  id           uuid primary key default gen_random_uuid(),
  trip_id      uuid not null references public.trips(id) on delete cascade,
  passenger_id uuid references public.trip_passengers(id) on delete set null,
  message      text not null,
  created_at   timestamptz not null default now()
);

create index on public.trips (driver_id, created_at desc);
create index on public.trips (carpool_id, created_at desc);
create index on public.trip_passengers (trip_id);
create index on public.trip_issues (trip_id);
create index on public.trips (batch_id);
create index on public.settlement_revisions (trip_id);
create index on public.folder_batches (folder_id, created_at desc);

alter table public.drivers         enable row level security;
alter table public.vehicles        enable row level security;
alter table public.carpools        enable row level security;
alter table public.carpool_members enable row level security;
alter table public.trips           enable row level security;
alter table public.trip_passengers enable row level security;
alter table public.settlements     enable row level security;
alter table public.trip_issues     enable row level security;
alter table public.folders         enable row level security;
alter table public.folder_batches  enable row level security;
alter table public.batch_payments  enable row level security;
alter table public.settlement_revisions enable row level security;

-- ---------------------------------------------------------------- internal lookups

create or replace function public._driver(p_secret text) returns public.drivers
language sql stable set search_path = public, extensions as $$
  select * from public.drivers where p_secret is not null and secret_hash = public._hash(p_secret)
$$;

create or replace function public._vehicle(p_secret text) returns public.vehicles
language sql stable set search_path = public, extensions as $$
  select * from public.vehicles
  where p_secret is not null and secret_hash = public._hash(p_secret) and revoked_at is null
$$;

-- The trip creator's secret, or the secret of a car linked to the trip's driver, may manage a trip.
create or replace function public._trip_admin(p_code text, p_secret text) returns public.trips
language plpgsql stable set search_path = public, extensions as $$
declare
  t public.trips;
  v public.vehicles;
begin
  select * into t from public.trips where code = upper(p_code);
  if t.id is null then
    raise exception 'trip_not_found';
  end if;
  if t.admin_hash = public._hash(p_secret) then
    return t;
  end if;
  v := public._vehicle(p_secret);
  if v.id is not null and (v.id = t.vehicle_id or (t.driver_id is not null and v.driver_id = t.driver_id)) then
    return t;
  end if;
  raise exception 'not_allowed';
end $$;

create or replace function public._new_trip_code() returns text
language plpgsql volatile set search_path = public, extensions as $$
declare c text;
begin
  loop
    c := public._code(6);
    exit when not exists (select 1 from public.trips where code = c);
  end loop;
  return c;
end $$;

create or replace function public._passengers_admin(p_trip uuid) returns jsonb
language sql stable set search_path = public, extensions as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'id', id, 'name', name, 'pickup', pickup, 'dropoff', dropoff, 'seat', seat,
           'save_place', save_place, 'estimate', estimate, 'paid_at', paid_at,
           'place_removed_at', place_removed_at,
           'joined_at', joined_at, 'boarded_at', boarded_at, 'alighted_at', alighted_at)
         order by joined_at), '[]'::jsonb)
  from public.trip_passengers where trip_id = p_trip
$$;

-- What one passenger may see of the course: the origin, their own stops, and anonymous
-- "동승자 1명" stops for everyone else. The driver's destination is dropped. The car position is
-- shared only until this passenger has boarded.
create or replace function public._progress_for(p jsonb, me public.trip_passengers) returns jsonb
language sql stable set search_path = public, extensions as $$
  select case when p is null then null else jsonb_build_object(
    'car', case when me.id is not null and me.boarded_at is null then p -> 'car' end,
    'stops', (select coalesce(jsonb_agg(
                case
                  when s ->> 'kind' = 'start' then
                    jsonb_build_object('kind', 'start', 'label', s -> 'label', 'time', s -> 'time', 'state', s -> 'state')
                  when me.id is not null and s ->> 'passenger_id' = me.id::text then
                    jsonb_build_object('kind', 'my_' || (s ->> 'kind'),
                                       'label', case when s ->> 'kind' = 'dropoff' then coalesce(s -> 'area', s -> 'label')
                                                     else s -> 'label' end,
                                       'time', s -> 'time', 'eta_min', s -> 'eta_min',
                                       'distance_km', s -> 'distance_km', 'state', s -> 'state')
                  else
                    jsonb_build_object('kind', 'other_' || (s ->> 'kind'),
                                       'time', s -> 'time', 'eta_min', s -> 'eta_min', 'state', s -> 'state')
                end order by ord), '[]'::jsonb)
              from jsonb_array_elements(p -> 'stops') with ordinality as x(s, ord)
              where s ->> 'kind' in ('start', 'pickup', 'dropoff')))
  end
$$;

-- Settlement as one passenger sees it: everyone's amount, "is_me" on their own row, no ids.
create or replace function public._settlement_for(p jsonb, me public.trip_passengers) returns jsonb
language sql stable set search_path = public, extensions as $$
  select case when p is null then null else
    (p - 'shares') || jsonb_build_object('shares', (
      select coalesce(jsonb_agg((s - 'passenger_id') || jsonb_build_object(
               'is_me', coalesce(me.id is not null and s ->> 'passenger_id' = me.id::text, false))
             order by ord), '[]'::jsonb)
      from jsonb_array_elements(p -> 'shares') with ordinality as x(s, ord)))
  end
$$;

-- Members of a favourite and the places kept for its next trip (labels only).
create or replace function public._carpool_members(p_carpool uuid) returns jsonb
language sql stable set search_path = public, extensions as $$
  select coalesce(jsonb_agg(jsonb_build_object('id', id, 'name', name,
           'pickup', pickup ->> 'label', 'dropoff', dropoff ->> 'label') order by created_at), '[]'::jsonb)
  from public.carpool_members where carpool_id = p_carpool
$$;

-- ---------------------------------------------------------------- driver

create or replace function public.create_driver(p_display_name text default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  s text := public._secret();
  d public.drivers;
begin
  insert into public.drivers (secret_hash, display_name)
  values (public._hash(s), coalesce(nullif(trim(p_display_name), ''), '운전자'))
  returning * into d;
  return jsonb_build_object('driver_id', d.id, 'driver_secret', s);
end $$;

create or replace function public.get_driver(p_driver_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare d public.drivers := public._driver(p_driver_secret);
begin
  if d.id is null then raise exception 'not_allowed'; end if;
  return jsonb_build_object(
    'display_name', d.display_name,
    'bank', case when d.bank_consent_at is null then null else jsonb_build_object(
      'bank_name', d.bank_name, 'account', public._dec(d.bank_account), 'holder', public._dec(d.account_holder)) end,
    'carpools', (select coalesce(jsonb_agg(jsonb_build_object('code', c.code, 'title', c.title,
                   'members', public._carpool_members(c.id))
                   order by c.created_at desc), '[]'::jsonb)
                 from public.carpools c where c.driver_id = d.id and c.archived_at is null),
    'trips', (select coalesce(jsonb_agg(jsonb_build_object(
                 'code', t.code, 'title', t.title, 'status', t.status, 'created_at', t.created_at)
                 order by t.created_at desc), '[]'::jsonb)
              from (select * from public.trips where driver_id = d.id
                    order by created_at desc limit 20) t),
    'vehicles', (select count(*) from public.vehicles v where v.driver_id = d.id and v.revoked_at is null));
end $$;

-- Passing p_bank_consent = false removes the stored account.
create or replace function public.update_driver(
  p_driver_secret text,
  p_display_name text default null,
  p_bank_name text default null,
  p_bank_account text default null,
  p_account_holder text default null,
  p_bank_consent boolean default null
) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare d public.drivers := public._driver(p_driver_secret);
begin
  if d.id is null then raise exception 'not_allowed'; end if;
  update public.drivers set
    display_name    = coalesce(nullif(trim(p_display_name), ''), display_name),
    bank_name       = case when p_bank_consent is false then null else coalesce(p_bank_name, bank_name) end,
    bank_account    = case when p_bank_consent is false then null
                           when p_bank_account is not null then public._enc(p_bank_account) else bank_account end,
    account_holder  = case when p_bank_consent is false then null
                           when p_account_holder is not null then public._enc(p_account_holder) else account_holder end,
    bank_consent_at = case when p_bank_consent is true then coalesce(bank_consent_at, now())
                           when p_bank_consent is false then null else bank_consent_at end
  where id = d.id;
  return public.get_driver(p_driver_secret);
end $$;

-- ---------------------------------------------------------------- trips

-- Called from the driver web page (driver secret) or the car (vehicle secret); both may be null
-- for an instant trip started from the car screen before any driver is linked.
create or replace function public.create_trip(
  p_title text,
  p_origin jsonb default null,
  p_destination jsonb default null,
  p_include_driver boolean default true,
  p_seats int default 4,
  p_rule text default 'shapley',
  p_driver_secret text default null,
  p_vehicle_secret text default null
) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  d public.drivers := public._driver(p_driver_secret);
  v public.vehicles := public._vehicle(p_vehicle_secret);
  s text := public._secret();
  t public.trips;
begin
  if p_driver_secret is not null and d.id is null then raise exception 'not_allowed'; end if;
  if p_vehicle_secret is not null and v.id is null then raise exception 'not_allowed'; end if;
  insert into public.trips (code, admin_hash, driver_id, vehicle_id, title, origin, destination,
                            include_driver, seats, rule)
  values (public._new_trip_code(), public._hash(s), coalesce(d.id, v.driver_id), v.id,
          coalesce(nullif(trim(p_title), ''), '함께 가는 길'), p_origin, p_destination,
          coalesce(p_include_driver, true), coalesce(p_seats, 4), coalesce(p_rule, 'shapley'))
  returning * into t;
  return jsonb_build_object('trip_id', t.id, 'code', t.code, 'admin_secret', s);
end $$;

-- Public view of a trip for anyone holding its code (the QR). Other passengers' stops and the
-- driver's destination are never returned; the caller sees only their own, matched by device key.
create or replace function public.get_trip(p_code text, p_device_key text default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  t public.trips;
  d public.drivers;
  me public.trip_passengers;
  s public.settlements;
  taken int;
begin
  select * into t from public.trips where code = upper(p_code);
  if t.id is null then raise exception 'trip_not_found'; end if;
  select * into d from public.drivers where id = t.driver_id;
  select * into me from public.trip_passengers
   where trip_id = t.id and p_device_key is not null and device_key_hash = public._hash(p_device_key);
  select * into s from public.settlements where trip_id = t.id;
  select count(*) into taken from public.trip_passengers where trip_id = t.id;
  return jsonb_build_object(
    'code', t.code,
    'title', t.title,
    'origin', t.origin ->> 'label',
    'status', t.status,
    'include_driver', t.include_driver,
    'rule', t.rule,
    'seats', t.seats,
    'seats_left', greatest(t.seats - taken, 0),
    'driver_name', coalesce(d.display_name, '운전자'),
    'carpool_code', (select code from public.carpools where id = t.carpool_id),
    'passengers', (select coalesce(jsonb_agg(jsonb_build_object(
                     'name', p.name, 'is_me', p.id = me.id,
                     'boarded', p.boarded_at is not null, 'alighted', p.alighted_at is not null)
                     order by p.joined_at), '[]'::jsonb)
                   from public.trip_passengers p where p.trip_id = t.id),
    'me', case when me.id is null then null else jsonb_build_object(
            'name', me.name, 'pickup', me.pickup, 'dropoff', me.dropoff, 'save_place', me.save_place,
            'estimate', me.estimate, 'boarded', me.boarded_at is not null,
            'alighted', me.alighted_at is not null,
            'place_removed', me.place_removed_at is not null) end,
    'progress', public._progress_for(t.progress, me),
    -- folder trips have no settlement page of their own (each rider's part goes to the batch page)
    'settlement', case when t.settle_mode = 'folder' then null else public._settlement_for(s.payload, me) end,
    'settle_mode', t.settle_mode,
    'paid', me.paid_at is not null,
    'folder', public._folder_for(t, me),
    -- times the settlement was corrected (records are corrected, never deleted)
    'corrections', (select count(*) from public.settlement_revisions where trip_id = t.id),
    -- 바로 정산: the account is shown while this passenger's settlement is in progress (정산 중), until
    -- the driver marks them paid. Folder trips show it only on the batch's settlement page.
    'bank', case when me.id is not null and d.bank_consent_at is not null
                   and t.status = 'settled' and t.settle_mode = 'direct' and me.paid_at is null
              then jsonb_build_object('bank_name', d.bank_name, 'account', public._dec(d.bank_account),
                                      'holder', public._dec(d.account_holder)) end);
end $$;

create or replace function public.join_trip(
  p_code text,
  p_device_key text,
  p_name text,
  p_pickup jsonb default null,
  p_dropoff jsonb default null,
  p_phone4 text default null,
  p_save_place boolean default null     -- null keeps the current choice (new members: kept)
) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  t public.trips;
  known boolean;
begin
  if coalesce(length(p_device_key), 0) < 16 then raise exception 'bad_device_key'; end if;
  if nullif(trim(p_name), '') is null then raise exception 'name_required'; end if;
  -- the car turns stops into NaviHelper waypoints, which need coordinates
  if (p_pickup is not null and not (p_pickup ? 'lat' and p_pickup ? 'lng'))
     or (p_dropoff is not null and not (p_dropoff ? 'lat' and p_dropoff ? 'lng')) then
    raise exception 'place_needs_location';
  end if;
  select * into t from public.trips where code = upper(p_code);
  if t.id is null then raise exception 'trip_not_found'; end if;
  if t.status not in ('preparing', 'in_progress') then raise exception 'trip_closed'; end if;
  known := exists (select 1 from public.trip_passengers
                   where trip_id = t.id and device_key_hash = public._hash(p_device_key));
  if not known and (select count(*) from public.trip_passengers where trip_id = t.id) >= t.seats then
    raise exception 'trip_full';
  end if;
  insert into public.trip_passengers (trip_id, name, pickup, dropoff, device_key_hash, phone4_hash, save_place)
  values (t.id, trim(p_name), p_pickup, p_dropoff, public._hash(p_device_key),
          case when p_phone4 ~ '^[0-9]{4}$' then public._hash(t.id::text || p_phone4) end,
          coalesce(p_save_place, true))
  on conflict (trip_id, device_key_hash) do update
    set name = excluded.name,
        pickup = excluded.pickup,
        dropoff = coalesce(excluded.dropoff, public.trip_passengers.dropoff),
        phone4_hash = coalesce(excluded.phone4_hash, public.trip_passengers.phone4_hash),
        save_place = coalesce(p_save_place, public.trip_passengers.save_place);
  return public.get_trip(p_code, p_device_key);
end $$;

-- Re-open the page on another phone: name + last 4 digits must match the passenger who joined.
create or replace function public.verify_device(p_code text, p_device_key text, p_name text, p_phone4 text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  t public.trips;
  pid uuid;
begin
  if coalesce(length(p_device_key), 0) < 16 then raise exception 'bad_device_key'; end if;
  select * into t from public.trips where code = upper(p_code);
  if t.id is null then raise exception 'trip_not_found'; end if;
  if t.verify_fails >= 20 then raise exception 'too_many_attempts'; end if;
  select id into pid from public.trip_passengers
   where trip_id = t.id and name = trim(p_name) and phone4_hash = public._hash(t.id::text || coalesce(p_phone4, ''));
  if pid is null then
    -- returned instead of raised, so the failure count is not rolled back
    update public.trips set verify_fails = verify_fails + 1 where id = t.id;
    return jsonb_build_object('error', 'verify_failed');
  end if;
  update public.trip_passengers set device_key_hash = public._hash(p_device_key) where id = pid;
  return public.get_trip(p_code, p_device_key);
end $$;

create or replace function public.leave_trip(p_code text, p_device_key text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare t public.trips;
begin
  select * into t from public.trips where code = upper(p_code);
  if t.id is null then raise exception 'trip_not_found'; end if;
  if t.status <> 'preparing' then raise exception 'trip_started'; end if;
  delete from public.trip_passengers where trip_id = t.id and device_key_hash = public._hash(p_device_key);
  return jsonb_build_object('ok', true);
end $$;

-- "내 장소 지우기": the passenger removes their own place right away, no driver approval. The place kept
-- for the favourite's next trip goes and saving stops; on a trip not finished yet the stops go too (the
-- car then asks NaviHelper for the whole route again with requestRouteWithWaypoints). Past trip records
-- and settled amounts stay.
create or replace function public.forget_my_place(p_code text, p_device_key text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  t public.trips;
  me public.trip_passengers;
  live boolean;
begin
  select * into t from public.trips where code = upper(p_code);
  if t.id is null then raise exception 'trip_not_found'; end if;
  select * into me from public.trip_passengers
   where trip_id = t.id and device_key_hash = public._hash(p_device_key);
  if me.id is null then raise exception 'not_allowed'; end if;
  live := t.status in ('preparing', 'in_progress');
  update public.trip_passengers set
    save_place = false, place_removed_at = now(),
    pickup = case when live then null else pickup end,
    dropoff = case when live then null else dropoff end
  where id = me.id;
  update public.carpool_members set pickup = null, dropoff = null
  where carpool_id = t.carpool_id and device_key_hash = me.device_key_hash;
  return public.get_trip(p_code, p_device_key);
end $$;

create or replace function public.report_issue(p_code text, p_device_key text, p_message text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  t public.trips;
  me public.trip_passengers;
begin
  select * into t from public.trips where code = upper(p_code);
  if t.id is null then raise exception 'trip_not_found'; end if;
  select * into me from public.trip_passengers
   where trip_id = t.id and device_key_hash = public._hash(p_device_key);
  if me.id is null then raise exception 'not_allowed'; end if;
  insert into public.trip_issues (trip_id, passenger_id, message)
  values (t.id, me.id, left(coalesce(nullif(trim(p_message), ''), '정산에 문제가 있어요'), 500));
  return jsonb_build_object('ok', true);
end $$;

-- Full view for the driver page and the car: every passenger with their stops.
create or replace function public.get_trip_admin(p_code text, p_admin_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare t public.trips := public._trip_admin(p_code, p_admin_secret);
begin
  return jsonb_build_object(
    'trip_id', t.id, 'code', t.code, 'title', t.title, 'origin', t.origin, 'destination', t.destination,
    'include_driver', t.include_driver, 'seats', t.seats, 'rule', t.rule, 'status', t.status,
    'created_at', t.created_at, 'started_at', t.started_at, 'ended_at', t.ended_at,
    'carpool_code', (select code from public.carpools where id = t.carpool_id),
    'passengers', public._passengers_admin(t.id),
    'progress', t.progress,
    'settlement', (select payload from public.settlements where trip_id = t.id),
    'settle_mode', t.settle_mode,
    'folder', (select jsonb_build_object('code', f.code, 'name', f.name, 'status', b.status)
               from public.folder_batches b join public.folders f on f.id = b.folder_id where b.id = t.batch_id),
    -- folders this trip can be stacked into, the carpool's own folder first, with what is piled up
    'folders', (select coalesce(jsonb_agg(jsonb_build_object('code', f.code, 'name', f.name,
                  'trips', jsonb_array_length(sm.summary -> 'trips'), 'amount', sm.summary -> 'receivable')
                  order by (f.id = c.folder_id) desc nulls last, f.created_at desc), '[]'::jsonb)
                from public.folders f left join public.carpools c on c.id = t.carpool_id
                left join lateral (select id from public.folder_batches where folder_id = f.id and status = 'open'
                                   order by created_at desc limit 1) o on true
                cross join lateral (select public._batch_summary(o.id) as summary) sm
                where f.archived_at is null
                  and ((t.driver_id is not null and f.driver_id = t.driver_id)
                       or (t.vehicle_id is not null and f.vehicle_id = t.vehicle_id))),
    'suggested_folder', (select f.code from public.carpools c join public.folders f on f.id = c.folder_id
                         where c.id = t.carpool_id and f.archived_at is null),
    'revisions', (select coalesce(jsonb_agg(jsonb_build_object('total', r.payload -> 'total', 'reason', r.reason,
                    'replaced_at', r.replaced_at) order by r.replaced_at), '[]'::jsonb)
                  from public.settlement_revisions r where r.trip_id = t.id),
    'issues', (select coalesce(jsonb_agg(jsonb_build_object('message', i.message, 'created_at', i.created_at,
                 'name', (select name from public.trip_passengers p where p.id = i.passenger_id))
                 order by i.created_at), '[]'::jsonb)
               from public.trip_issues i where i.trip_id = t.id));
end $$;

create or replace function public.set_trip_status(p_code text, p_admin_secret text, p_status text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare t public.trips := public._trip_admin(p_code, p_admin_secret);
begin
  if p_status not in ('preparing', 'in_progress', 'cancelled') then raise exception 'bad_status'; end if;
  update public.trips set
    status = p_status,
    started_at = case when p_status = 'in_progress' then coalesce(started_at, now()) else started_at end,
    ended_at = case when p_status = 'cancelled' then now() else ended_at end
  where id = t.id;
  return public.get_trip_admin(p_code, p_admin_secret);
end $$;

-- Live course and expected shares from the car (sent while parked or at each stop).
-- p_estimates: [{passenger_id, amount}]
create or replace function public.update_progress(p_code text, p_admin_secret text, p_progress jsonb,
                                                  p_estimates jsonb default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare t public.trips := public._trip_admin(p_code, p_admin_secret);
begin
  update public.trips set progress = p_progress where id = t.id;
  if p_estimates is not null then
    update public.trip_passengers p set estimate = (e ->> 'amount')::numeric
    from jsonb_array_elements(p_estimates) e
    where p.trip_id = t.id and p.id::text = e ->> 'passenger_id';
  end if;
  return jsonb_build_object('ok', true);
end $$;

-- Seat matching and boarding events reported by the car.
create or replace function public.mark_passenger(
  p_code text, p_admin_secret text, p_passenger_id uuid,
  p_seat text default null, p_boarded boolean default null, p_alighted boolean default null
) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare t public.trips := public._trip_admin(p_code, p_admin_secret);
begin
  update public.trip_passengers set
    seat = coalesce(p_seat, seat),
    boarded_at = case when p_boarded is true then coalesce(boarded_at, now()) else boarded_at end,
    alighted_at = case when p_alighted is true then coalesce(alighted_at, now()) else alighted_at end
  where id = p_passenger_id and trip_id = t.id;
  return public.get_trip_admin(p_code, p_admin_secret);
end $$;

-- Posting again corrects the settlement (a seat fixed after arrival, a removed place): the previous
-- result is kept in settlement_revisions with the reason. A trip whose folder batch was already
-- requested cannot change, since passengers were told a fixed sum.
create or replace function public.post_settlement(p_code text, p_admin_secret text, p_payload jsonb,
                                                  p_reason text default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  t public.trips := public._trip_admin(p_code, p_admin_secret);
  old public.settlements;
begin
  select * into old from public.settlements where trip_id = t.id;
  if old.trip_id is not null then
    if t.batch_id is not null and (select status from public.folder_batches where id = t.batch_id) <> 'open' then
      raise exception 'batch_locked';
    end if;
    insert into public.settlement_revisions (trip_id, payload, reason) values (t.id, old.payload, p_reason);
  end if;
  insert into public.settlements (trip_id, payload) values (t.id, p_payload)
  on conflict (trip_id) do update set payload = excluded.payload, settled_at = now();
  update public.trips set status = 'settled', ended_at = coalesce(ended_at, now()), progress = null
  where id = t.id;
  -- members who agreed keep their places for the next trip of a saved carpool
  update public.carpool_members m set pickup = p.pickup, dropoff = p.dropoff
  from public.trip_passengers p
  where t.carpool_id is not null and m.carpool_id = t.carpool_id and p.trip_id = t.id
    and p.save_place and m.device_key_hash = p.device_key_hash;
  -- precise positions are no longer needed: keep them only at neighbourhood level (about 1 km)
  update public.trip_passengers set
    pickup = case when pickup ? 'lat' then pickup || jsonb_build_object(
               'lat', round((pickup ->> 'lat')::numeric, 2), 'lng', round((pickup ->> 'lng')::numeric, 2)) else pickup end,
    dropoff = case when dropoff ? 'lat' then dropoff || jsonb_build_object(
               'lat', round((dropoff ->> 'lat')::numeric, 2), 'lng', round((dropoff ->> 'lng')::numeric, 2)) else dropoff end
  where trip_id = t.id;
  return public.get_trip_admin(p_code, p_admin_secret);
end $$;

-- ---------------------------------------------------------------- 바로 정산 / 카풀 폴더

-- The folder's owner: its driver (driver secret or a car linked to that driver) or the car that made it.
create or replace function public._folder_admin(p_code text, p_secret text) returns public.folders
language plpgsql stable set search_path = public, extensions as $$
declare
  f public.folders;
  d public.drivers := public._driver(p_secret);
  v public.vehicles := public._vehicle(p_secret);
begin
  select * into f from public.folders where code = upper(p_code) and archived_at is null;
  if f.id is null then raise exception 'folder_not_found'; end if;
  if (d.id is not null and d.id = f.driver_id)
     or (v.id is not null and (v.id = f.vehicle_id or (f.driver_id is not null and v.driver_id = f.driver_id))) then
    return f;
  end if;
  raise exception 'not_allowed';
end $$;

create or replace function public._open_batch(p_folder uuid) returns uuid
language plpgsql volatile set search_path = public, extensions as $$
declare b uuid;
begin
  select id into b from public.folder_batches where folder_id = p_folder and status = 'open'
  order by created_at desc limit 1;
  if b is null then
    insert into public.folder_batches (folder_id) values (p_folder) returning id into b;
  end if;
  return b;
end $$;

-- Totals of one batch: every trip, each passenger's sum (matched across trips by device), the driver's part.
create or replace function public._batch_summary(p_batch uuid) returns jsonb
language sql stable set search_path = public, extensions as $$
  with shares as (
    select t.id as trip_id, x ->> 'role' as role, (x ->> 'amount')::numeric as amount,
           p.device_key_hash, p.name
    from public.trips t
    join public.settlements st on st.trip_id = t.id
    cross join lateral jsonb_array_elements(st.payload -> 'shares') x
    left join public.trip_passengers p on p.id::text = x ->> 'passenger_id'
    where t.batch_id = p_batch
  )
  select jsonb_build_object(
    'trips', (select coalesce(jsonb_agg(jsonb_build_object(
                'code', t.code, 'title', t.title, 'date', coalesce(t.ended_at, t.created_at),
                'total', (st.payload ->> 'total')::numeric,
                'km', st.payload #> '{fuel,distance_km}',
                'shares', (select coalesce(jsonb_agg(jsonb_build_object('name', x ->> 'name',
                             'role', x ->> 'role', 'amount', (x ->> 'amount')::numeric) order by ord), '[]'::jsonb)
                           from jsonb_array_elements(st.payload -> 'shares') with ordinality as y(x, ord)))
                order by coalesce(t.ended_at, t.created_at) desc), '[]'::jsonb)
              from public.trips t join public.settlements st on st.trip_id = t.id
              where t.batch_id = p_batch),
    'people', (select coalesce(jsonb_agg(jsonb_build_object('name', name, 'amount', amount, 'trips', n)
                 order by name), '[]'::jsonb)
               from (select max(name) as name, sum(amount) as amount, count(distinct trip_id) as n
                     from shares where role is distinct from 'driver' and device_key_hash is not null
                     group by device_key_hash) g),
    'driver_amount', (select coalesce(sum(amount), 0) from shares where role = 'driver'),
    'receivable', (select coalesce(sum(amount), 0) from shares
                   where role is distinct from 'driver' and device_key_hash is not null),
    'total', (select coalesce(sum((st.payload ->> 'total')::numeric), 0)
              from public.trips t join public.settlements st on st.trip_id = t.id where t.batch_id = p_batch))
$$;

-- What one passenger sees on a trip stacked into a carpool folder (M5): their own rides and running
-- sum only. Folder trips get no settlement page of their own; when the driver presses 합쳐서 정산하기
-- one page is made for the batch (get_settle_page), and `page` points the trip page there. An unpaid
-- request in the same folder comes first, so the newest trip link (and the favourite link) leads to it.
create or replace function public._folder_for(t public.trips, me public.trip_passengers) returns jsonb
language plpgsql stable set search_path = public, extensions as $$
declare
  b public.folder_batches;
  f public.folders;
  page text;
  rides jsonb;
begin
  if t.batch_id is null or me.id is null then return null; end if;
  select * into b from public.folder_batches where id = t.batch_id;
  select * into f from public.folders where id = b.folder_id;
  select r.code into page from public.folder_batches r
  join public.batch_payments bp on bp.batch_id = r.id
  where r.folder_id = b.folder_id and r.status = 'requested'
    and bp.device_key_hash = me.device_key_hash and bp.paid_at is null
  order by r.requested_at limit 1;
  if page is null and b.status <> 'open' then page := b.code; end if;
  select coalesce(jsonb_agg(jsonb_build_object(
           'date', coalesce(t2.ended_at, t2.created_at), 'title', t2.title,
           'amount', (x ->> 'amount')::numeric, 'is_this', t2.id = t.id)
           order by coalesce(t2.ended_at, t2.created_at) desc), '[]'::jsonb)
    into rides
  from public.trips t2
  join public.settlements st on st.trip_id = t2.id
  join public.trip_passengers p on p.trip_id = t2.id and p.device_key_hash = me.device_key_hash
  cross join lateral jsonb_array_elements(st.payload -> 'shares') x
  where t2.batch_id = b.id and x ->> 'passenger_id' = p.id::text;
  return jsonb_build_object(
    'name', f.name, 'status', b.status, 'page', page, 'rides', rides,
    'my_total', (select coalesce(sum((r ->> 'amount')::numeric), 0) from jsonb_array_elements(rides) r));
end $$;

-- The phone opening a settlement page: matched by the device key it rode with, or by one verified
-- there with name + last 4 digits.
create or replace function public._payment_for(p_batch uuid, p_device_key text) returns public.batch_payments
language sql stable set search_path = public, extensions as $$
  select * from public.batch_payments
  where batch_id = p_batch and p_device_key is not null
    and (device_key_hash = public._hash(p_device_key) or public._hash(p_device_key) = any(device_hashes))
  limit 1
$$;

-- The one settlement page of a batch (M6 정산 중, M6-1 정산 완료). Everyone in the folder gets the same
-- link or QR, and each phone sees only its own rides and sum. The account is shown while that
-- person's part is unpaid.
create or replace function public.get_settle_page(p_code text, p_device_key text default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  b public.folder_batches;
  f public.folders;
  d public.drivers;
  pay public.batch_payments;
  rides jsonb;
begin
  select * into b from public.folder_batches where code = upper(p_code);
  if b.id is null then raise exception 'page_not_found'; end if;
  select * into f from public.folders where id = b.folder_id;
  select * into d from public.drivers where id = f.driver_id;
  pay := public._payment_for(b.id, p_device_key);
  if pay.id is not null then
    select coalesce(jsonb_agg(jsonb_build_object(
             'date', coalesce(t.ended_at, t.created_at), 'title', t.title,
             'amount', (x ->> 'amount')::numeric, 'from', x ->> 'from', 'to', x ->> 'to', 'km', x -> 'km',
             'total', (st.payload ->> 'total')::numeric,
             'driver', (select coalesce(sum((y ->> 'amount')::numeric), 0)
                        from jsonb_array_elements(st.payload -> 'shares') y where y ->> 'role' = 'driver'))
             order by coalesce(t.ended_at, t.created_at) desc), '[]'::jsonb)
      into rides
    from public.trips t
    join public.settlements st on st.trip_id = t.id
    join public.trip_passengers p on p.trip_id = t.id and p.device_key_hash = pay.device_key_hash
    cross join lateral jsonb_array_elements(st.payload -> 'shares') x
    where t.batch_id = b.id and x ->> 'passenger_id' = p.id::text;
  end if;
  return jsonb_build_object(
    'code', b.code, 'name', f.name, 'status', b.status, 'requested_at', b.requested_at, 'done_at', b.done_at,
    'driver_name', coalesce(d.display_name, '운전자'),
    'next_title', (select title from public.trips where batch_id = b.id order by created_at desc limit 1),
    'me', case when pay.id is null then null else jsonb_build_object(
      'name', pay.name, 'amount', pay.amount, 'trips', pay.trips, 'paid', pay.paid_at is not null,
      'rides', rides) end,
    'bank', case when pay.id is not null and pay.paid_at is null and b.status = 'requested'
                      and d.bank_consent_at is not null
              then jsonb_build_object('bank_name', d.bank_name, 'account', public._dec(d.bank_account),
                                      'holder', public._dec(d.account_holder)) end);
end $$;

-- Opening the settlement page on a phone that did not ride: name + last 4 digits, 20 tries per batch.
create or replace function public.verify_settle_page(p_code text, p_device_key text, p_name text, p_phone4 text)
returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  b public.folder_batches;
  pid uuid;
begin
  if coalesce(length(p_device_key), 0) < 16 then raise exception 'bad_device_key'; end if;
  select * into b from public.folder_batches where code = upper(p_code);
  if b.id is null then raise exception 'page_not_found'; end if;
  if b.verify_fails >= 20 then raise exception 'too_many_attempts'; end if;
  select bp.id into pid from public.batch_payments bp
  where bp.batch_id = b.id and exists (
    select 1 from public.trip_passengers p join public.trips t on t.id = p.trip_id
    where t.batch_id = b.id and p.device_key_hash = bp.device_key_hash and p.name = trim(p_name)
      and p.phone4_hash = public._hash(t.id::text || coalesce(p_phone4, '')))
  limit 1;
  if pid is null then
    update public.folder_batches set verify_fails = verify_fails + 1 where id = b.id;
    return jsonb_build_object('error', 'verify_failed');
  end if;
  update public.batch_payments set device_hashes = array_append(device_hashes, public._hash(p_device_key))
  where id = pid and not (public._hash(p_device_key) = any(device_hashes));
  return public.get_settle_page(p_code, p_device_key);
end $$;

-- "정산에 문제가 있어요" on the settlement page: filed on the newest trip this person rode in the batch.
create or replace function public.report_settle_issue(p_code text, p_device_key text, p_message text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  b public.folder_batches;
  pay public.batch_payments;
  p public.trip_passengers;
begin
  select * into b from public.folder_batches where code = upper(p_code);
  if b.id is null then raise exception 'page_not_found'; end if;
  pay := public._payment_for(b.id, p_device_key);
  if pay.id is null then raise exception 'not_allowed'; end if;
  select p2.* into p from public.trip_passengers p2 join public.trips t on t.id = p2.trip_id
  where t.batch_id = b.id and p2.device_key_hash = pay.device_key_hash
  order by t.created_at desc limit 1;
  insert into public.trip_issues (trip_id, passenger_id, message)
  values (p.trip_id, p.id, left(coalesce(nullif(trim(p_message), ''), '정산에 문제가 있어요'), 500));
  return jsonb_build_object('ok', true);
end $$;

-- At the arrival screen (L6): settle this trip now (바로 정산) or stack it into a folder.
create or replace function public.choose_settlement(
  p_code text, p_admin_secret text, p_mode text,
  p_folder_code text default null, p_new_folder text default null
) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  t public.trips := public._trip_admin(p_code, p_admin_secret);
  f public.folders;
  fc text;
begin
  if t.status <> 'settled' then raise exception 'not_settled'; end if;
  if t.batch_id is not null
     and (select status from public.folder_batches where id = t.batch_id) <> 'open' then
    raise exception 'batch_locked';
  end if;
  if p_mode = 'direct' then
    update public.trips set settle_mode = 'direct', batch_id = null where id = t.id;
  elsif p_mode = 'folder' then
    if p_folder_code is not null then
      select * into f from public.folders
      where code = upper(p_folder_code) and archived_at is null
        and ((t.driver_id is not null and driver_id = t.driver_id) or (t.vehicle_id is not null and vehicle_id = t.vehicle_id));
      if f.id is null then raise exception 'folder_not_found'; end if;
    elsif nullif(trim(p_new_folder), '') is not null then
      loop
        fc := public._code(8);
        exit when not exists (select 1 from public.folders where code = fc);
      end loop;
      insert into public.folders (code, driver_id, vehicle_id, name)
      values (fc, t.driver_id, t.vehicle_id, trim(p_new_folder)) returning * into f;
    else
      raise exception 'folder_required';
    end if;
    update public.trips set settle_mode = 'folder', batch_id = public._open_batch(f.id) where id = t.id;
    update public.trip_passengers set paid_at = null where trip_id = t.id;
    -- favourites remember their folder, so the next trip of this carpool has it pre-selected
    update public.carpools set folder_id = f.id where id = t.carpool_id;
  else
    raise exception 'bad_mode';
  end if;
  return public.get_trip_admin(p_code, p_admin_secret);
end $$;

-- 바로 정산: the driver ticks a passenger as paid; their page then hides the account.
create or replace function public.mark_paid(p_code text, p_admin_secret text, p_passenger_id uuid,
                                            p_paid boolean default true) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare t public.trips := public._trip_admin(p_code, p_admin_secret);
begin
  if t.settle_mode is distinct from 'direct' then raise exception 'not_direct'; end if;
  update public.trip_passengers set paid_at = case when p_paid then coalesce(paid_at, now()) end
  where id = p_passenger_id and trip_id = t.id;
  return public.get_trip_admin(p_code, p_admin_secret);
end $$;

-- 카풀 폴더 list: name, state and what has piled up ("출근 카풀 [요청 전] 9회 21,870원").
create or replace function public.list_folders(p_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  d public.drivers := public._driver(p_secret);
  v public.vehicles := public._vehicle(p_secret);
begin
  if d.id is null and v.id is null then raise exception 'not_allowed'; end if;
  return (select coalesce(jsonb_agg(item order by created_at desc), '[]'::jsonb) from (
    select f.created_at, jsonb_build_object(
      'code', f.code, 'name', f.name,
      'status', case when r.id is not null then 'requested' else 'open' end,
      'trips', jsonb_array_length(sm.summary -> 'trips'),
      'amount', sm.summary -> 'receivable') as item
    from public.folders f
    left join lateral (select id from public.folder_batches where folder_id = f.id and status = 'requested'
                       order by created_at desc limit 1) r on true
    left join lateral (select id from public.folder_batches where folder_id = f.id and status = 'open'
                       order by created_at desc limit 1) o on true
    cross join lateral (select public._batch_summary(coalesce(r.id, o.id)) as summary) sm
    where f.archived_at is null
      and ((d.id is not null and f.driver_id = d.id)
           or (v.id is not null and (f.vehicle_id = v.id or (v.driver_id is not null and f.driver_id = v.driver_id))))
  ) x);
end $$;

-- One folder for the driver (L1-6, L1-7): the batch being settled, if any, and the open one.
create or replace function public.get_folder_admin(p_folder_code text, p_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  f public.folders := public._folder_admin(p_folder_code, p_secret);
  r public.folder_batches;
  o uuid := public._open_batch(f.id);
begin
  select * into r from public.folder_batches where folder_id = f.id and status = 'requested'
  order by created_at desc limit 1;
  return jsonb_build_object(
    'code', f.code, 'name', f.name,
    'requested', case when r.id is null then null else public._batch_summary(r.id) || jsonb_build_object(
      'requested_at', r.requested_at, 'code', r.code,
      'issues', (select coalesce(jsonb_agg(jsonb_build_object('message', i.message, 'created_at', i.created_at,
                   'name', (select name from public.trip_passengers p where p.id = i.passenger_id))
                   order by i.created_at), '[]'::jsonb)
                 from public.trip_issues i join public.trips t on t.id = i.trip_id where t.batch_id = r.id),
      'payments', (select coalesce(jsonb_agg(jsonb_build_object('id', id, 'name', name, 'amount', amount,
                     'trips', trips, 'paid', paid_at is not null) order by name), '[]'::jsonb)
                   from public.batch_payments where batch_id = r.id)) end,
    'open', public._batch_summary(o),
    'last_done', (select jsonb_build_object('done_at', done_at) from public.folder_batches
                  where folder_id = f.id and status = 'done' order by done_at desc limit 1));
end $$;

-- 합쳐서 정산하기: fix every passenger's sum and show it (with the account) on their pages.
-- New trips keep stacking into a fresh open batch meanwhile.
create or replace function public.request_batch(p_folder_code text, p_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  f public.folders := public._folder_admin(p_folder_code, p_secret);
  o uuid := public._open_batch(f.id);
  pc text;
begin
  if exists (select 1 from public.folder_batches where folder_id = f.id and status = 'requested') then
    raise exception 'batch_in_progress';
  end if;
  if not exists (select 1 from public.trips where batch_id = o) then raise exception 'nothing_to_settle'; end if;
  insert into public.batch_payments (batch_id, device_key_hash, name, amount, trips)
  select o, p.device_key_hash, max(p.name), sum((x ->> 'amount')::numeric), count(distinct t.id)
  from public.trips t
  join public.settlements st on st.trip_id = t.id
  cross join lateral jsonb_array_elements(st.payload -> 'shares') x
  join public.trip_passengers p on p.id::text = x ->> 'passenger_id'
  where t.batch_id = o
  group by p.device_key_hash;
  loop
    pc := public._code(8);
    exit when not exists (select 1 from public.folder_batches where code = pc);
  end loop;
  update public.folder_batches set status = 'requested', requested_at = now(), code = pc where id = o;
  insert into public.folder_batches (folder_id) values (f.id);
  return public.get_folder_admin(p_folder_code, p_secret);
end $$;

-- 정산 요청 취소: back to 요청 전, and trips stacked since then join it again.
create or replace function public.cancel_batch_request(p_folder_code text, p_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  f public.folders := public._folder_admin(p_folder_code, p_secret);
  r uuid;
begin
  select id into r from public.folder_batches where folder_id = f.id and status = 'requested'
  order by created_at desc limit 1;
  if r is null then raise exception 'no_request'; end if;
  update public.trips set batch_id = r
  where batch_id in (select id from public.folder_batches where folder_id = f.id and status = 'open');
  delete from public.folder_batches where folder_id = f.id and status = 'open';
  delete from public.batch_payments where batch_id = r;
  update public.folder_batches set status = 'open', requested_at = null, code = null, verify_fails = 0 where id = r;
  return public.get_folder_admin(p_folder_code, p_secret);
end $$;

-- The driver ticks a passenger's sum as received; when everyone is ticked the batch is done.
create or replace function public.mark_batch_paid(p_folder_code text, p_secret text, p_payment_id uuid,
                                                  p_paid boolean default true) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  f public.folders := public._folder_admin(p_folder_code, p_secret);
  r uuid;
begin
  select id into r from public.folder_batches where folder_id = f.id and status = 'requested'
  order by created_at desc limit 1;
  if r is null then raise exception 'no_request'; end if;
  update public.batch_payments set paid_at = case when p_paid then coalesce(paid_at, now()) end
  where id = p_payment_id and batch_id = r;
  if not exists (select 1 from public.batch_payments where batch_id = r and paid_at is null) then
    update public.folder_batches set status = 'done', done_at = now() where id = r;
  end if;
  return public.get_folder_admin(p_folder_code, p_secret);
end $$;

-- ---------------------------------------------------------------- regular carpools

create or replace function public.save_carpool(p_code text, p_admin_secret text, p_driver_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  t public.trips := public._trip_admin(p_code, p_admin_secret);
  d public.drivers := public._driver(p_driver_secret);
  c public.carpools;
  cc text;
begin
  if d.id is null then raise exception 'not_allowed'; end if;
  if t.carpool_id is not null then
    select * into c from public.carpools where id = t.carpool_id;
    return jsonb_build_object('carpool_code', c.code);
  end if;
  loop
    cc := public._code(8);
    exit when not exists (select 1 from public.carpools where code = cc);
  end loop;
  insert into public.carpools (code, driver_id, title, origin, destination, include_driver, seats, rule)
  values (cc, d.id, t.title, t.origin, t.destination, t.include_driver, t.seats, t.rule) returning * into c;
  insert into public.carpool_members (carpool_id, name, pickup, dropoff, device_key_hash, phone4_hash)
  select c.id, name,
         case when save_place then pickup end, case when save_place then dropoff end,
         device_key_hash, phone4_hash
  from public.trip_passengers where trip_id = t.id;
  update public.trips set carpool_id = c.id, driver_id = coalesce(driver_id, d.id) where id = t.id;
  return jsonb_build_object('carpool_code', c.code);
end $$;

-- "Make the last trip again": a new trip with the same members, and the places of those who
-- agreed to keep them already filled in.
create or replace function public.recreate_trip(p_carpool_code text, p_driver_secret text default null,
                                                p_vehicle_secret text default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  c public.carpools;
  d public.drivers := public._driver(p_driver_secret);
  v public.vehicles := public._vehicle(p_vehicle_secret);
  s text := public._secret();
  t public.trips;
begin
  select * into c from public.carpools where code = upper(p_carpool_code) and archived_at is null;
  if c.id is null then raise exception 'carpool_not_found'; end if;
  if not (coalesce(d.id = c.driver_id, false) or coalesce(v.driver_id = c.driver_id, false)) then
    raise exception 'not_allowed';
  end if;
  insert into public.trips (code, admin_hash, driver_id, vehicle_id, carpool_id, title, origin, destination,
                            include_driver, seats, rule)
  values (public._new_trip_code(), public._hash(s), c.driver_id, v.id, c.id, c.title, c.origin, c.destination,
          c.include_driver, c.seats, c.rule)
  returning * into t;
  insert into public.trip_passengers (trip_id, name, pickup, dropoff, device_key_hash, phone4_hash, save_place)
  select t.id, name, pickup, dropoff, coalesce(device_key_hash, public._hash(id::text)), phone4_hash,
         pickup is not null or dropoff is not null
  from public.carpool_members where carpool_id = c.id;
  return jsonb_build_object('trip_id', t.id, 'code', t.code, 'admin_secret', s);
end $$;

-- Stable link a passenger keeps on the home screen: resolves to the carpool's newest trip and
-- adds up this month's settled amounts for that passenger.
create or replace function public.get_carpool(p_carpool_code text, p_device_key text default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare c public.carpools;
begin
  select * into c from public.carpools where code = upper(p_carpool_code) and archived_at is null;
  if c.id is null then raise exception 'carpool_not_found'; end if;
  return jsonb_build_object(
    'code', c.code, 'title', c.title,
    'latest_trip', (select code from public.trips where carpool_id = c.id and status <> 'cancelled'
                    order by created_at desc limit 1),
    'month', (select jsonb_build_object('trips', count(*),
                       'my_amount', coalesce(sum((sh ->> 'amount')::numeric), 0))
              from public.trips t
              join public.settlements s on s.trip_id = t.id
              join public.trip_passengers p on p.trip_id = t.id
                   and p_device_key is not null and p.device_key_hash = public._hash(p_device_key)
              cross join lateral jsonb_array_elements(s.payload -> 'shares') sh
              where t.carpool_id = c.id and t.status = 'settled'
                and date_trunc('month', s.settled_at) = date_trunc('month', now())
                and sh ->> 'passenger_id' = p.id::text));
end $$;

-- The x next to an address on the favourites screen: removes the place kept for the next trip.
-- Trip records are not touched. The driver's phone or a car linked to the driver may do it.
create or replace function public.remove_member_place(p_carpool_code text, p_secret text, p_member_id uuid)
returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  d public.drivers := public._driver(p_secret);
  v public.vehicles := public._vehicle(p_secret);
  c public.carpools;
begin
  select * into c from public.carpools where code = upper(p_carpool_code) and archived_at is null;
  if c.id is null then raise exception 'carpool_not_found'; end if;
  if not (coalesce(d.id = c.driver_id, false) or coalesce(v.driver_id = c.driver_id, false)) then
    raise exception 'not_allowed';
  end if;
  update public.carpool_members set pickup = null, dropoff = null where id = p_member_id and carpool_id = c.id;
  return jsonb_build_object('code', c.code, 'members', public._carpool_members(c.id));
end $$;

create or replace function public.archive_carpool(p_carpool_code text, p_driver_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare d public.drivers := public._driver(p_driver_secret);
begin
  update public.carpools set archived_at = now()
  where code = upper(p_carpool_code) and driver_id = d.id;
  return jsonb_build_object('ok', found);
end $$;

-- ---------------------------------------------------------------- vehicle link

-- The car calls this once and shows link_code as a QR; the driver's phone claims it.
create or replace function public.create_vehicle_link(p_vehicle_secret text default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  v public.vehicles := public._vehicle(p_vehicle_secret);
  s text;
  lc text;
begin
  loop
    lc := public._code(6);
    exit when not exists (select 1 from public.vehicles where link_code = lc);
  end loop;
  if v.id is null then
    s := public._secret();
    insert into public.vehicles (secret_hash, link_code, link_expires_at)
    values (public._hash(s), lc, now() + interval '10 minutes') returning * into v;
  else
    update public.vehicles set link_code = lc, link_expires_at = now() + interval '10 minutes'
    where id = v.id returning * into v;
  end if;
  return jsonb_build_object('vehicle_id', v.id, 'vehicle_secret', s, 'link_code', lc,
                            'expires_at', v.link_expires_at);
end $$;

create or replace function public.claim_vehicle_link(p_link_code text, p_driver_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare d public.drivers := public._driver(p_driver_secret);
begin
  if d.id is null then raise exception 'not_allowed'; end if;
  update public.vehicles set driver_id = d.id, link_code = null, link_expires_at = null
  where link_code = upper(p_link_code) and link_expires_at > now() and revoked_at is null;
  if not found then raise exception 'link_expired'; end if;
  return jsonb_build_object('ok', true);
end $$;

-- Trips the linked driver prepared on the phone, for the car to load at ignition.
create or replace function public.get_vehicle_trips(p_vehicle_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare v public.vehicles := public._vehicle(p_vehicle_secret);
begin
  if v.id is null then raise exception 'not_allowed'; end if;
  return jsonb_build_object(
    'linked', v.driver_id is not null,
    'trips', (select coalesce(jsonb_agg(jsonb_build_object('code', t.code, 'title', t.title,
                'passengers', (select count(*) from public.trip_passengers p where p.trip_id = t.id))
                order by t.created_at desc), '[]'::jsonb)
              from public.trips t
              where t.driver_id = v.driver_id and t.status = 'preparing'
                and t.created_at > now() - interval '7 days'),
    'carpools', (select coalesce(jsonb_agg(jsonb_build_object('code', c.code, 'title', c.title)), '[]'::jsonb)
                 from public.carpools c where c.driver_id = v.driver_id and c.archived_at is null));
end $$;

create or replace function public.revoke_vehicle(p_vehicle_secret text) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
begin
  update public.vehicles set revoked_at = now(), driver_id = null
  where secret_hash = public._hash(p_vehicle_secret);
  return jsonb_build_object('ok', found);
end $$;

-- ---------------------------------------------------------------- retention

-- Run daily (see the cron migration): stops are kept only while they are needed.
create or replace function public.purge_old_locations() returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare n_loc int; n_old int;
begin
  update public.trip_passengers p set pickup = null, dropoff = null
  from public.trips t
  where p.trip_id = t.id and t.ended_at < now() - interval '24 hours'
    and (p.pickup is not null or p.dropoff is not null);
  get diagnostics n_loc = row_count;
  update public.trips set progress = null
  where ended_at < now() - interval '24 hours' and progress is not null;
  update public.trips set status = 'cancelled', ended_at = now()
  where status = 'preparing' and created_at < now() - interval '7 days';
  get diagnostics n_old = row_count;
  return jsonb_build_object('cleared_locations', n_loc, 'expired_trips', n_old);
end $$;

-- ---------------------------------------------------------------- permissions

revoke all on all tables in schema public from public;
revoke all on all functions in schema public from public;

do $$
declare
  fn text;
  api text[] := array[
    'create_driver', 'update_driver', 'get_driver',
    'create_trip', 'get_trip', 'join_trip', 'verify_device', 'leave_trip', 'forget_my_place', 'report_issue',
    'get_settle_page', 'verify_settle_page', 'report_settle_issue', 'remove_member_place',
    'get_trip_admin', 'set_trip_status', 'update_progress', 'mark_passenger', 'post_settlement',
    'choose_settlement', 'mark_paid', 'list_folders', 'get_folder_admin', 'request_batch',
    'cancel_batch_request', 'mark_batch_paid',
    'save_carpool', 'recreate_trip', 'get_carpool', 'archive_carpool',
    'create_vehicle_link', 'claim_vehicle_link', 'get_vehicle_trips', 'revoke_vehicle'];
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on all tables in schema public from anon, authenticated';
    execute 'revoke all on all functions in schema public from anon, authenticated';
    foreach fn in array api loop
      execute format('grant execute on function public.%I to anon, authenticated', fn);
    end loop;
  end if;
end $$;
