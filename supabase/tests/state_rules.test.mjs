// Trip and batch state rules: what is final, who may change what, and what each viewer gets to see.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';

const migration = readFileSync(new URL('../migrations/20261022000000_init.sql', import.meta.url), 'utf8');

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
const place = (label) => ({ label, lat: 37.5612, lng: 126.9234 });

async function driverWithCar(db) {
  const { driver_secret } = await rpc(db, 'create_driver', { p_display_name: '지훈' });
  const car = await rpc(db, 'create_vehicle_link', {});
  await rpc(db, 'claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  await rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: true, p_bank_name: '페어은행',
                                      p_bank_account: '111-222-333', p_account_holder: '지훈' });
  return { driver_secret, vehicle_secret: car.vehicle_secret };
}

const admin = (db, trip) => rpc(db, 'get_trip_admin', { p_code: trip.code, p_admin_secret: trip.admin_secret });

// riders = [[deviceNo, name], ...] join and ride; shares = { name: amount } for the ones the car bills
async function ride(db, secrets, riders, shares, title = '출근') {
  const trip = await rpc(db, 'create_trip', { p_title: title, ...secrets });
  for (const [n, name] of riders) {
    await rpc(db, 'join_trip', { p_code: trip.code, p_device_key: device(n), p_name: name,
                                 p_phone4: `${n}${n}${n}${n}`.slice(0, 4), p_pickup: place(`${name} 정류장`),
                                 p_dropoff: place('회사') });
  }
  await rpc(db, 'set_trip_status', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_status: 'in_progress' });
  const ps = (await admin(db, trip)).passengers;
  for (const p of ps) {
    await rpc(db, 'mark_passenger', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_passenger_id: p.id,
                                      p_boarded: true, p_alighted: true });
  }
  const ids = Object.fromEntries(ps.map((p) => [p.name, p.id]));
  const list = Object.entries(shares).map(([name, amount]) => ({ passenger_id: ids[name], name, amount, km: 10,
                                                                 from: `${name} 정류장`, to: '회사' }));
  list.push({ passenger_id: null, name: '지훈', role: 'driver', amount: 3000 });
  await rpc(db, 'post_settlement', { p_code: trip.code, p_admin_secret: trip.admin_secret,
                                     p_payload: { total: list.reduce((s, x) => s + x.amount, 0), shares: list } });
  return { ...trip, ids };
}

test('settled and cancelled trips are final, and a started trip does not go back to preparing', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const going = await rpc(db, 'create_trip', { p_title: '퇴근', p_vehicle_secret: vehicle_secret });
  const set = (t, s) => rpc(db, 'set_trip_status', { p_code: t.code, p_admin_secret: t.admin_secret, p_status: s });
  await set(going, 'in_progress');
  await assert.rejects(set(going, 'preparing'), /bad_status/);

  const done = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진']], { 수진: 2000 });
  assert.equal((await admin(db, done)).status, 'settled');
  await assert.rejects(set(done, 'cancelled'), /trip_closed/, 'a settled trip cannot be cancelled afterwards');
  await assert.rejects(set(done, 'in_progress'), /trip_closed/);
  await assert.rejects(rpc(db, 'update_progress', { p_code: done.code, p_admin_secret: done.admin_secret,
                                                    p_progress: { stops: [] } }), /trip_closed/);

  await set(going, 'cancelled');
  await assert.rejects(set(going, 'in_progress'), /trip_closed/);
  await assert.rejects(rpc(db, 'post_settlement', { p_code: going.code, p_admin_secret: going.admin_secret,
                                                    p_payload: { total: 0, shares: [] } }), /trip_closed/);
});

test('what someone already paid cannot be switched to a folder or cancelled away', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진'], [2, '민호']], { 수진: 2430, 민호: 1780 });
  await rpc(db, 'choose_settlement', { p_code: a.code, p_admin_secret: a.admin_secret, p_mode: 'direct' });
  await rpc(db, 'mark_paid', { p_code: a.code, p_admin_secret: a.admin_secret, p_passenger_id: a.ids['수진'] });
  await assert.rejects(rpc(db, 'choose_settlement', { p_code: a.code, p_admin_secret: a.admin_secret, p_mode: 'folder',
                                                      p_new_folder: '출근 카풀' }), /settle_started/);
  assert.notEqual((await admin(db, a)).passengers.find((p) => p.name === '수진').paid_at, null, 'the tick is still there');

  const b = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진'], [2, '민호']], { 수진: 2430, 민호: 1780 });
  const folder = (await rpc(db, 'choose_settlement', { p_code: b.code, p_admin_secret: b.admin_secret, p_mode: 'folder',
                                                       p_new_folder: '출근 카풀' })).folder.code;
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  // a requested batch is fixed: no boarding corrections either
  await assert.rejects(rpc(db, 'mark_passenger', { p_code: b.code, p_admin_secret: b.admin_secret,
                                                   p_passenger_id: b.ids['민호'], p_alighted: false }), /batch_locked/);
  const sujin = req.payments.find((p) => p.name === '수진');
  await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: sujin.id });
  await assert.rejects(rpc(db, 'cancel_batch_request', { p_folder_code: folder, p_secret: vehicle_secret }),
                       /settle_started/);
  await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: sujin.id,
                                     p_paid: false });
  const back = await rpc(db, 'cancel_batch_request', { p_folder_code: folder, p_secret: vehicle_secret });
  assert.equal(back.requested, null, 'once the tick is undone the request can be cancelled');
});

test('the car cannot bill passengers of another trip', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진']], { 수진: 2000 });
  const b = await rpc(db, 'create_trip', { p_title: '퇴근', p_vehicle_secret: vehicle_secret });
  await assert.rejects(rpc(db, 'post_settlement', {
    p_code: b.code, p_admin_secret: b.admin_secret,
    p_payload: { total: 2000, shares: [{ passenger_id: a.ids['수진'], name: '수진', amount: 2000 }] } }), /bad_share/);
});

test('only riders see the settlement, others\' stops are left out, and the account needs a share', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  // 지수 joined but did not come, so the car bills only 수진 and 민호
  const t = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진'], [2, '민호'], [3, '지수']],
                       { 수진: 2430, 민호: 1780 });
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });

  const stranger = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(9) });
  assert.equal(stranger.settlement, null, 'the code alone does not show who paid what');
  assert.equal(stranger.bank, null);
  assert.equal(stranger.favourite, false);
  assert.equal(stranger.carpool_code, null);

  const sujin = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(1) });
  const mine = sujin.settlement.shares.find((s) => s.is_me);
  const other = sujin.settlement.shares.find((s) => s.name === '민호');
  assert.equal(mine.from, '수진 정류장');
  assert.equal(mine.km, 10);
  assert.equal(other.amount, 1780, 'everyone\'s amount is shown');
  assert.equal(other.from, undefined, 'but not where others got on');
  assert.equal(other.km, undefined);
  assert.equal(sujin.bank.account, '111-222-333');

  const jisu = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(3) });
  assert.equal(jisu.bank, null, 'no account for someone the car did not bill');
});

test('a car manages only the trips and folders of the driver it is linked to now', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진']], { 수진: 2000 });
  await rpc(db, 'choose_settlement', { p_code: a.code, p_admin_secret: a.admin_secret, p_mode: 'folder',
                                       p_new_folder: '출근 카풀' });
  assert.equal((await rpc(db, 'get_trip_admin', { p_code: a.code, p_admin_secret: vehicle_secret })).code, a.code);
  assert.equal((await rpc(db, 'list_folders', { p_secret: vehicle_secret })).length, 1);

  // the car is handed over and linked to another driver
  const other = await rpc(db, 'create_driver', { p_display_name: '은지' });
  const link = await rpc(db, 'create_vehicle_link', { p_vehicle_secret: vehicle_secret });
  await rpc(db, 'claim_vehicle_link', { p_link_code: link.link_code, p_driver_secret: other.driver_secret });
  await assert.rejects(rpc(db, 'get_trip_admin', { p_code: a.code, p_admin_secret: vehicle_secret }), /not_allowed/);
  assert.equal((await rpc(db, 'list_folders', { p_secret: vehicle_secret })).length, 0);
  assert.equal((await rpc(db, 'list_folders', { p_secret: driver_secret })).length, 1, 'the first driver keeps them');
});

test('trips and folders made on a car before linking follow the driver who links it', async () => {
  const db = await freshDb();
  const car = await rpc(db, 'create_vehicle_link', {});
  const t = await ride(db, { p_vehicle_secret: car.vehicle_secret }, [[1, '수진']], { 수진: 2000 });
  await rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: true, p_bank_name: '페어은행',
                                      p_bank_account: '444-555', p_account_holder: '지훈' });
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });
  assert.equal((await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(1) })).bank.account, '444-555',
               'the account entered on the car reaches a trip the car made');

  const f = await ride(db, { p_vehicle_secret: car.vehicle_secret }, [[1, '수진']], { 수진: 2000 });
  await rpc(db, 'choose_settlement', { p_code: f.code, p_admin_secret: f.admin_secret, p_mode: 'folder',
                                       p_new_folder: '출근 카풀' });
  const { driver_secret } = await rpc(db, 'create_driver', { p_display_name: '지훈' });
  const link = await rpc(db, 'create_vehicle_link', { p_vehicle_secret: car.vehicle_secret });
  await rpc(db, 'claim_vehicle_link', { p_link_code: link.link_code, p_driver_secret: driver_secret });
  assert.equal((await rpc(db, 'list_folders', { p_secret: driver_secret })).length, 1);
  const trips = (await rpc(db, 'get_driver', { p_driver_secret: driver_secret })).trips.map((x) => x.code);
  assert.ok(trips.includes(t.code) && trips.includes(f.code));
  assert.equal((await rpc(db, 'get_trip_admin', { p_code: t.code, p_admin_secret: car.vehicle_secret })).code, t.code);
});

test('a misrecognised boarding is undone, and a dropped-off ride is no longer edited from the phone', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const t = await rpc(db, 'create_trip', { p_title: '출근', p_vehicle_secret: vehicle_secret });
  const join = (pickup, dropoff) => rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(1), p_name: '수진',
                                                           p_pickup: place(pickup), p_dropoff: place(dropoff) });
  await join('집 앞', '회사');
  await rpc(db, 'set_trip_status', { p_code: t.code, p_admin_secret: t.admin_secret, p_status: 'in_progress' });
  const id = (await admin(db, t)).passengers[0].id;
  const mark = (args) => rpc(db, 'mark_passenger', { p_code: t.code, p_admin_secret: t.admin_secret,
                                                     p_passenger_id: id, ...args });
  const row = async () => (await admin(db, t)).passengers[0];

  await mark({ p_boarded: true });
  await join('다른 곳', '학교');
  assert.equal((await row()).pickup.label, '집 앞', 'the pickup is part of the record once on board');
  assert.equal((await row()).dropoff.label, '학교', 'the drop-off can still change');

  await mark({ p_alighted: true });
  await mark({ p_alighted: false });
  assert.equal((await row()).alighted_at, null, '잘못 인식 takes the drop-off back');
  assert.notEqual((await row()).boarded_at, null);
  await mark({ p_alighted: true });
  await mark({ p_boarded: false });
  assert.equal((await row()).boarded_at, null);
  assert.equal((await row()).alighted_at, null, 'undoing boarding undoes the drop-off too');

  await mark({ p_boarded: true, p_alighted: true });
  await assert.rejects(join('집 앞', '회사'), /record_locked/);
});

test('deleting a ride record also takes the name off the folder line and the kept stops', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const t = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진'], [2, '민호']], { 수진: 2430, 민호: 1780 });
  const folder = (await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'folder',
                                                       p_new_folder: '출근 카풀' })).folder.code;
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  for (const p of req.payments) {
    await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: p.id });
  }
  await rpc(db, 'delete_ride_record', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['수진'] });
  const names = (await rpc(db, 'get_folder_admin', { p_folder_code: folder, p_secret: vehicle_secret }))
    .last_done.payments.map((p) => p.name).sort();
  assert.deepEqual(names, ['기록을 지운 동승자', '민호']);
  const left = await db.query('select exact_stops, estimate from public.trip_passengers where id = $1', [t.ids['수진']]);
  assert.equal(left.rows[0].exact_stops, null);
});

test('removing a favourite clears its kept places; a folder is removed only when nothing is being settled', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const t = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진']], { 수진: 2000 });
  const { carpool_code } = await rpc(db, 'save_carpool', { p_code: t.code, p_admin_secret: t.admin_secret,
                                                          p_driver_secret: driver_secret });
  await assert.rejects(rpc(db, 'archive_carpool', { p_carpool_code: carpool_code, p_secret: 'someone-else' }),
                       /not_allowed/);
  await rpc(db, 'archive_carpool', { p_carpool_code: carpool_code, p_secret: vehicle_secret });
  await assert.rejects(rpc(db, 'get_carpool', { p_carpool_code: carpool_code }), /carpool_not_found/);
  const kept = await db.query('select count(*)::int as n from public.carpool_members where pickup is not null or dropoff is not null');
  assert.equal(kept.rows[0].n, 0);

  const folder = (await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'folder',
                                                       p_new_folder: '출근 카풀' })).folder.code;
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  await assert.rejects(rpc(db, 'archive_folder', { p_folder_code: folder, p_secret: vehicle_secret }),
                       /batch_in_progress/);
  await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: req.payments[0].id });
  await rpc(db, 'archive_folder', { p_folder_code: folder, p_secret: driver_secret });
  assert.equal((await rpc(db, 'list_folders', { p_secret: vehicle_secret })).length, 0);
  assert.equal((await admin(db, t)).status, 'settled', 'settled records stay');
});

test('trips left running are closed after 7 days and stop sharing the car position after a day', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const old = await rpc(db, 'create_trip', { p_title: '출근', p_vehicle_secret: vehicle_secret });
  const day = await rpc(db, 'create_trip', { p_title: '퇴근', p_vehicle_secret: vehicle_secret });
  for (const t of [old, day]) {
    await rpc(db, 'set_trip_status', { p_code: t.code, p_admin_secret: t.admin_secret, p_status: 'in_progress' });
    await rpc(db, 'update_progress', { p_code: t.code, p_admin_secret: t.admin_secret,
                                       p_progress: { car: { lat: 37.5, lng: 127 }, stops: [] } });
  }
  await db.query(`update public.trips set started_at = now() - interval '8 days' where code = $1`, [old.code]);
  await db.query(`update public.trips set started_at = now() - interval '25 hours' where code = $1`, [day.code]);
  await rpc(db, 'purge_old_locations');
  assert.equal((await admin(db, old)).status, 'cancelled');
  const left = await db.query('select status, progress from public.trips where code = $1', [day.code]);
  assert.equal(left.rows[0].status, 'in_progress');
  assert.equal(left.rows[0].progress, null);
});

test('a page takes at most 100 wrong tries a day, even spread over hours', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const t = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진']], { 수진: 2000 });
  const id = (await db.query('select id from public.trips where code = $1', [t.code])).rows[0].id;
  await db.query(`insert into public.verify_attempts (scope, device_key_hash, failed_at)
                  select 'trip:' || $1, 'd' || g, now() - (g % 20 + 2) * interval '1 hour' from generate_series(1, 100) g`,
                 [id]);
  await assert.rejects(rpc(db, 'verify_device', { p_code: t.code, p_device_key: device(7), p_name: '수진',
                                                  p_phone4: '1111' }), /too_many_attempts/);
});

test('the favourite link adds up the month for the same rider on a new phone', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, { p_vehicle_secret: vehicle_secret }, [[1, '수진']], { 수진: 2000 });
  const { carpool_code } = await rpc(db, 'save_carpool', { p_code: a.code, p_admin_secret: a.admin_secret,
                                                          p_driver_secret: driver_secret });
  const next = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_vehicle_secret: vehicle_secret });
  await rpc(db, 'verify_device', { p_code: next.code, p_device_key: device(7), p_name: '수진', p_phone4: '1111' });
  const id = (await admin(db, next)).passengers[0].id;
  await rpc(db, 'post_settlement', { p_code: next.code, p_admin_secret: next.admin_secret,
                                     p_payload: { total: 2500, shares: [{ passenger_id: id, name: '수진', amount: 2500 }] } });
  const cp = await rpc(db, 'get_carpool', { p_carpool_code: carpool_code, p_device_key: device(7) });
  assert.equal(cp.month.trips, 2);
  assert.equal(Number(cp.month.my_amount), 4500);
});
