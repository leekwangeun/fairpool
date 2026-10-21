-- FairPool server schema.
-- No user accounts: drivers, passengers and vehicles are identified by random secrets kept on
-- their own device; the server stores only SHA-256 hashes. Tables are closed to the public API
-- (RLS on, no policies) and every read or write goes through the security-definer functions below.

create extension if not exists pgcrypto;

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

-- ---------------------------------------------------------------- tables

create table public.drivers (
  id              uuid primary key default gen_random_uuid(),
  secret_hash     text not null unique,
  display_name    text not null default '운전자',
  bank_name       text,
  bank_account    text,
  account_holder  text,
  bank_consent_at timestamptz,          -- account is shown to passengers only after consent
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
  created_at     timestamptz not null default now(),
  archived_at    timestamptz
);

-- Stops are copied into the next trip only for members who agreed to keep them (save_place).
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
  save_place      boolean not null default false,
  estimate        numeric,               -- expected share posted by the car while driving
  seat            text,
  joined_at       timestamptz not null default now(),
  boarded_at      timestamptz,
  alighted_at     timestamptz,
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

alter table public.drivers         enable row level security;
alter table public.vehicles        enable row level security;
alter table public.carpools        enable row level security;
alter table public.carpool_members enable row level security;
alter table public.trips           enable row level security;
alter table public.trip_passengers enable row level security;
alter table public.settlements     enable row level security;
alter table public.trip_issues     enable row level security;

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
           'save_place', save_place, 'estimate', estimate,
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
      'bank_name', d.bank_name, 'account', d.bank_account, 'holder', d.account_holder) end,
    'carpools', (select coalesce(jsonb_agg(jsonb_build_object('code', c.code, 'title', c.title)
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
    bank_account    = case when p_bank_consent is false then null else coalesce(p_bank_account, bank_account) end,
    account_holder  = case when p_bank_consent is false then null else coalesce(p_account_holder, account_holder) end,
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
            'alighted', me.alighted_at is not null) end,
    'progress', public._progress_for(t.progress, me),
    'settlement', public._settlement_for(s.payload, me),
    -- the account is shown only to people who actually rode, after the trip is settled
    'bank', case when t.status = 'settled' and me.id is not null and d.bank_consent_at is not null
              then jsonb_build_object('bank_name', d.bank_name, 'account', d.bank_account,
                                      'holder', d.account_holder) end);
end $$;

create or replace function public.join_trip(
  p_code text,
  p_device_key text,
  p_name text,
  p_pickup jsonb default null,
  p_dropoff jsonb default null,
  p_phone4 text default null,
  p_save_place boolean default false
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
          coalesce(p_save_place, false))
  on conflict (trip_id, device_key_hash) do update
    set name = excluded.name,
        pickup = excluded.pickup,
        dropoff = coalesce(excluded.dropoff, public.trip_passengers.dropoff),
        phone4_hash = coalesce(excluded.phone4_hash, public.trip_passengers.phone4_hash),
        save_place = excluded.save_place;
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
    update public.trips set verify_fails = verify_fails + 1 where id = t.id;
    raise exception 'verify_failed';
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

-- "내 장소 지우기": stop keeping this passenger's places for the next carpool trips.
create or replace function public.forget_my_place(p_code text, p_device_key text) returns jsonb
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
  update public.trip_passengers set save_place = false where id = me.id;
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

create or replace function public.post_settlement(p_code text, p_admin_secret text, p_payload jsonb) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare t public.trips := public._trip_admin(p_code, p_admin_secret);
begin
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
    'get_trip_admin', 'set_trip_status', 'update_progress', 'mark_passenger', 'post_settlement',
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
