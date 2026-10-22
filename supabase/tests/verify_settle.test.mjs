// Settlement page checks: expiry, folder batches, corrections from the car, and robustness.
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

// a driver with a linked car and a bank account (L1-9)
async function driverWithCar(db) {
  const { driver_secret } = await rpc(db, 'create_driver', { p_display_name: '지훈' });
  const car = await rpc(db, 'create_vehicle_link', {});
  await rpc(db, 'claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  await rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: true, p_bank_name: '페어은행',
                                      p_bank_account: '111-222-333', p_account_holder: '지훈' });
  return { driver_secret, vehicle_secret: car.vehicle_secret };
}

// one ride: riders = [[deviceNo, name, amount], ...]; the car posts the settlement
async function ride(db, driver_secret, riders, title = '출근', driverAmount = 3000) {
  const trip = await rpc(db, 'create_trip', { p_title: title, p_driver_secret: driver_secret });
  for (const [n, name] of riders) {
    await rpc(db, 'join_trip', { p_code: trip.code, p_device_key: device(n), p_name: name, p_phone4: `${n}${n}${n}${n}`.slice(0, 4),
                                 p_pickup: place(`${name} 정류장`), p_dropoff: place('회사') });
  }
  await rpc(db, 'set_trip_status', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_status: 'in_progress' });
  const ps = (await rpc(db, 'get_trip_admin', { p_code: trip.code, p_admin_secret: trip.admin_secret })).passengers;
  for (const p of ps) {
    await rpc(db, 'mark_passenger', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_passenger_id: p.id,
                                      p_boarded: true, p_alighted: true });
  }
  const id = (name) => ps.find((p) => p.name === name).id;
  const shares = riders.map(([, name, amount]) => ({ passenger_id: id(name), name, amount, km: 10 }));
  shares.push({ passenger_id: null, name: '지훈', role: 'driver', amount: driverAmount });
  const total = shares.reduce((s, x) => s + x.amount, 0);
  await rpc(db, 'post_settlement', { p_code: trip.code, p_admin_secret: trip.admin_secret,
                                     p_payload: { total, shares, fuel: { distance_km: 46 } } });
  return { ...trip, ids: Object.fromEntries(ps.map((p) => [p.name, p.id])), total };
}

const stack = (db, trip, folder) => rpc(db, 'choose_settlement', {
  p_code: trip.code, p_admin_secret: trip.admin_secret, p_mode: 'folder',
  ...(folder ? { p_folder_code: folder } : { p_new_folder: '출근 카풀' }) });

const ago = (db, trip, col, interval) =>
  db.query(`update public.trips set ${col} = now() - interval '${interval}' where code = $1`, [trip.code]);

// ---------------------------------------------------------------- 1. expiry

test('바로 정산 page: stays readable after the stops expire, account only while unpaid', async () => {
  const db = await freshDb();
  const { driver_secret } = await driverWithCar(db);
  const t = await ride(db, driver_secret, [[1, '수진', 2430], [2, '민호', 1780]]);
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });

  let v = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(1) });
  assert.equal(v.bank.account, '111-222-333');
  assert.equal(v.settlement.shares.find((s) => s.is_me).amount, 2430);

  await ago(db, t, 'ended_at', '25 hours');
  const purged = await rpc(db, 'purge_old_locations');
  assert.ok(purged.cleared_locations >= 2);
  v = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(1) });
  assert.equal(v.me.pickup, null, 'stops are gone after a day');
  assert.equal(v.me.dropoff, null);
  assert.equal(v.settlement.shares.find((s) => s.is_me).amount, 2430, 'the settlement itself does not expire');
  assert.equal(v.bank.account, '111-222-333', 'an unpaid rider still sees where to pay');

  await rpc(db, 'mark_paid', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['수진'] });
  v = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(1) });
  assert.equal(v.bank, null);
  assert.equal((await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(2) })).bank.account, '111-222-333',
               'paying is per passenger');
});

test('unstarted trips expire after 7 days; stops are kept until a day after the end', async () => {
  const db = await freshDb();
  const { driver_secret } = await driverWithCar(db);
  const old = await rpc(db, 'create_trip', { p_title: '지난주 준비', p_driver_secret: driver_secret });
  await rpc(db, 'join_trip', { p_code: old.code, p_device_key: device(1), p_name: '수진', p_pickup: place('정류장') });
  await ago(db, old, 'created_at', '8 days');
  const fresh = await ride(db, driver_secret, [[2, '민호', 1500]]);
  await ago(db, fresh, 'ended_at', '23 hours');

  const r = await rpc(db, 'purge_old_locations');
  assert.equal(r.expired_trips, 1);
  assert.equal((await rpc(db, 'get_trip', { p_code: old.code })).status, 'cancelled');
  await assert.rejects(rpc(db, 'join_trip', { p_code: old.code, p_device_key: device(3), p_name: 'x' }), /trip_closed/);
  assert.ok((await rpc(db, 'get_trip', { p_code: fresh.code, p_device_key: device(2) })).me.pickup,
            'a trip that ended 23 hours ago keeps its stops');
});

test('invite window after departure closes by itself after 10 minutes', async () => {
  const db = await freshDb();
  const { driver_secret } = await driverWithCar(db);
  const t = await rpc(db, 'create_trip', { p_title: 'MT', p_driver_secret: driver_secret });
  await rpc(db, 'set_trip_status', { p_code: t.code, p_admin_secret: t.admin_secret, p_status: 'in_progress' });
  await assert.rejects(rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(1), p_name: '태호' }), /join_closed/);
  await rpc(db, 'set_join_open', { p_code: t.code, p_admin_secret: t.admin_secret });
  await rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(1), p_name: '태호' });
  await db.query(`update public.trips set join_open_until = now() - interval '1 second' where code = $1`, [t.code]);
  await assert.rejects(rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(2), p_name: '늦은 사람' }), /join_closed/);
  await rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(1), p_name: '태호' }); // members may still edit
});

// ---------------------------------------------------------------- 2. folder batches

test('folder: no page per trip, one page per batch, who rode along is shown but the account is each one\'s own', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  // different riders each day (계산 예시 3)
  const a = await ride(db, driver_secret, [[1, '수진', 2430]]);
  const f0 = await stack(db, a);
  const folder = f0.folder.code;
  const b = await ride(db, driver_secret, [[1, '수진', 1900], [2, '민호', 1780]]);
  await stack(db, b, folder);
  const c = await ride(db, driver_secret, [[1, '수진', 1670], [2, '민호', 1300], [3, '지은', 1430]]);
  await stack(db, c, folder);

  for (const [t, n] of [[a, 1], [b, 2], [c, 3]]) {
    const v = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(n) });
    assert.equal(v.settlement, null, 'folder trips have no settlement page of their own');
    assert.equal(v.bank, null);
    assert.equal(v.folder.page, null, 'no page until 합쳐서 정산하기');
  }
  const m5 = await rpc(db, 'get_trip', { p_code: c.code, p_device_key: device(1) });
  assert.equal(m5.folder.my_total, 2430 + 1900 + 1670);
  assert.equal(m5.folder.rides.length, 3);

  let list = await rpc(db, 'list_folders', { p_secret: vehicle_secret });
  assert.equal(list[0].status, 'open');
  assert.equal(list[0].trips, 3);
  assert.equal(Number(list[0].amount), 2430 + 1900 + 1780 + 1670 + 1300 + 1430);

  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  const pay = Object.fromEntries(req.payments.map((p) => [p.name, p]));
  assert.equal(Number(pay['수진'].amount), 6000);
  assert.equal(pay['수진'].trips, 3);
  assert.equal(Number(pay['민호'].amount), 3080);
  assert.equal(Number(pay['지은'].amount), 1430);
  assert.equal(req.payments.reduce((s, p) => s + Number(p.amount), 0), Number(req.receivable), 'payments add up to the receivable');

  // every trip page now points to the same single page
  for (const [t, n] of [[a, 1], [b, 2], [c, 3]]) {
    assert.equal((await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(n) })).folder.page, req.code);
  }
  const page = (n) => rpc(db, 'get_settle_page', { p_code: req.code.toLowerCase(), p_device_key: device(n) });
  const mine = await page(2);
  assert.equal(Number(mine.me.amount), 3080);
  assert.equal(mine.me.rides.length, 2);
  assert.equal(mine.bank.account, '111-222-333');
  // M6: in each of my rides, who rode along and each one's share, but not where others got on and off
  const three = mine.me.rides.find((r) => r.people.length === 3);
  assert.deepEqual(three.people.map((x) => [x.name, Number(x.amount), x.is_me]),
                   [['수진', 1670, false], ['민호', 1300, true], ['지은', 1430, false]]);
  assert.ok(three.people.every((x) => !('from' in x) && !('to' in x)));
  // "3회 중 2회", "3회 총비용", "수진, 지은 7,430원", "운전자 몫"
  assert.equal(mine.batch.trips, 3);
  assert.equal(Number(mine.batch.total), 19510);
  assert.equal(Number(mine.batch.driver_amount), 9000);
  assert.deepEqual(mine.batch.others, ['수진', '지은']);
  assert.equal(Number(mine.batch.others_amount), 6000 + 1430);
  const outsider = await rpc(db, 'get_settle_page', { p_code: req.code });
  assert.equal(outsider.me, null);
  assert.equal(outsider.batch, null, 'a phone that did not ride sees no names');
  assert.equal(outsider.bank, null, 'the account is not shown to a phone that did not ride');

  // a trip after the request goes into a new batch; the requested sums do not move
  const d = await ride(db, driver_secret, [[1, '수진', 2430]]);
  await stack(db, d, folder);
  assert.equal(Number((await page(1)).me.amount), 6000);
  list = await rpc(db, 'list_folders', { p_secret: vehicle_secret });
  assert.equal(list[0].status, 'requested');

  await assert.rejects(rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret }), /batch_in_progress/);
  await assert.rejects(rpc(db, 'choose_settlement', { p_code: b.code, p_admin_secret: b.admin_secret, p_mode: 'direct' }),
                       /batch_locked/, 'a requested trip cannot leave the batch');
});

test('folder: cancel, re-request, paid one by one, done', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, driver_secret, [[1, '수진', 2430], [2, '민호', 1780]]);
  const folder = (await stack(db, a)).folder.code;
  const first = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  const b = await ride(db, driver_secret, [[1, '수진', 1900]]);
  await stack(db, b, folder);

  const back = await rpc(db, 'cancel_batch_request', { p_folder_code: folder, p_secret: vehicle_secret });
  assert.equal(back.requested, null);
  assert.equal(back.open.trips.length, 2, 'trips stacked meanwhile join the batch again');
  await assert.rejects(rpc(db, 'get_settle_page', { p_code: first.code, p_device_key: device(1) }), /page_not_found/);
  assert.equal((await rpc(db, 'get_trip', { p_code: a.code, p_device_key: device(1) })).folder.page, null);

  const again = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  assert.notEqual(again.code, first.code);
  const pay = Object.fromEntries(again.payments.map((p) => [p.name, p]));
  assert.equal(Number(pay['수진'].amount), 4330);

  let f = await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: pay['수진'].id });
  assert.ok(f.requested, 'still in progress while 민호 has not paid');
  assert.equal((await rpc(db, 'get_settle_page', { p_code: again.code, p_device_key: device(1) })).bank, null);
  assert.equal((await rpc(db, 'get_settle_page', { p_code: again.code, p_device_key: device(2) })).bank.account, '111-222-333');
  // ticking twice changes nothing
  await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: pay['수진'].id });

  f = await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret, p_payment_id: pay['민호'].id });
  assert.equal(f.requested, null);
  assert.ok(f.last_done.done_at);
  const done = await rpc(db, 'get_settle_page', { p_code: again.code, p_device_key: device(2) });
  assert.equal(done.status, 'done');
  assert.equal(done.me.paid, true);
  assert.equal(done.bank, null);
  const list = await rpc(db, 'list_folders', { p_secret: vehicle_secret });
  assert.equal(list[0].status, 'open');
  assert.equal(list[0].trips, 0, 'the folder starts again from 0');
  await assert.rejects(rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret }), /nothing_to_settle/);
});

// ---------------------------------------------------------------- 3. corrections from the car

test('car corrections: direct trip shows the new amount, folder sums follow, requested batch is locked', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const t = await ride(db, driver_secret, [[1, '수진', 2430], [2, '민호', 1780]]);
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });
  const fixed = { total: 7000, shares: [
    { passenger_id: t.ids['수진'], name: '수진', amount: 2600 },
    { passenger_id: t.ids['민호'], name: '민호', amount: 1400 },
    { passenger_id: null, name: '지훈', role: 'driver', amount: 3000 }] };
  const admin = await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: vehicle_secret, p_payload: fixed,
                                                   p_reason: '좌석 정정' });
  assert.equal(admin.revisions.length, 1);
  assert.equal(admin.revisions[0].reason, '좌석 정정');
  const v = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(1) });
  assert.equal(v.settlement.shares.find((s) => s.is_me).amount, 2600);
  assert.equal(v.corrections, 1);
  assert.equal(v.settle_mode, 'direct', 'a correction keeps the chosen way to settle');

  // folder: an open batch follows the correction
  const a = await ride(db, driver_secret, [[1, '수진', 2430]]);
  const folder = (await stack(db, a)).folder.code;
  await rpc(db, 'post_settlement', { p_code: a.code, p_admin_secret: a.admin_secret, p_reason: '하차 위치 정정',
    p_payload: { total: 5000, shares: [{ passenger_id: a.ids['수진'], name: '수진', amount: 2000 },
                                       { passenger_id: null, name: '지훈', role: 'driver', amount: 3000 }] } });
  assert.equal(Number((await rpc(db, 'list_folders', { p_secret: vehicle_secret }))[0].amount), 2000);
  assert.equal((await rpc(db, 'get_trip', { p_code: a.code, p_device_key: device(1) })).folder.my_total, 2000);

  await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret });
  await assert.rejects(rpc(db, 'post_settlement', { p_code: a.code, p_admin_secret: a.admin_secret,
    p_payload: { total: 1, shares: [] } }), /batch_locked/, 'passengers were told a fixed sum');
});

test('once anyone in a trip has settled, nothing in it can be corrected until every tick is undone', async () => {
  const db = await freshDb();
  const { driver_secret } = await driverWithCar(db);
  const t = await ride(db, driver_secret, [[1, '수진', 2430], [2, '민호', 1780]]);
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });
  await rpc(db, 'mark_paid', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['수진'] });
  const payload = (sujin, minho) => ({ total: sujin + minho + 3000, shares: [
    { passenger_id: t.ids['수진'], name: '수진', amount: sujin },
    { passenger_id: t.ids['민호'], name: '민호', amount: minho },
    { passenger_id: null, name: '지훈', role: 'driver', amount: 3000 }] });
  const correct = (sujin, minho) => rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret,
                                                                p_reason: '하차 정정', p_payload: payload(sujin, minho) });
  // Shapley shares depend on one another: even a correction that leaves 수진's part as it is is refused
  await assert.rejects(correct(2430, 1500), /settle_started/, 'another rider\'s part cannot change either');
  await assert.rejects(correct(3000, 1780), /settle_started/);
  await assert.rejects(rpc(db, 'mark_passenger', { p_code: t.code, p_admin_secret: t.admin_secret,
                                                   p_passenger_id: t.ids['민호'], p_seat: '2열 오른쪽' }), /settle_started/,
                       'boarding and seat corrections change the amounts too');
  const v = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(2) });
  assert.equal(v.settlement.shares.find((s) => s.is_me).amount, 1780);
  assert.equal(v.corrections, 0);

  // with every 정산 완료 tick undone the trip can be corrected again
  await rpc(db, 'mark_paid', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['수진'], p_paid: false });
  const fixed = await correct(2500, 1500);
  assert.equal(fixed.settlement.shares.find((s) => s.name === '민호').amount, 1500);
  assert.equal(fixed.revisions.length, 1);
  await rpc(db, 'mark_passenger', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['민호'],
                                    p_seat: '2열 오른쪽' });
});

test('a deleted ride record keeps the trip settled and stays without a name', async () => {
  const db = await freshDb();
  const { driver_secret } = await driverWithCar(db);
  const t = await ride(db, driver_secret, [[1, '수진', 2430]]);
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });
  await rpc(db, 'mark_paid', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['수진'] });
  await rpc(db, 'delete_ride_record', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: t.ids['수진'] });
  // its stops are gone, so the tick cannot be undone and the trip cannot be recalculated
  await assert.rejects(rpc(db, 'mark_paid', { p_code: t.code, p_admin_secret: t.admin_secret,
                                              p_passenger_id: t.ids['수진'], p_paid: false }), /record_deleted/);
  await assert.rejects(rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_reason: '정정',
    p_payload: { total: 5430, shares: [{ passenger_id: t.ids['수진'], name: '수진', amount: 2430, from: '집', to: '회사', km: 10 },
                                       { passenger_id: null, name: '지훈', role: 'driver', amount: 3000 }] } }),
                       /settle_started/);
  const after = await rpc(db, 'get_trip_admin', { p_code: t.code, p_admin_secret: t.admin_secret });
  const share = after.settlement.shares[0];
  assert.equal(share.name, '기록을 지운 동승자');
  assert.equal(share.amount, 2430, 'the amount stays so the total adds up');
  assert.ok(!JSON.stringify(after).includes('수진'));
});

test('a paid tick can be taken back after the whole batch is done', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, driver_secret, [[1, '수진', 2430]]);
  const folder = (await stack(db, a)).folder.code;
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  const tick = (paid) => rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: vehicle_secret,
                                                      p_payment_id: req.payments[0].id, p_paid: paid });
  let f = await tick(true);
  assert.equal(f.requested, null);
  assert.equal(f.last_done.payments[0].paid, true);
  f = await tick(false);
  assert.ok(f.requested, 'back to 정산 중');
  const page = await rpc(db, 'get_settle_page', { p_code: req.code, p_device_key: device(1) });
  assert.equal(page.status, 'requested');
  assert.equal(page.bank.account, '111-222-333', 'the account shows again');
  f = await tick(true);
  assert.equal(f.requested, null);
  assert.equal((await rpc(db, 'get_settle_page', { p_code: req.code, p_device_key: device(1) })).status, 'done');
});

test('one batch is settled at a time, even if 합쳐서 정산하기 is pressed twice', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, driver_secret, [[1, '수진', 2430]]);
  const folder = (await stack(db, a)).folder.code;
  await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret });
  // a second 'requested' (or 'open') batch for the same folder is refused by the database itself
  await assert.rejects(db.query(`insert into public.folder_batches (folder_id, status)
                                 select id, 'requested' from public.folders where code = $1`, [folder]),
                       /folder_batches_one_requested/);
  await assert.rejects(db.query(`insert into public.folder_batches (folder_id, status)
                                 select id, 'open' from public.folders where code = $1`, [folder]),
                       /folder_batches_one_open/);
});

// ---------------------------------------------------------------- 4. robustness

test('settlement pages refuse guessing, other drivers and wrong secrets', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const other = await rpc(db, 'create_driver', { p_display_name: '남' });
  const a = await ride(db, driver_secret, [[1, '수진', 2430]]);
  const folder = (await stack(db, a)).folder.code;
  await assert.rejects(rpc(db, 'get_folder_admin', { p_folder_code: folder, p_secret: other.driver_secret }), /not_allowed/);
  await assert.rejects(rpc(db, 'request_batch', { p_folder_code: folder, p_secret: 'x'.repeat(48) }), /not_allowed/);
  await assert.rejects(rpc(db, 'post_settlement', { p_code: a.code, p_admin_secret: other.driver_secret,
                                                    p_payload: { shares: [] } }));
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  assert.match(req.code, /^[2-9A-HJ-NP-Z]{8}$/);
  await assert.rejects(rpc(db, 'get_settle_page', { p_code: 'ABCDEFGH' }), /page_not_found/);
  await assert.rejects(rpc(db, 'report_settle_issue', { p_code: req.code, p_device_key: device(9), p_message: 'x' }),
                       /not_allowed/);
  await rpc(db, 'report_settle_issue', { p_code: req.code, p_device_key: device(1), p_message: '금액이 이상해요' });
  const admin = await rpc(db, 'get_folder_admin', { p_folder_code: folder, p_secret: vehicle_secret });
  assert.equal(admin.requested.issues[0].name, '수진');
});

test('a new phone opens the settlement page with name and last 4 digits; guessing cannot lock the rider out', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, driver_secret, [[1, '수진', 2430]]);
  const folder = (await stack(db, a)).folder.code;
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  const ok = await rpc(db, 'verify_settle_page', { p_code: req.code, p_device_key: device(7), p_name: '수진', p_phone4: '1111' });
  assert.equal(Number(ok.me.amount), 2430, 'the new phone now sees 수진\'s part');
  assert.equal((await rpc(db, 'get_settle_page', { p_code: req.code, p_device_key: device(7) })).bank.account, '111-222-333');
  // someone guessing on one phone: that phone stops after 5 tries an hour, the rider's new phone still gets in
  const guess = (n, digits = '0000') =>
    rpc(db, 'verify_settle_page', { p_code: req.code, p_device_key: device(n), p_name: '수진', p_phone4: digits });
  for (let i = 0; i < 5; i++) assert.equal((await guess(8)).error, 'verify_failed');
  await assert.rejects(guess(8, '1111'), /too_many_attempts/);
  assert.equal(Number((await guess(9, '1111')).me.amount), 2430, 'the real rider on another new phone is not locked out');
  // guessing from many phones closes the page for an hour at most
  for (let n = 10; n < 35; n++) await guess(n); // 5 + 25 = 30 wrong tries on this page
  await assert.rejects(guess(50, '1111'), /too_many_attempts/);
  await db.query(`update public.verify_attempts set failed_at = failed_at - interval '61 minutes'`);
  assert.equal(Number((await guess(50, '1111')).me.amount), 2430, 'open again after the hour');
});

test('the same rider on a new phone in the middle of the month is still one person', async () => {
  const db = await freshDb();
  const { driver_secret, vehicle_secret } = await driverWithCar(db);
  const a = await ride(db, driver_secret, [[1, '수진', 2430]]);
  const folder = (await stack(db, a)).folder.code;
  // next day 수진 joins from a new phone (device 5) with the same name and digits
  const b = await rpc(db, 'create_trip', { p_title: '출근', p_driver_secret: driver_secret });
  await rpc(db, 'join_trip', { p_code: b.code, p_device_key: device(5), p_name: '수진', p_phone4: '1111' });
  const pid = (await rpc(db, 'get_trip_admin', { p_code: b.code, p_admin_secret: b.admin_secret })).passengers[0].id;
  await rpc(db, 'post_settlement', { p_code: b.code, p_admin_secret: b.admin_secret, p_payload: { total: 5430, shares: [
    { passenger_id: pid, name: '수진', amount: 2430 }, { passenger_id: null, name: '지훈', role: 'driver', amount: 3000 }] } });
  await stack(db, b, folder);
  assert.equal((await rpc(db, 'get_trip', { p_code: b.code, p_device_key: device(5) })).folder.my_total, 4860,
               'the new phone already sees the running sum of both rides');
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  assert.equal(req.payments.length, 1);
  assert.equal(Number(req.payments[0].amount), 4860);
  assert.equal(req.payments[0].trips, 2);
  for (const n of [1, 5]) {
    const page = await rpc(db, 'get_settle_page', { p_code: req.code, p_device_key: device(n) });
    assert.equal(page.me.rides.length, 2, 'both phones open the same part');
  }
  // a different person with the same name but other digits stays separate
  const c = await rpc(db, 'create_trip', { p_title: '출근', p_driver_secret: driver_secret });
  await rpc(db, 'join_trip', { p_code: c.code, p_device_key: device(6), p_name: '수진', p_phone4: '9999' });
  const pid2 = (await rpc(db, 'get_trip_admin', { p_code: c.code, p_admin_secret: c.admin_secret })).passengers[0].id;
  await rpc(db, 'post_settlement', { p_code: c.code, p_admin_secret: c.admin_secret, p_payload: { total: 4000, shares: [
    { passenger_id: pid2, name: '수진', amount: 1000 }, { passenger_id: null, name: '지훈', role: 'driver', amount: 3000 }] } });
  await stack(db, c, folder);
  const open = (await rpc(db, 'get_folder_admin', { p_folder_code: folder, p_secret: vehicle_secret })).open;
  assert.equal(open.people.length, 1);
  assert.equal(Number(open.people[0].amount), 1000);
});

test('a trip made again from a favourite opens on a new phone with name and digits', async () => {
  const db = await freshDb();
  const { driver_secret } = await driverWithCar(db);
  const a = await ride(db, driver_secret, [[1, '수진', 2430]]);
  const { carpool_code } = await rpc(db, 'save_carpool', { p_code: a.code, p_admin_secret: a.admin_secret,
                                                          p_driver_secret: driver_secret });
  const next = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_driver_secret: driver_secret });
  const v = await rpc(db, 'verify_device', { p_code: next.code, p_device_key: device(7), p_name: '수진', p_phone4: '1111' });
  assert.equal(v.me.name, '수진');
  // the favourite now follows the new phone
  const third = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_driver_secret: driver_secret });
  assert.equal((await rpc(db, 'get_trip', { p_code: third.code, p_device_key: device(7) })).me.name, '수진');
});
