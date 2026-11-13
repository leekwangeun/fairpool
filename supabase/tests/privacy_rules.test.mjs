// What other riders may see, how long places are kept, when records lock for good, and the settlement
// fields the car sends.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';

const migration = readFileSync(new URL('../migrations/20261022000000_init.sql', import.meta.url), 'utf8');
const cron = readFileSync(new URL('../migrations/20261022000100_retention_cron.sql', import.meta.url), 'utf8');

async function freshDb() {
  const db = new PGlite({ extensions: { pgcrypto } });
  await db.exec(migration);
  return db;
}

async function rpc(db, fn, args = {}) {
  const names = Object.keys(args);
  const params = names.map((n) => {
    const v = args[n];
    return v !== null && typeof v === 'object' ? JSON.stringify(v) : v;
  });
  const sql = `select public.${fn}(${names.map((n, i) => `${n} => $${i + 1}`).join(', ')}) as r`;
  return (await db.query(sql, params)).rows[0].r;
}

const device = (n) => `device-key-${n}-0123456789abcdef`;
const spot = (label, area, lat = 37.5612, lng = 126.9234) => ({ label, area, address: `${area} 1-2`, lat, lng });

async function driverWithCar(db) {
  const { driver_secret } = await rpc(db, 'create_driver', { p_display_name: '지훈' });
  const car = await rpc(db, 'create_vehicle_link', {});
  await rpc(db, 'claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  await rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: true, p_bank_name: '페어은행',
                                      p_bank_account: '111-222-333', p_account_holder: '지훈' });
  await rpc(db, 'set_driver_info', { p_vehicle_secret: car.vehicle_secret, p_name: '지훈', p_phone4: '7890' });
  return { driver_secret, vehicle_secret: car.vehicle_secret };
}

const admin = (db, trip) => rpc(db, 'get_trip_admin', { p_code: trip.code, p_admin_secret: trip.admin_secret });
const view = (db, trip, n) => rpc(db, 'get_trip', { p_code: trip.code, p_device_key: device(n) });

// 수진 gets on at the origin, 민호 at 신촌역; both ride to the end
async function twoRiders(db, vehicle_secret, origin = spot('지훈 집', '은평구 응암동', 37.6, 126.92)) {
  const trip = await rpc(db, 'create_trip', { p_title: '출근', p_vehicle_secret: vehicle_secret, p_origin: origin,
                                              p_destination: spot('회사 주차장', '중구 태평로1가', 37.57, 126.98) });
  await rpc(db, 'join_trip', { p_code: trip.code, p_device_key: device(1), p_name: '수진', p_phone4: '1111',
                               p_dropoff: spot('망원역 1번 출구', '마포구 망원동') });
  await rpc(db, 'join_trip', { p_code: trip.code, p_device_key: device(2), p_name: '민호', p_phone4: '2222',
                               p_pickup: spot('신촌역 2번 출구', '서대문구 창천동'),
                               p_dropoff: spot('연남동 집 앞', '마포구 연남동') });
  await rpc(db, 'set_trip_status', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_status: 'in_progress' });
  const ps = (await admin(db, trip)).passengers;
  const ids = Object.fromEntries(ps.map((p) => [p.name, p.id]));
  for (const p of ps) {
    await rpc(db, 'mark_passenger', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_passenger_id: p.id,
                                      p_boarded: true, p_alighted: true });
  }
  return { ...trip, ids };
}

const fullPayload = (ids, extra = {}) => ({
  rule: 'shapley', include_driver: true, total: 9000, toll: 0, distance_source: 'osrm',
  fuel: { distance_km: 30, efficiency_km_l: 11, amount: 9000 },
  coalitions: [{ members: ['수진'], km: 21.3 }, { members: ['민호'], km: 8.7 }, { members: ['수진', '민호'], km: 24.1 }],
  shares: [
    { passenger_id: ids['수진'], name: '수진', role: 'passenger', amount: 3990, km: 21.0, share_km: 9.1, alone: 6120,
      from: '지훈 집', to: '망원역 1번 출구' },
    { passenger_id: ids['민호'], name: '민호', role: 'passenger', amount: 2010, km: 8.2, share_km: 4.6, alone: 2870,
      from: '신촌역 2번 출구', to: '연남동 집 앞' },
    { passenger_id: null, name: '지훈', role: 'driver', amount: 3000, share_km: 6.8, alone: 4100 },
  ],
  ...extra,
});

// ---------------------------------------------------------------- A. what other riders see

test('a rider sees the others only as name, role and amount; no coalitions, no one else\'s basis', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: fullPayload(t.ids) });

  const s = (await view(db, t, 2)).settlement;
  assert.equal(s.coalitions, undefined, 'the coalition table names everyone with their km');
  assert.equal(s.distance_source, undefined);
  assert.deepEqual(Object.keys(s).sort(), ['fuel', 'include_driver', 'provisional', 'rule', 'shares', 'toll', 'total']);
  const [sujin, minho, driver] = s.shares;
  assert.deepEqual(sujin, { name: '수진', role: 'passenger', amount: 3990, is_me: false });
  assert.deepEqual(driver, { name: '지훈', role: 'driver', amount: 3000, is_me: false });
  assert.deepEqual(minho, { name: '민호', role: 'passenger', amount: 2010, km: 8.2, share_km: 4.6, alone: 2870,
                            from: '신촌역 2번 출구', to: '연남동 집 앞', is_me: true }, 'my own basis stays');
  const json = JSON.stringify(s);
  for (const leak of ['6120', '21.3', '24.1', '망원역', '지훈 집', '4100']) {
    assert.ok(!json.includes(leak), `${leak} must not reach another rider`);
  }
  // the driver keeps the whole payload
  assert.equal((await admin(db, t)).settlement.coalitions.length, 3);
});

test('only riders who get on at the origin see its name', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  assert.equal((await view(db, t, 1)).origin, '지훈 집');
  assert.equal((await view(db, t, 2)).origin, null);
  assert.equal((await view(db, t, 9)).origin, null);
  // a rider who first chose a pickup and then the origin (before boarding) gets to see it
  const u = await rpc(db, 'create_trip', { p_title: '퇴근', p_vehicle_secret: vehicle_secret,
                                           p_origin: spot('회사 주차장', '중구 태평로1가') });
  await rpc(db, 'join_trip', { p_code: u.code, p_device_key: device(3), p_name: '지은',
                               p_pickup: spot('시청역', '중구 태평로1가'), p_dropoff: spot('집', '마포구 망원동') });
  assert.equal((await view(db, u, 3)).origin, null);
  await rpc(db, 'join_trip', { p_code: u.code, p_device_key: device(3), p_name: '지은', p_dropoff: spot('집', '마포구 망원동') });
  assert.equal((await view(db, u, 3)).origin, '회사 주차장');
});

// ---------------------------------------------------------------- B. retention

test('a day after the trip, place names go down to area level everywhere and exact copies are gone', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: fullPayload(t.ids) });
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_reason: '하차 정정',
                                     p_payload: fullPayload(t.ids, { total: 9010 }) });
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });
  await rpc(db, 'queue_trip_share', { p_code: t.code, p_admin_secret: t.admin_secret });
  const exact = (await db.query('select count(*)::int as n from public.trip_passengers where exact_stops is not null')).rows[0].n;
  assert.equal(exact, 2, 'exact stops are kept for the arrival screen\'s favourite');

  await db.query(`update public.trips set ended_at = now() - interval '25 hours' where code = $1`, [t.code]);
  const res = await rpc(db, 'purge_old_locations');
  assert.equal(res.cleared_locations, 2);

  const a = await admin(db, t);
  const [sujin, minho] = a.settlement.shares;
  assert.deepEqual([sujin.from, sujin.to], ['은평구 응암동', '마포구 망원동'], 'the origin area for a rider who got on there');
  assert.deepEqual([minho.from, minho.to], ['서대문구 창천동', '마포구 연남동']);
  const revision = (await db.query('select payload from public.settlement_revisions')).rows[0].payload;
  assert.deepEqual(revision.shares.map((x) => [x.from, x.to]).slice(0, 2),
                   [['은평구 응암동', '마포구 망원동'], ['서대문구 창천동', '마포구 연남동']]);
  const left = (await db.query('select pickup, dropoff, exact_stops from public.trip_passengers')).rows;
  assert.ok(left.every((p) => p.pickup === null && p.dropoff === null && p.exact_stops === null));
  assert.equal(a.origin.lat, undefined, 'the trip start loses its coordinates');
  assert.equal(a.origin.address, undefined);
  assert.equal(a.origin.label, '지훈 집');
  assert.equal(a.destination.lng, undefined);

  // the driver's phone card and the passenger page only carry the area now
  const dv = await rpc(db, 'verify_device', { p_code: t.code, p_device_key: device(8), p_name: '지훈', p_phone4: '7890' });
  const card = JSON.stringify(dv.driver_view.cards);
  assert.ok(card.includes('마포구 망원동'));
  for (const exactName of ['망원역', '신촌역', '연남동 집 앞']) assert.ok(!card.includes(exactName), exactName);
  assert.equal((await view(db, t, 2)).settlement.shares[1].from, '서대문구 창천동');

  // running again does not lose the areas
  await rpc(db, 'purge_old_locations');
  assert.equal((await admin(db, t)).settlement.shares[1].to, '마포구 연남동');
});

test('a trip never settled is cleared a day after it started; a later settlement keeps no exact names', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  await db.query(`update public.trips set started_at = now() - interval '25 hours' where code = $1`, [t.code]);
  const res = await rpc(db, 'purge_old_locations');
  assert.equal(res.cleared_locations, 2, 'ended_at is still empty, the start counts');
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: fullPayload(t.ids) });
  const json = JSON.stringify((await admin(db, t)).settlement.shares);
  for (const exactName of ['망원역', '신촌역', '연남동 집 앞', '지훈 집']) assert.ok(!json.includes(exactName), exactName);
});

test('the trip start and end keep coordinates only when the driver keeps them in a favourite', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const home = spot('지훈 집', '은평구 응암동', 37.6, 126.92);
  const first = await twoRiders(db, vehicle_secret, home);
  await rpc(db, 'post_settlement', { p_code: first.code, p_admin_secret: first.admin_secret, p_payload: fullPayload(first.ids) });
  await rpc(db, 'save_carpool', { p_code: first.code, p_admin_secret: first.admin_secret });
  const other = await twoRiders(db, vehicle_secret, spot('친구 집', '마포구 서교동', 37.55, 126.91));
  await rpc(db, 'post_settlement', { p_code: other.code, p_admin_secret: other.admin_secret, p_payload: fullPayload(other.ids) });
  await db.query(`update public.trips set ended_at = now() - interval '25 hours'`);
  await rpc(db, 'purge_old_locations');
  assert.equal((await admin(db, first)).origin.lat, 37.6, 'saved in the favourite');
  assert.equal((await admin(db, other)).origin.lat, undefined);
});

test('the clean-up runs every hour', () => {
  assert.match(cron, /'15 \* \* \* \*'/);
});

// ---------------------------------------------------------------- C. corrections lock and record history

test('a folder batch once paid keeps its trips locked even after the request is cancelled', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: fullPayload(t.ids) });
  const folder = (await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'folder',
                                                       p_new_folder: '출근 카풀' })).folder.code;
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  const pay = req.payments[0];
  await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: pay.id });
  await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: pay.id, p_paid: false });
  await rpc(db, 'cancel_batch_request', { p_folder_code: folder, p_secret: vehicle_secret });
  await assert.rejects(rpc(db, 'mark_passenger', { p_code: t.code, p_admin_secret: t.admin_secret,
                                                   p_passenger_id: t.ids['민호'], p_alighted: false }), /settle_started/);
  await assert.rejects(rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_reason: '정정',
                                                    p_payload: fullPayload(t.ids, { total: 8000 }) }), /settle_started/);
});

test('changes to a ride record are kept with who and when, and only that rider sees them', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  const mark = (args) => rpc(db, 'mark_passenger', { p_code: t.code, p_admin_secret: t.admin_secret,
                                                     p_passenger_id: t.ids['민호'], ...args });
  assert.deepEqual((await view(db, t, 2)).me.record_changes, [], 'boarding as it happened is not a change');

  // "잘못 인식": the car undoes the boarding (which undoes the drop-off), then records it again
  await mark({ p_boarded: false });
  await mark({ p_boarded: true });
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: fullPayload(t.ids) });
  // after the settlement: a seat fixed on the arrival screen (with the car's own secret)
  await rpc(db, 'mark_passenger', { p_code: t.code, p_admin_secret: vehicle_secret, p_passenger_id: t.ids['민호'],
                                    p_seat: '2열 오른쪽' });

  const changes = (await view(db, t, 2)).me.record_changes;
  assert.deepEqual(changes.map((c) => [c.field, c.old === null, c.new === null]), [
    ['boarded_at', false, true],
    ['alighted_at', false, true],
    ['boarded_at', true, false],
    ['seat', true, false],
  ]);
  assert.equal(changes[3].new, '2열 오른쪽');
  assert.ok(changes.every((c) => c.changed_at));
  const who = (await db.query('select changed_by from public.record_revisions order by seq')).rows.map((r) => r.changed_by);
  assert.deepEqual(who, ['trip_admin', 'trip_admin', 'trip_admin', 'car']);
  assert.deepEqual((await view(db, t, 1)).me.record_changes, [], '수진 does not see 민호\'s record');
  assert.equal((await view(db, t, 9)).me, null);

  // on the folder's settlement page too
  const folder = (await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'folder',
                                                       p_new_folder: '출근 카풀' })).folder.code;
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  const page = await rpc(db, 'get_settle_page', { p_code: req.code, p_device_key: device(2) });
  assert.equal(page.me.rides[0].changes.length, 4);
  assert.equal((await rpc(db, 'get_settle_page', { p_code: req.code, p_device_key: device(1) })).me.rides[0].changes.length, 0);

  // the driver deleting the ride record takes its history with it
  for (const p of req.payments) {
    await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: p.id });
  }
  await rpc(db, 'delete_ride_record', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['민호'] });
  assert.equal((await db.query('select count(*)::int as n from public.record_revisions')).rows[0].n, 0);
});

// ---------------------------------------------------------------- F. settlement fields from the car

test('the server keeps the rule, the driver flag, capped and provisional rows and the driver\'s remainder', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  const payload = fullPayload(t.ids, { rule: 'distance', distance_source: 'straight_line' });
  delete payload.include_driver;
  payload.driver_included = false;
  payload.shares[0].capped = true;
  payload.shares[1].capped = 'yes';
  payload.shares[1].provisional = true;
  payload.shares.push({ passenger_id: null, name: '끝전', role: 'remainder', amount: 10 });
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: payload });

  const stored = (await admin(db, t)).settlement;
  assert.equal(stored.rule, 'distance');
  assert.equal(stored.include_driver, false);
  assert.equal(stored.driver_included, undefined);
  assert.equal(stored.provisional, true, 'distances not from the road router are provisional');
  assert.equal(stored.driver_absorbed, 10);
  assert.equal(stored.shares.length, 3, 'the remainder row is not a person');
  assert.equal(stored.shares[0].capped, true);
  assert.equal(stored.shares[1].capped, undefined, 'only true or false is kept');

  const mine = await view(db, t, 1);
  assert.equal(mine.settlement.provisional, true);
  assert.equal(mine.settlement.driver_absorbed, 10);
  assert.equal(mine.settlement.include_driver, false);
  assert.equal(mine.settlement.shares[0].capped, true);
  assert.equal(mine.settlement.shares[1].capped, undefined, 'another rider\'s capped flag is theirs');
  assert.equal((await view(db, t, 2)).settlement.shares[1].provisional, true);

  // other ways the car may send the remainder, and a road distance
  for (const [extra, want] of [[{ driver_absorbed: 20 }, 20], [{ remainder: { amount: 30 } }, 30], [{ remainder: 40 }, 40]]) {
    const u = await twoRiders(db, vehicle_secret);
    await rpc(db, 'post_settlement', { p_code: u.code, p_admin_secret: u.admin_secret, p_payload: fullPayload(u.ids, extra) });
    const s = (await admin(db, u)).settlement;
    assert.equal(s.driver_absorbed, want);
    assert.equal(s.provisional, false, 'osrm distances are final');
    assert.equal(s.remainder, undefined);
  }
});

test('provisional amounts are not paid: no account, no 정산 완료, no folder request until the final ones come', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  const post = (extra) => rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret,
                                                       p_payload: fullPayload(t.ids, extra) });
  await post({ distance_source: 'straight_line' });
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });
  let v = await view(db, t, 1);
  assert.equal(v.settlement.provisional, true);
  assert.equal(v.bank, null, 'no account to copy while the amount may still change');
  await assert.rejects(rpc(db, 'mark_paid', { p_code: t.code, p_admin_secret: t.admin_secret,
                                              p_passenger_id: t.ids['수진'] }), /provisional_settlement/);
  await rpc(db, 'queue_trip_share', { p_code: t.code, p_admin_secret: t.admin_secret });
  const dv = await rpc(db, 'verify_device', { p_code: t.code, p_device_key: device(8), p_name: '지훈', p_phone4: '7890' });
  assert.equal(dv.driver_view.cards[0].bank, null, 'the driver\'s phone does not pass the account on either');
  assert.equal(dv.driver_view.cards[0].provisional, true);

  // a provisional row alone is enough, even with road distances
  const rowOnly = fullPayload(t.ids);
  rowOnly.shares[1].provisional = true;
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: rowOnly, p_reason: '재계산' });
  assert.equal((await view(db, t, 1)).bank, null);

  // the car posts again with road distances: nothing was paid, so the correction goes through and payment opens
  await post({ distance_source: 'osrm', total: 9010 });
  v = await view(db, t, 1);
  assert.equal(v.settlement.provisional, false);
  assert.equal(v.bank.account, '111-222-333');
  await rpc(db, 'mark_paid', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['수진'] });

  // folder: the sums cannot be fixed while a trip in them is provisional
  const u = await twoRiders(db, vehicle_secret);
  await rpc(db, 'post_settlement', { p_code: u.code, p_admin_secret: u.admin_secret,
                                     p_payload: fullPayload(u.ids, { distance_source: 'none' }) });
  const folder = (await rpc(db, 'choose_settlement', { p_code: u.code, p_admin_secret: u.admin_secret, p_mode: 'folder',
                                                       p_new_folder: '출근 카풀' })).folder.code;
  await assert.rejects(rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret }), /provisional_settlement/);
  await rpc(db, 'post_settlement', { p_code: u.code, p_admin_secret: u.admin_secret, p_payload: fullPayload(u.ids) });
  assert.ok((await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested.code);
});

test('older payloads still work: segment_split, no rule, no flags', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await twoRiders(db, vehicle_secret);
  const old = { rule: 'segment_split', include_driver: true, total: 6000,
                shares: [{ passenger_id: t.ids['수진'], name: '수진', amount: 3000, from: '지훈 집', to: '망원역 1번 출구' },
                         { passenger_id: t.ids['민호'], name: '민호', amount: 3000 }] };
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: old });
  const s = (await view(db, t, 1)).settlement;
  assert.equal(s.rule, 'distance');
  assert.equal(s.provisional, undefined);
  assert.equal(s.driver_absorbed, undefined);
  assert.equal(s.shares[0].from, '지훈 집');

  const u = await twoRiders(db, vehicle_secret);
  await rpc(db, 'post_settlement', { p_code: u.code, p_admin_secret: u.admin_secret,
                                     p_payload: { total: 0, shares: [] } });
  assert.equal((await admin(db, u)).settlement.rule, 'shapley', 'the trip\'s rule when none is sent');
  // a payload stored before this change (rule as the old car sent it) is read the same way
  await db.query(`update public.settlements set payload = payload || '{"rule":"segment_split"}' where trip_id =
                  (select id from public.trips where code = $1)`, [u.code]);
  assert.equal((await view(db, u, 1)).settlement.rule, 'distance');
});
