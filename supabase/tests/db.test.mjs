// Runs the real migration in an in-process Postgres (PGlite) and walks through the trip flows.
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

// Same call shape the web client uses: named arguments, jsonb result.
async function rpc(db, fn, args = {}) {
  const names = Object.keys(args);
  const params = names.map((n) => {
    const v = args[n];
    return v !== null && typeof v === 'object' ? JSON.stringify(v) : v;
  });
  const sql = `select public.${fn}(${names.map((n, i) => `${n} => $${i + 1}`).join(', ')}) as r`;
  const res = await db.query(sql, params);
  return res.rows[0].r;
}

const place = (label) => ({ label, lat: 37.56, lng: 126.97 });
const device = (n) => `device-key-${n}-0123456789abcdef`;

test('each new trip gets its own code and admin secret', async () => {
  const db = await freshDb();
  const a = await rpc(db, 'create_trip', { p_title: 'MT 귀갓길', p_destination: place('지훈 집') });
  const b = await rpc(db, 'create_trip', { p_title: 'MT 귀갓길' });
  assert.match(a.code, /^[2-9A-HJ-NP-Z]{8}$/, "8 characters so invite codes cannot be guessed");
  assert.notEqual(a.code, b.code);
  assert.equal(a.admin_secret.length, 48);
});

test('passengers join by code and only see their own stops', async () => {
  const db = await freshDb();
  const { code, admin_secret } = await rpc(db, 'create_trip', { p_title: '출근', p_destination: place('회사') });
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(1), p_name: '민지', p_dropoff: place('민지 집') });
  const seo = await rpc(db, 'join_trip', { p_code: code.toLowerCase(), p_device_key: device(2), p_name: '서연',
                                          p_pickup: place('정류장'), p_dropoff: place('서연 집') });
  assert.equal(seo.passengers.length, 2);
  assert.equal(seo.me.dropoff.label, '서연 집');
  assert.ok(!JSON.stringify(seo.passengers).includes('민지 집'), 'other stops must not leak');

  // joining again from the same device updates instead of duplicating (the form always sends both stops)
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(2), p_name: '서연', p_pickup: place('정류장'),
                               p_dropoff: place('서연 회사') });
  const admin = await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: admin_secret });
  assert.equal(admin.passengers.length, 2);
  assert.equal(admin.passengers[1].dropoff.label, '서연 회사');
  assert.equal(admin.passengers[1].pickup.label, '정류장');
});

test('admin view needs the secret', async () => {
  const db = await freshDb();
  const { code } = await rpc(db, 'create_trip', { p_title: 't' });
  await assert.rejects(rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: 'wrong' }), /not_allowed/);
  await assert.rejects(rpc(db, 'get_trip', { p_code: 'ZZZZZZ' }), /trip_not_found/);
});

test('bank account is shown only while a rider\'s settlement is in progress, and only with consent', async () => {
  const db = await freshDb();
  // the account is entered only on the car (L1-9), even before a driver links it
  const car = await rpc(db, 'create_vehicle_link', {});
  await assert.rejects(rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: null,
                                                     p_bank_name: '페어은행', p_bank_account: '000-0000-0000',
                                                     p_account_holder: '지훈' }), /consent_required/);
  await rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: true, p_bank_name: '페어은행',
                                      p_bank_account: '000-0000-0000', p_account_holder: '지훈' });
  const stored = (await db.query('select bank_account from public.drivers where bank_account is not null')).rows[0].bank_account;
  assert.ok(!Buffer.from(stored).toString('latin1').includes('000-0000-0000'), 'the account is stored encrypted');
  // linking the car keeps the account with the driver; the driver web neither sees nor edits it
  const { driver_secret } = await rpc(db, 'create_driver', { p_display_name: '지훈' });
  await rpc(db, 'claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  assert.equal((await rpc(db, 'get_bank_account', { p_vehicle_secret: car.vehicle_secret })).account, '000-0000-0000');
  assert.equal((await rpc(db, 'get_driver', { p_driver_secret: driver_secret })).bank, undefined);
  await assert.rejects(rpc(db, 'set_bank_account', { p_vehicle_secret: driver_secret, p_consent: true,
                                                     p_bank_name: 'x', p_bank_account: '1', p_account_holder: 'x' }),
                       /not_allowed/, 'a driver secret is not a car');

  const { code, admin_secret } = await rpc(db, 'create_trip', { p_title: 'MT', p_driver_secret: driver_secret });
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(1), p_name: '민지' });

  let view = await rpc(db, 'get_trip', { p_code: code, p_device_key: device(1) });
  assert.equal(view.bank, null, 'not before settlement');
  assert.equal(view.driver_name, '지훈');

  const minji = (await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: admin_secret })).passengers[0].id;
  const payload = { total: 15730, shares: [{ passenger_id: minji, name: '민지', km: 26.7, amount: 3990 },
                                           { passenger_id: null, name: '지훈', role: 'driver', amount: 5300 }] };
  await rpc(db, 'post_settlement', { p_code: code, p_admin_secret: admin_secret, p_payload: payload });
  view = await rpc(db, 'get_trip', { p_code: code, p_device_key: device(1) });
  assert.equal(view.status, 'settled');
  assert.equal(view.settlement.shares[0].amount, 3990);
  assert.equal(view.settlement.shares[0].is_me, true);
  assert.equal(view.settlement.shares[1].is_me, false);
  assert.ok(!JSON.stringify(view.settlement).includes(minji), 'passenger ids are not exposed');
  assert.equal(view.bank, null, 'not until the driver chooses how to settle');

  await rpc(db, 'choose_settlement', { p_code: code, p_admin_secret: admin_secret, p_mode: 'direct' });
  view = await rpc(db, 'get_trip', { p_code: code, p_device_key: device(1) });
  assert.equal(view.settle_mode, 'direct');
  assert.equal(view.bank.account, '000-0000-0000', '바로 정산: shown while the passenger has not paid');

  const stranger = await rpc(db, 'get_trip', { p_code: code, p_device_key: device(9) });
  assert.equal(stranger.bank, null, 'code holders who did not ride do not see the account');

  const admin = await rpc(db, 'mark_paid', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: minji });
  assert.ok(admin.passengers[0].paid_at);
  view = await rpc(db, 'get_trip', { p_code: code, p_device_key: device(1) });
  assert.equal(view.paid, true);
  assert.equal(view.bank, null, 'taken down once the driver marks it paid');

  await rpc(db, 'mark_paid', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: minji, p_paid: false });
  assert.equal((await rpc(db, 'get_trip', { p_code: code, p_device_key: device(1) })).bank.account, '000-0000-0000');
  await rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: false });
  view = await rpc(db, 'get_trip', { p_code: code, p_device_key: device(1) });
  assert.equal(view.bank, null, '계좌 지우기 removes the account at once');
  assert.equal(await rpc(db, 'get_bank_account', { p_vehicle_secret: car.vehicle_secret }), null);
  await assert.rejects(rpc(db, 'join_trip', { p_code: code, p_device_key: device(3), p_name: '늦은 사람' }), /trip_closed/);
});

test('carpool folder stacks trips and settles them together', async () => {
  const db = await freshDb();
  const { driver_secret } = await rpc(db, 'create_driver', { p_display_name: '지훈' });
  const car = await rpc(db, 'create_vehicle_link', {});
  await rpc(db, 'claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  await rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: true, p_bank_name: '페어은행',
                                      p_bank_account: '111-222', p_account_holder: '지훈' });
  const first = await rpc(db, 'create_trip', { p_title: '출근', p_driver_secret: driver_secret });
  await rpc(db, 'join_trip', { p_code: first.code, p_device_key: device(1), p_name: '수진', p_phone4: '1111' });
  await rpc(db, 'join_trip', { p_code: first.code, p_device_key: device(2), p_name: '도윤', p_phone4: '2222' });
  const page = (code, n) => rpc(db, 'get_settle_page', { p_code: code, p_device_key: device(n) });
  const settle = async (trip, sujin, doyoon) => {
    const ps = (await rpc(db, 'get_trip_admin', { p_code: trip.code, p_admin_secret: trip.admin_secret })).passengers;
    const id = (n) => ps.find((p) => p.name === n).id;
    await rpc(db, 'post_settlement', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_payload: {
      total: sujin + doyoon + 1000,
      shares: [{ passenger_id: id('수진'), name: '수진', amount: sujin },
               { passenger_id: id('도윤'), name: '도윤', amount: doyoon },
               { passenger_id: null, name: '지훈', role: 'driver', amount: 1000 }] } });
  };
  const view = (trip, n) => rpc(db, 'get_trip', { p_code: trip.code, p_device_key: device(n) });

  await settle(first, 2430, 2430);
  // 즐겨찾기에 추가 on the arrival screen
  const { carpool_code } = await rpc(db, 'save_carpool', { p_code: first.code, p_admin_secret: first.admin_secret,
                                                          p_driver_secret: driver_secret });
  await assert.rejects(rpc(db, 'choose_settlement', { p_code: first.code, p_admin_secret: first.admin_secret,
                                                      p_mode: 'folder' }), /folder_required/);
  let admin = await rpc(db, 'choose_settlement', { p_code: first.code, p_admin_secret: first.admin_secret,
                                                   p_mode: 'folder', p_new_folder: '출근 카풀' });
  assert.equal(admin.folder.name, '출근 카풀');
  const folder = admin.folder.code;
  assert.match(folder, /^[2-9A-HJ-NP-Z]{8}$/);
  let v = await view(first, 1);
  assert.equal(v.folder.status, 'open');
  assert.equal(v.folder.rides.length, 1);
  assert.equal(v.folder.page, null, 'folder trips get no settlement page of their own');
  assert.equal(v.settlement, null, 'nor everyone\'s shares of that trip');
  assert.equal(v.bank, null, '요청 전: nothing to send yet');

  // the next trip of the favourite suggests the same folder
  const second = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_driver_secret: driver_secret });
  await settle(second, 2430, 2430);
  admin = await rpc(db, 'get_trip_admin', { p_code: second.code, p_admin_secret: second.admin_secret });
  assert.equal(admin.suggested_folder, folder);
  await rpc(db, 'choose_settlement', { p_code: second.code, p_admin_secret: second.admin_secret,
                                       p_mode: 'folder', p_folder_code: folder });
  v = await view(second, 1);
  assert.equal(v.folder.rides.length, 2);
  assert.equal(Number(v.folder.my_total), 4860);

  let list = await rpc(db, 'list_folders', { p_secret: driver_secret });
  assert.deepEqual([list[0].name, list[0].status, list[0].trips, Number(list[0].amount)], ['출근 카풀', 'open', 2, 9720]);

  // 합쳐서 정산하기 makes one settlement page for the batch
  let f = await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: driver_secret });
  assert.equal(f.requested.payments.length, 2);
  assert.equal(f.open.trips.length, 0);
  const code = f.requested.code;
  assert.match(code, /^[2-9A-HJ-NP-Z]{8}$/);
  v = await view(first, 1);
  assert.equal(v.folder.page, code, 'the trip page points to the settlement page');
  assert.equal(v.bank, null, 'the account is on the settlement page only');
  let p = await page(code, 1);
  assert.equal(p.status, 'requested');
  assert.equal(Number(p.me.amount), 4860);
  assert.equal(p.me.rides.length, 2);
  assert.equal(p.bank.account, '111-222', '정산 중: the account is shown');
  assert.ok(!JSON.stringify(p).includes('도윤'), 'each rider sees only their own part');
  const stranger = await page(code, 9);
  assert.equal(stranger.me, null);
  assert.equal(stranger.bank, null);
  // another phone: name + last 4 digits
  assert.equal((await rpc(db, 'verify_settle_page', { p_code: code, p_device_key: device(8), p_name: '수진',
                                                      p_phone4: '0000' })).error, 'verify_failed');
  p = await rpc(db, 'verify_settle_page', { p_code: code, p_device_key: device(8), p_name: '수진', p_phone4: '1111' });
  assert.equal(Number(p.me.amount), 4860);
  await rpc(db, 'report_settle_issue', { p_code: code, p_device_key: device(8), p_message: '금액 확인 부탁해요' });
  f = await rpc(db, 'get_folder_admin', { p_folder_code: folder, p_secret: driver_secret });
  assert.equal(f.requested.issues[0].name, '수진');

  // a trip made meanwhile goes to a new batch; the requested one is locked
  const third = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_driver_secret: driver_secret });
  await settle(third, 2500, 2500);
  await rpc(db, 'choose_settlement', { p_code: third.code, p_admin_secret: third.admin_secret,
                                       p_mode: 'folder', p_folder_code: folder });
  v = await view(third, 1);
  assert.equal(v.folder.page, code, 'the newest trip link still leads to what is owed');
  assert.equal(v.folder.status, 'open');
  await assert.rejects(rpc(db, 'choose_settlement', { p_code: first.code, p_admin_secret: first.admin_secret,
                                                      p_mode: 'direct' }), /batch_locked/);
  await assert.rejects(rpc(db, 'request_batch', { p_folder_code: folder, p_secret: driver_secret }), /batch_in_progress/);

  // 정산 요청 취소 puts everything back into one open batch and closes the page
  f = await rpc(db, 'cancel_batch_request', { p_folder_code: folder, p_secret: driver_secret });
  assert.equal(f.requested, null);
  assert.equal(f.open.trips.length, 3);
  await assert.rejects(page(code, 1), /page_not_found/);
  assert.equal((await view(first, 1)).folder.page, null);

  f = await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: driver_secret });
  const code2 = f.requested.code;
  const sujinPay = f.requested.payments.find((x) => x.name === '수진');
  assert.equal(Number(sujinPay.amount), 7360);
  assert.equal(sujinPay.trips, 3);
  f = await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: driver_secret, p_payment_id: sujinPay.id });
  assert.ok(f.requested, 'still in progress while someone has not paid');
  p = await page(code2, 1);
  assert.equal(p.me.paid, true);
  assert.equal(p.bank, null, 'taken down for the one who paid');
  assert.equal((await page(code2, 2)).bank.account, '111-222', 'still shown to the one who has not');

  const doyoonPay = f.requested.payments.find((x) => x.name === '도윤');
  f = await rpc(db, 'mark_batch_paid', { p_folder_code: folder, p_secret: driver_secret, p_payment_id: doyoonPay.id });
  assert.equal(f.requested, null);
  assert.ok(f.last_done);
  v = await view(third, 2);
  assert.equal(v.folder.page, code2, 'a finished trip still opens its settlement page');
  p = await page(code2, 2);
  assert.equal(p.status, 'done');
  assert.equal(p.bank, null, '정산 완료: the account is taken down');

  list = await rpc(db, 'list_folders', { p_secret: driver_secret });
  assert.deepEqual([list[0].status, list[0].trips], ['open', 0], 'the folder keeps going from zero');
  await assert.rejects(rpc(db, 'get_folder_admin', { p_folder_code: folder, p_secret: 'someone-else' }), /not_allowed/);
});

test('saved carpool recreates the trip with the same members and adds up the month', async () => {
  const db = await freshDb();
  const { driver_secret } = await rpc(db, 'create_driver', { p_display_name: '민호' });
  const first = await rpc(db, 'create_trip', { p_title: '출근 카풀', p_destination: place('회사'),
                                              p_driver_secret: driver_secret });
  // no consent step: places of a saved carpool are kept by default and can be removed later
  const busStop = { label: '버스정류장', lat: 37.561234, lng: 126.973456 };
  await rpc(db, 'join_trip', { p_code: first.code, p_device_key: device(1), p_name: '수진', p_pickup: busStop });
  await rpc(db, 'join_trip', { p_code: first.code, p_device_key: device(2), p_name: '도윤', p_pickup: place('도윤 집') });
  await rpc(db, 'join_trip', { p_code: first.code, p_device_key: device(3), p_name: '안 온 사람', p_pickup: place('정문') });
  // a favourite is added on the arrival screen, after the trip
  await assert.rejects(rpc(db, 'save_carpool', { p_code: first.code, p_admin_secret: first.admin_secret,
                                                 p_driver_secret: driver_secret }), /not_settled/);
  const shareFor = async (code, secret) => {
    const ps = (await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: secret })).passengers;
    const id = (n) => ps.find((p) => p.name === n).id;
    return { total: 6000, shares: [{ passenger_id: id('수진'), name: '수진', km: 20, amount: 2430 },
                                   { passenger_id: id('도윤'), name: '도윤', km: 15, amount: 1800 }] };
  };
  await rpc(db, 'post_settlement', { p_code: first.code, p_admin_secret: first.admin_secret,
                                     p_payload: await shareFor(first.code, first.admin_secret) });
  const { carpool_code } = await rpc(db, 'save_carpool', { p_code: first.code, p_admin_secret: first.admin_secret,
                                                          p_driver_secret: driver_secret });
  assert.match(carpool_code, /^[2-9A-HJ-NP-Z]{8}$/);

  const next = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_driver_secret: driver_secret });
  assert.notEqual(next.code, first.code);
  const again = await rpc(db, 'get_trip', { p_code: next.code, p_device_key: device(1) });
  assert.equal(again.me.pickup.label, '버스정류장', 'members keep the same stop');
  assert.equal(again.me.pickup.lat, 37.561234, 'with the exact point, not the rounded record');
  assert.equal(again.passengers.length, 2, 'only the people who actually rode are kept');
  assert.equal((await rpc(db, 'get_trip', { p_code: next.code, p_device_key: device(3) })).me, null);
  const other = await rpc(db, 'get_trip', { p_code: next.code, p_device_key: device(2) });
  assert.equal(other.me.pickup.label, '도윤 집');
  await rpc(db, 'post_settlement', { p_code: next.code, p_admin_secret: next.admin_secret,
                                     p_payload: await shareFor(next.code, next.admin_secret) });

  // only the driver deletes; a finished trip's place is part of its record until the settlement is complete
  const sujinRow = (await rpc(db, 'get_trip_admin', { p_code: next.code, p_admin_secret: next.admin_secret }))
    .passengers.find((p) => p.name === '수진');
  await assert.rejects(rpc(db, 'remove_passenger_place', { p_code: next.code, p_admin_secret: next.admin_secret,
                                                           p_passenger_id: sujinRow.id }), /record_locked/);
  // the x on the favourites screen removes the place kept for the next trip; the record stays
  const sujinMember = (await rpc(db, 'get_driver', { p_driver_secret: driver_secret })).carpools[0].members
    .find((m) => m.name === '수진');
  await rpc(db, 'remove_member_place', { p_carpool_code: carpool_code, p_secret: driver_secret,
                                         p_member_id: sujinMember.id });
  const kept = (await rpc(db, 'get_trip_admin', { p_code: next.code, p_admin_secret: next.admin_secret }))
    .passengers.find((p) => p.name === '수진');
  assert.equal(kept.pickup.label, '버스정류장', 'the record of a finished trip is not deleted');
  const third = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_driver_secret: driver_secret });
  const cleared = await rpc(db, 'get_trip', { p_code: third.code, p_device_key: device(1) });
  assert.equal(cleared.me.pickup, null, 'the x removes the kept place');
  assert.equal(cleared.me.save_place, false, 'and the choice sticks');
  // editing the stop again later does not silently turn saving back on
  await rpc(db, 'join_trip', { p_code: third.code, p_device_key: device(1), p_name: '수진', p_pickup: place('새 정류장') });
  assert.equal((await rpc(db, 'get_trip', { p_code: third.code, p_device_key: device(1) })).me.save_place, false);
  assert.equal((await rpc(db, 'get_trip', { p_code: third.code, p_device_key: device(2) })).me.pickup.label, '도윤 집');
  await rpc(db, 'set_trip_status', { p_code: third.code, p_admin_secret: third.admin_secret, p_status: 'cancelled' });

  const cp = await rpc(db, 'get_carpool', { p_carpool_code: carpool_code, p_device_key: device(1) });
  assert.equal(cp.latest_trip, next.code);
  assert.equal(cp.month.trips, 2);
  assert.equal(Number(cp.month.my_amount), 4860);

  // the x next to an address on the favourites screen removes the kept place right away
  const members = (await rpc(db, 'get_driver', { p_driver_secret: driver_secret })).carpools[0].members;
  const doyoon = members.find((m) => m.name === '도윤');
  assert.equal(doyoon.pickup, '도윤 집');
  const after = await rpc(db, 'remove_member_place', { p_carpool_code: carpool_code, p_secret: driver_secret,
                                                       p_member_id: doyoon.id });
  assert.equal(after.members.find((m) => m.name === '도윤').pickup, null);
  const fourth = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_driver_secret: driver_secret });
  assert.equal((await rpc(db, 'get_trip', { p_code: fourth.code, p_device_key: device(2) })).me.pickup, null);
  await assert.rejects(rpc(db, 'remove_member_place', { p_carpool_code: carpool_code, p_secret: 'someone-else',
                                                        p_member_id: doyoon.id }), /not_allowed/);

  await assert.rejects(rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_driver_secret: 'someone-else' }),
                       /not_allowed/);
});

test('linked car can load and manage trips its driver prepared', async () => {
  const db = await freshDb();
  const { driver_secret } = await rpc(db, 'create_driver', {});
  const car = await rpc(db, 'create_vehicle_link', {});
  assert.match(car.link_code, /^[2-9A-HJ-NP-Z]{6}$/);
  await rpc(db, 'claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  const prepared = await rpc(db, 'create_trip', { p_title: '외근', p_driver_secret: driver_secret });

  const list = await rpc(db, 'get_vehicle_trips', { p_vehicle_secret: car.vehicle_secret });
  assert.equal(list.linked, true);
  assert.equal(list.trips[0].code, prepared.code);

  const started = await rpc(db, 'set_trip_status', { p_code: prepared.code, p_admin_secret: car.vehicle_secret,
                                                     p_status: 'in_progress' });
  assert.equal(started.status, 'in_progress');
  await assert.rejects(rpc(db, 'claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret }),
                       /link_expired/, 'a link code works once');

  await rpc(db, 'revoke_vehicle', { p_vehicle_secret: car.vehicle_secret });
  await assert.rejects(rpc(db, 'get_vehicle_trips', { p_vehicle_secret: car.vehicle_secret }), /not_allowed/);
});

test('course shows the origin and my stops, hides others, the destination, and the car after boarding', async () => {
  const db = await freshDb();
  const { code, admin_secret } = await rpc(db, 'create_trip', { p_title: 'MT 귀갓길', p_origin: place('가평 MT 숙소'),
                                                               p_destination: place('지훈 집') });
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(1), p_name: '민지', p_dropoff: place('민지 집') });
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(2), p_name: '태호', p_phone4: '5678',
                               p_pickup: place('신촌역 2번 출구'),
                               p_dropoff: { label: '연희동 주민센터', area: '서대문구 연희동', lat: 37.566, lng: 126.931 } });
  const ps = (await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: admin_secret })).passengers;
  const [minji, taeho] = [ps[0].id, ps[1].id];
  const progress = {
    car: { lat: 37.57, lng: 126.98 },
    stops: [
      { kind: 'start', label: '가평 MT 숙소', time: '15:10', state: 'done' },
      { kind: 'pickup', passenger_id: taeho, label: '신촌역 2번 출구', eta_min: 10, distance_km: 7, state: 'now' },
      { kind: 'dropoff', passenger_id: minji, label: '민지 집', eta_min: 20, state: 'next' },
      { kind: 'dropoff', passenger_id: taeho, label: '연희동 주민센터', area: '서대문구 연희동', eta_min: 30, state: 'next' },
      { kind: 'end', label: '지훈 집', state: 'next' },
    ],
  };
  await rpc(db, 'update_progress', { p_code: code, p_admin_secret: admin_secret, p_progress: progress,
                                     p_estimates: [{ passenger_id: taeho, amount: 370 }] });

  let view = await rpc(db, 'get_trip', { p_code: code, p_device_key: device(2) });
  const json = JSON.stringify(view);
  assert.ok(!json.includes('민지 집') && !json.includes('지훈 집'), 'other stops and the destination stay hidden');
  assert.equal(view.origin, '가평 MT 숙소');
  assert.deepEqual(view.progress.stops.map((s) => s.kind), ['start', 'my_pickup', 'other_dropoff', 'my_dropoff']);
  assert.equal(view.progress.stops[3].label, '서대문구 연희동', 'my drop-off is shown at area level');
  assert.equal(view.progress.car.lat, 37.57);
  assert.equal(Number(view.me.estimate), 370);
  assert.equal(view.seats_left, 2);

  await rpc(db, 'mark_passenger', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: taeho, p_boarded: true });
  view = await rpc(db, 'get_trip', { p_code: code, p_device_key: device(2) });
  assert.equal(view.progress.car, null, 'car position is no longer shared after boarding');

  // the same passenger opens the page on another phone
  const wrong = await rpc(db, 'verify_device', { p_code: code, p_device_key: device(7), p_name: '태호', p_phone4: '0000' });
  assert.equal(wrong.error, 'verify_failed');
  view = await rpc(db, 'verify_device', { p_code: code, p_device_key: device(7), p_name: '태호', p_phone4: '5678' });
  assert.equal(view.me.name, '태호');

  await rpc(db, 'report_issue', { p_code: code, p_device_key: device(7), p_message: '정산에 문제가 있어요' });
  const admin = await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: admin_secret });
  assert.equal(admin.issues[0].name, '태호');
});

test('a trip made on the car screen hands the passengers\' stops back to the car as waypoints', async () => {
  const db = await freshDb();
  // the car makes the trip itself (L2 invite QR) and keeps the admin secret
  const car = await rpc(db, 'create_vehicle_link', {});
  const trip = await rpc(db, 'create_trip', { p_title: 'MT 귀갓길', p_origin: place('가평 MT 숙소'),
                                             p_vehicle_secret: car.vehicle_secret });
  // a passenger scans the QR and picks places from the search list
  await rpc(db, 'join_trip', {
    p_code: trip.code, p_device_key: device(1), p_name: '태호', p_phone4: '5678',
    p_pickup: { label: '신촌역 2번 출구', address: '서울 서대문구 신촌로 90', lat: 37.5552, lng: 126.9368 },
    p_dropoff: { label: '연희동 주민센터', area: '서대문구 연희동', address: '서울 서대문구 연희로 109', lat: 37.5662, lng: 126.9312 },
  });
  // a place typed without picking has no coordinates and is refused
  await assert.rejects(rpc(db, 'join_trip', { p_code: trip.code, p_device_key: device(2), p_name: '민지',
                                              p_dropoff: { label: '민지 집' } }), /place_needs_location/);

  // the car reads the stops back with its own secret and builds NaviHelper RequestWaypointInfo values
  const admin = await rpc(db, 'get_trip_admin', { p_code: trip.code, p_admin_secret: car.vehicle_secret });
  const stops = admin.passengers.flatMap((p) => [p.pickup, p.dropoff].filter(Boolean));
  const waypoints = stops.slice(0, 2).map((s, i) => ({
    latitude: s.lat, longitude: s.lng, name: s.label, index: i === 0 ? 'FIRST' : 'SECOND', address: s.address,
  }));
  assert.deepEqual(waypoints[0], { latitude: 37.5552, longitude: 126.9368, name: '신촌역 2번 출구', index: 'FIRST',
                                   address: '서울 서대문구 신촌로 90' });
  assert.equal(waypoints[1].name, '연희동 주민센터');
  // the same secret the car holds from create_trip also works
  const again = await rpc(db, 'get_trip_admin', { p_code: trip.code, p_admin_secret: trip.admin_secret });
  assert.equal(again.passengers.length, 1);

  // a trip started on the spot from the car QR can be added to favourites on the arrival screen too
  const taeho = again.passengers[0].id;
  await rpc(db, 'post_settlement', { p_code: trip.code, p_admin_secret: car.vehicle_secret,
                                     p_payload: { total: 3000, shares: [{ passenger_id: taeho, name: '태호', amount: 1500 }] } });
  const { carpool_code } = await rpc(db, 'save_carpool', { p_code: trip.code, p_admin_secret: car.vehicle_secret });
  const loaded = await rpc(db, 'get_vehicle_trips', { p_vehicle_secret: car.vehicle_secret });
  assert.equal(loaded.carpools[0].code, carpool_code);
  const next = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_vehicle_secret: car.vehicle_secret });
  const member = (await rpc(db, 'get_trip_admin', { p_code: next.code, p_admin_secret: next.admin_secret })).passengers[0];
  assert.equal(member.pickup.lat, 37.5552, 'the favourite keeps the exact waypoint');
});

test('a full car refuses new passengers, and only the driver takes someone off before boarding', async () => {
  const db = await freshDb();
  const { code, admin_secret } = await rpc(db, 'create_trip', { p_title: 't', p_seats: 1 });
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(1), p_name: '민지' });
  await assert.rejects(rpc(db, 'join_trip', { p_code: code, p_device_key: device(2), p_name: '서연' }), /trip_full/);
  const v = await rpc(db, 'join_trip', { p_code: code, p_device_key: device(1), p_name: '민지', p_dropoff: place('집') });
  assert.equal(v.seats_left, 0);

  // a passenger who drops out tells the driver; the driver takes them off and the seat opens
  const minji = (await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: admin_secret })).passengers[0].id;
  const admin = await rpc(db, 'remove_passenger', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: minji });
  assert.equal(admin.passengers.length, 0);
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(2), p_name: '서연' });
  // after boarding the passenger is part of the ride record
  const seoyeon = (await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: admin_secret })).passengers[0].id;
  await rpc(db, 'mark_passenger', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: seoyeon, p_boarded: true });
  await assert.rejects(rpc(db, 'remove_passenger', { p_code: code, p_admin_secret: admin_secret,
                                                     p_passenger_id: seoyeon }), /record_locked/);
  await assert.rejects(rpc(db, 'remove_passenger', { p_code: code, p_admin_secret: 'passenger', p_passenger_id: seoyeon }),
                       /not_allowed/);
});

test('records are corrected, never deleted: removing my place during a trip and a re-settlement', async () => {
  const db = await freshDb();
  const { code, admin_secret } = await rpc(db, 'create_trip', { p_title: '출근' });
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(1), p_name: '민지', p_phone4: '1234',
                               p_pickup: place('정류장'), p_dropoff: place('집') });
  await rpc(db, 'set_trip_status', { p_code: code, p_admin_secret: admin_secret, p_status: 'in_progress' });

  // after departure the invite QR no longer lets newcomers in, members can still edit their stops
  const seoyeonJoin = { p_code: code, p_device_key: device(2), p_name: '서연', p_dropoff: place('서연 집') };
  await assert.rejects(rpc(db, 'join_trip', seoyeonJoin), /join_closed/);
  assert.equal((await rpc(db, 'get_trip', { p_code: code, p_device_key: device(2) })).joinable, false);
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(1), p_name: '민지', p_phone4: '1234',
                               p_pickup: place('정류장'), p_dropoff: place('집') });
  // the car shows the QR again at a stop: open for 10 minutes
  const opened = await rpc(db, 'set_join_open', { p_code: code, p_admin_secret: admin_secret });
  assert.ok(new Date(opened.join_open_until) > new Date());
  assert.equal((await rpc(db, 'get_trip', { p_code: code, p_device_key: device(2) })).joinable, true);
  await rpc(db, 'join_trip', seoyeonJoin);
  // closed again when the QR is hidden or the car drives off, and on its own after 10 minutes
  await rpc(db, 'set_join_open', { p_code: code, p_admin_secret: admin_secret, p_open: false });
  await assert.rejects(rpc(db, 'join_trip', { p_code: code, p_device_key: device(3), p_name: '늦은 사람' }), /join_closed/);
  await rpc(db, 'set_join_open', { p_code: code, p_admin_secret: admin_secret });
  await db.query(`update public.trips set join_open_until = now() - interval '1 minute'`);
  await assert.rejects(rpc(db, 'join_trip', { p_code: code, p_device_key: device(3), p_name: '늦은 사람' }), /join_closed/);
  await assert.rejects(rpc(db, 'set_join_open', { p_code: code, p_admin_secret: 'passenger' }), /not_allowed/);

  let admin = await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: admin_secret });
  const minji = admin.passengers.find((p) => p.name === '민지');
  const seoyeon = admin.passengers.find((p) => p.name === '서연');

  // before boarding the driver removes a place: it leaves the stops, so the car asks NaviHelper for the
  // whole route again
  admin = await rpc(db, 'remove_passenger_place', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: seoyeon.id });
  const removed = admin.passengers.find((p) => p.id === seoyeon.id);
  assert.ok(removed.place_removed_at);
  assert.equal(removed.dropoff, null);
  // after boarding it is part of the ride record: corrected only, until the settlement is complete
  await rpc(db, 'mark_passenger', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: minji.id, p_boarded: true });
  await assert.rejects(rpc(db, 'remove_passenger_place', { p_code: code, p_admin_secret: admin_secret,
                                                           p_passenger_id: minji.id }), /record_locked/);

  // settling again keeps the result it replaces, with the reason
  const payload = (amount) => ({ total: 5000, shares: [{ passenger_id: minji.id, name: '민지', amount, from: '정류장', to: '집' },
                                                       { passenger_id: null, name: '운전자', role: 'driver', amount: 5000 - amount }] });
  await rpc(db, 'post_settlement', { p_code: code, p_admin_secret: admin_secret, p_payload: payload(3000) });
  admin = await rpc(db, 'post_settlement', { p_code: code, p_admin_secret: admin_secret, p_payload: payload(2500),
                                             p_reason: '하차 위치 정정' });
  assert.equal(admin.settlement.shares[0].amount, 2500);
  assert.equal(admin.revisions.length, 1);
  assert.equal(admin.revisions[0].reason, '하차 위치 정정');
  assert.equal((await rpc(db, 'get_trip', { p_code: code, p_device_key: device(1) })).corrections, 1);

  // the ride record can be deleted only once the settlement is complete, by the driver
  await assert.rejects(rpc(db, 'delete_ride_record', { p_code: code, p_admin_secret: admin_secret,
                                                       p_passenger_id: minji.id }), /settlement_not_done/);
  await rpc(db, 'choose_settlement', { p_code: code, p_admin_secret: admin_secret, p_mode: 'direct' });
  await rpc(db, 'mark_paid', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: minji.id });
  admin = await rpc(db, 'delete_ride_record', { p_code: code, p_admin_secret: admin_secret, p_passenger_id: minji.id });
  const gone = admin.passengers.find((p) => p.id === minji.id);
  assert.ok(gone.record_deleted_at);
  assert.equal(gone.name, '기록을 지운 동승자');
  assert.equal(gone.pickup, null);
  assert.equal(gone.boarded_at, null);
  // the trip, the amounts and the corrections stay; the share is kept without a name
  assert.deepEqual(admin.settlement.shares.map((x) => [x.name, x.amount]), [['기록을 지운 동승자', 2500], ['운전자', 2500]]);
  assert.equal(admin.settlement.shares[0].from, undefined);
  assert.equal(admin.revisions.length, 1);
  assert.ok(!JSON.stringify(admin).includes('민지'), 'the name is gone from the record and the corrections');
  assert.equal((await rpc(db, 'get_trip', { p_code: code, p_device_key: device(1) })).me, null);

  // wrong digits are counted (not rolled back with an error): 5 an hour per phone, 30 an hour per page
  const guess = (n) => rpc(db, 'verify_device', { p_code: code, p_device_key: device(n), p_name: '민지', p_phone4: '0000' });
  for (let i = 0; i < 5; i++) assert.equal((await guess(5)).error, 'verify_failed');
  await assert.rejects(guess(5), /too_many_attempts/, 'that phone waits an hour');
  assert.equal((await guess(6)).error, 'verify_failed', 'another phone is not locked out by it');
  for (let n = 7; n < 31; n++) await guess(n);
  await assert.rejects(guess(40), /too_many_attempts/, '30 wrong tries close the page for an hour');
  await db.query(`update public.verify_attempts set failed_at = failed_at - interval '61 minutes'`);
  assert.equal((await guess(5)).error, 'verify_failed', 'and it opens again after the hour');
});

test('stops are cleared a day after the trip ends', async () => {
  const db = await freshDb();
  const { code, admin_secret } = await rpc(db, 'create_trip', { p_title: 't' });
  await rpc(db, 'join_trip', { p_code: code, p_device_key: device(1), p_name: '민지', p_dropoff: place('집') });
  await rpc(db, 'post_settlement', { p_code: code, p_admin_secret: admin_secret, p_payload: { shares: [] } });
  await db.query(`update public.trips set ended_at = now() - interval '2 days'`);
  const res = await rpc(db, 'purge_old_locations');
  assert.equal(res.cleared_locations, 1);
  const admin = await rpc(db, 'get_trip_admin', { p_code: code, p_admin_secret: admin_secret });
  assert.equal(admin.passengers[0].dropoff, null);
});

test('driver info from the car opens a read-only "내 휴대폰으로 보내기" view of each settlement', async () => {
  const db = await freshDb();
  // 운전자 정보 입력 on the car before any driver links it: name, last 4 digits, account
  const car = await rpc(db, 'create_vehicle_link', {});
  await assert.rejects(rpc(db, 'set_driver_info', { p_vehicle_secret: car.vehicle_secret, p_name: '지훈' }), /phone4_required/);
  await assert.rejects(rpc(db, 'set_driver_info', { p_vehicle_secret: car.vehicle_secret, p_name: '지훈', p_phone4: '12' }),
                       /bad_phone4/);
  let info = await rpc(db, 'set_driver_info', { p_vehicle_secret: car.vehicle_secret, p_name: '지훈', p_phone4: '7890' });
  assert.deepEqual([info.name, info.phone4_set, info.bank], ['지훈', true, null]);
  await rpc(db, 'set_bank_account', { p_vehicle_secret: car.vehicle_secret, p_consent: true, p_bank_name: '페어은행',
                                      p_bank_account: '1002-123-7890', p_account_holder: '지훈' });
  const stored = (await db.query('select phone4_hash from public.drivers where phone4_hash is not null')).rows[0];
  assert.notEqual(stored.phone4_hash, '7890');
  // linking the car brings the name and digits along
  const { driver_secret } = await rpc(db, 'create_driver', {});
  await rpc(db, 'claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  info = await rpc(db, 'get_driver_info', { p_vehicle_secret: car.vehicle_secret });
  assert.deepEqual([info.name, info.phone4_set, info.bank.account], ['지훈', true, '1002-123-7890']);

  // 바로 정산 trip
  const trip = await rpc(db, 'create_trip', { p_title: 'MT 귀갓길', p_vehicle_secret: car.vehicle_secret });
  await rpc(db, 'join_trip', { p_code: trip.code, p_device_key: device(1), p_name: '민지', p_phone4: '1111' });
  await rpc(db, 'join_trip', { p_code: trip.code, p_device_key: device(2), p_name: '서연', p_phone4: '2222' });
  const ps = (await rpc(db, 'get_trip_admin', { p_code: trip.code, p_admin_secret: car.vehicle_secret })).passengers;
  // the invite screen (L2/P2) sends the invite before the trip ends
  const asDriver = (code, phone4 = '7890') =>
    rpc(db, 'verify_device', { p_code: code, p_device_key: device(9), p_name: '지훈', p_phone4: phone4 });
  assert.equal((await rpc(db, 'queue_trip_share', { p_code: trip.code, p_admin_secret: car.vehicle_secret })).kind, 'invite');
  const invite = (await asDriver(trip.code)).driver_view.cards;
  assert.deepEqual(invite.map((c) => [c.kind, c.code, c.seats_left]), [['invite', trip.code, 2]]);
  assert.deepEqual(invite[0].people.map((p) => p.name), ['민지', '서연']);
  assert.equal(JSON.stringify(invite).includes('연희동'), false, 'no stops on the invite card');
  await rpc(db, 'post_settlement', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_payload: {
    total: 15730, shares: [{ passenger_id: ps[0].id, name: '민지', amount: 3990, from: '창천동', to: '연희동' },
                           { passenger_id: ps[1].id, name: '서연', amount: 4700, from: '창천동', to: '망원동' },
                           { passenger_id: null, name: '지훈', role: 'driver', amount: 7040 }] } });
  await assert.rejects(rpc(db, 'queue_trip_share', { p_code: trip.code, p_admin_secret: car.vehicle_secret }), /not_chosen/);
  await rpc(db, 'choose_settlement', { p_code: trip.code, p_admin_secret: car.vehicle_secret, p_mode: 'direct' });

  let res = await asDriver(trip.code);
  assert.deepEqual(res.driver_view.cards, [], 'nothing until the car sends it');
  assert.equal((await asDriver(trip.code, '0000')).error, 'verify_failed');

  assert.equal((await rpc(db, 'queue_trip_share', { p_code: trip.code, p_admin_secret: car.vehicle_secret })).kind, 'trip');
  res = await asDriver(trip.code);
  const card = res.driver_view.cards[0];
  assert.equal(res.driver_view.driver_name, '지훈');
  assert.deepEqual([card.kind, card.code, card.total, card.bank.account], ['trip', trip.code, 15730, '1002-123-7890']);
  assert.deepEqual(card.people.map((p) => [p.name, p.amount, p.to, p.paid]),
                   [['민지', 3990, '연희동', false], ['서연', 4700, '망원동', false]]);
  // read-only: the phone is not bound to anyone and the driver secret never leaves the car
  assert.equal((await rpc(db, 'get_trip', { p_code: trip.code, p_device_key: device(9) })).me, null);
  assert.ok(!JSON.stringify(res).includes(trip.admin_secret));

  for (const p of ps) await rpc(db, 'mark_paid', { p_code: trip.code, p_admin_secret: car.vehicle_secret, p_passenger_id: p.id });
  assert.deepEqual((await asDriver(trip.code)).driver_view.cards, [], 'the card goes once everyone has paid');

  // 카풀 폴더: a stacking trip shows its own shares, a requested batch shows every person and trip
  const t2 = await rpc(db, 'create_trip', { p_title: '출근', p_vehicle_secret: car.vehicle_secret });
  await rpc(db, 'join_trip', { p_code: t2.code, p_device_key: device(1), p_name: '민지', p_phone4: '1111' });
  const p2 = (await rpc(db, 'get_trip_admin', { p_code: t2.code, p_admin_secret: t2.admin_secret })).passengers[0];
  await rpc(db, 'post_settlement', { p_code: t2.code, p_admin_secret: t2.admin_secret, p_payload: {
    total: 6000, shares: [{ passenger_id: p2.id, name: '민지', amount: 2430, from: '신촌', to: '회사' },
                          { passenger_id: null, name: '지훈', role: 'driver', amount: 3570 }] } });
  const folder = (await rpc(db, 'choose_settlement', { p_code: t2.code, p_admin_secret: t2.admin_secret, p_mode: 'folder',
                                                         p_new_folder: '출근 카풀' })).folder;
  await rpc(db, 'queue_trip_share', { p_code: t2.code, p_admin_secret: t2.admin_secret });
  let c = (await asDriver(t2.code)).driver_view.cards;
  assert.deepEqual([c.length, c[0].settle_mode, c[0].folder, c[0].bank], [1, 'folder', '출근 카풀', null],
                   'still stacking: no account yet');

  await assert.rejects(rpc(db, 'queue_batch_share', { p_folder_code: folder.code, p_secret: car.vehicle_secret }), /no_request/);
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder.code, p_secret: car.vehicle_secret })).requested;
  await rpc(db, 'queue_batch_share', { p_folder_code: folder.code, p_secret: car.vehicle_secret });
  res = await rpc(db, 'verify_settle_page', { p_code: req.code, p_device_key: device(9), p_name: '지훈', p_phone4: '7890' });
  c = res.driver_view.cards;
  assert.deepEqual([c.length, c[0].kind, c[0].code, c[0].total, c[0].bank.account], [1, 'batch', req.code, 2430, '1002-123-7890']);
  assert.deepEqual(c[0].rides[0].shares, [{ name: '민지', amount: 2430, from: '신촌', to: '회사' }]);
  // the trip link of that batch shows it too
  c = (await asDriver(t2.code)).driver_view.cards;
  assert.deepEqual(c.map((x) => x.kind), ['batch']);
  assert.equal((await rpc(db, 'get_settle_page', { p_code: req.code, p_device_key: device(9) })).me, null);

  await rpc(db, 'cancel_batch_request', { p_folder_code: folder.code, p_secret: car.vehicle_secret });
  const again = (await rpc(db, 'request_batch', { p_folder_code: folder.code, p_secret: car.vehicle_secret })).requested;
  res = await rpc(db, 'verify_settle_page', { p_code: again.code, p_device_key: device(9), p_name: '지훈', p_phone4: '7890' });
  assert.deepEqual(res.driver_view.cards, [], 'a cancelled request has to be sent again');
});
