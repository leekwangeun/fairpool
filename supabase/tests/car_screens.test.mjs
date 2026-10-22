// What the car screens read and change beyond a single trip: requests (L1-8), unchecked records
// (L5-3, L6-1), past trips (L1-5), new folder chips (L6) and favourites (L1-3, L1-3a).
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
  return { driver_secret, vehicle_secret: car.vehicle_secret };
}

const admin = (db, trip) => rpc(db, 'get_trip_admin', { p_code: trip.code, p_admin_secret: trip.admin_secret });

// riders = [[deviceNo, name], ...]; shares = { name: amount }; the car records and settles the trip
async function ride(db, secret, riders, shares, title = '출근') {
  const trip = await rpc(db, 'create_trip', { p_title: title, p_vehicle_secret: secret });
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
  const list = Object.entries(shares).map(([name, amount]) => ({ passenger_id: ids[name], name, amount, km: 10 }));
  list.push({ passenger_id: null, name: '지훈', role: 'driver', amount: 3000 });
  await rpc(db, 'post_settlement', { p_code: trip.code, p_admin_secret: trip.admin_secret,
                                     p_payload: { total: list.reduce((s, x) => s + x.amount, 0), shares: list,
                                                  fuel: { distance_km: 46 } } });
  return { ...trip, ids };
}

test('L1-8: a reported problem arrives with its kind, the recorded stop and the share, then is handled', async () => {
  const db = await freshDb();
  const { vehicle_secret, driver_secret } = await driverWithCar(db);
  const t = await ride(db, vehicle_secret, [[1, '태호'], [2, '서연']], { 태호: 260, 서연: 5170 });
  await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'direct' });
  await assert.rejects(rpc(db, 'report_issue', { p_code: t.code, p_device_key: device(1), p_kind: 'delete' }),
                       /bad_kind/);
  await rpc(db, 'report_issue', { p_code: t.code, p_device_key: device(1), p_kind: 'place',
                                  p_message: '신촌역 3번 출구에서 탔어요' });

  let list = await rpc(db, 'list_issues', { p_secret: vehicle_secret });
  assert.equal(list.open, 1, 'the badge count on L1');
  const issue = list.issues[0];
  assert.equal(issue.name, '태호');
  assert.equal(issue.kind, 'place');
  assert.equal(issue.message, '신촌역 3번 출구에서 탔어요');
  assert.equal(issue.trip.code, t.code);
  assert.equal(issue.pickup.label, '태호 정류장');
  assert.ok(issue.boarded_at);
  assert.equal(Number(issue.amount), 260);
  assert.equal(issue.settle_done, false, '정산 중');
  assert.equal((await rpc(db, 'list_issues', { p_secret: driver_secret })).open, 1, 'the phone sees the same list');

  const stranger = await rpc(db, 'create_driver', { p_display_name: '은지' });
  assert.equal((await rpc(db, 'list_issues', { p_secret: stranger.driver_secret })).open, 0);
  await assert.rejects(rpc(db, 'resolve_issue', { p_secret: stranger.driver_secret, p_issue_id: issue.id }),
                       /not_allowed/);

  // 고칠 것 없어요
  list = await rpc(db, 'resolve_issue', { p_secret: vehicle_secret, p_issue_id: issue.id });
  assert.equal(list.open, 0);
  assert.equal(list.resolved, 1);
  const handled = await rpc(db, 'list_issues', { p_secret: vehicle_secret, p_resolved: true });
  assert.equal(handled.issues[0].id, issue.id, '처리한 요청 보기');
  assert.ok(handled.issues[0].resolved_at);
  assert.notEqual((await admin(db, t)).issues[0].resolved_at, null);

  // a note is optional
  await rpc(db, 'report_issue', { p_code: t.code, p_device_key: device(2), p_kind: 'amount' });
  list = await rpc(db, 'list_issues', { p_secret: vehicle_secret });
  assert.equal(list.issues[0].message, null);
  assert.equal(list.issues[0].kind, 'amount');
});

test('a problem reported on a folder settlement page comes with its kind', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await ride(db, vehicle_secret, [[1, '태호']], { 태호: 1200 });
  const folder = (await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'folder',
                                                       p_new_folder: '동아리 MT' })).folder.code;
  const req = (await rpc(db, 'request_batch', { p_folder_code: folder, p_secret: vehicle_secret })).requested;
  await rpc(db, 'report_settle_issue', { p_code: req.code, p_device_key: device(1), p_kind: 'people',
                                         p_message: '민지는 안 탔어요' });
  const issue = (await rpc(db, 'list_issues', { p_secret: vehicle_secret })).issues[0];
  assert.equal(issue.kind, 'people');
  assert.equal(issue.trip.settle_mode, 'folder');
  const f = await rpc(db, 'get_folder_admin', { p_folder_code: folder, p_secret: vehicle_secret });
  assert.equal(f.requested.issues[0].kind, 'people');
});

test('records the car made while nobody looked stay unchecked until confirmed or corrected', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await rpc(db, 'create_trip', { p_title: 'MT 귀갓길', p_vehicle_secret: vehicle_secret });
  for (const [n, name] of [[1, '태호'], [2, '서연']]) {
    await rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(n), p_name: name, p_pickup: place(name) });
  }
  await rpc(db, 'set_trip_status', { p_code: t.code, p_admin_secret: t.admin_secret, p_status: 'in_progress' });
  const ids = Object.fromEntries((await admin(db, t)).passengers.map((p) => [p.name, p.id]));
  const mark = (name, args) => rpc(db, 'mark_passenger', { p_code: t.code, p_admin_secret: t.admin_secret,
                                                           p_passenger_id: ids[name], ...args });
  // L5-3: the car records on its own
  await mark('태호', { p_boarded: true, p_unchecked: true });
  await mark('서연', { p_boarded: true, p_unchecked: true });
  await mark('태호', { p_alighted: true, p_unchecked: true });
  const row = async (name) => (await admin(db, t)).passengers.find((p) => p.name === name);
  assert.equal((await row('태호')).board_unchecked, true);
  assert.equal((await row('태호')).alight_unchecked, true);
  let past = (await rpc(db, 'list_trips', { p_secret: vehicle_secret })).trips[0];
  assert.equal(past.unchecked, 3, 'L1-5 확인 필요 tab');

  // 맞아요 for one, then a correction for another clears its flag too
  await rpc(db, 'confirm_records', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: ids['태호'] });
  assert.equal((await row('태호')).board_unchecked, false);
  assert.equal((await row('태호')).alight_unchecked, false);
  assert.ok((await row('태호')).alighted_at, 'confirming keeps the record as it is');
  await mark('서연', { p_boarded: false });
  assert.equal((await row('서연')).board_unchecked, false);
  assert.equal((await row('서연')).boarded_at, null);

  // 모두 맞아요
  await mark('서연', { p_boarded: true, p_alighted: true, p_unchecked: true });
  await rpc(db, 'confirm_records', { p_code: t.code, p_admin_secret: t.admin_secret });
  past = (await rpc(db, 'list_trips', { p_secret: vehicle_secret })).trips[0];
  assert.equal(past.unchecked, 0);
});

test('L1-5: past trips with folder chips, this month\'s amount to receive and frequent riders', async () => {
  const db = await freshDb();
  const { vehicle_secret, driver_secret } = await driverWithCar(db);
  const a = await ride(db, vehicle_secret, [[1, '태호'], [2, '서연']], { 태호: 2000, 서연: 3000 }, '출근');
  await rpc(db, 'choose_settlement', { p_code: a.code, p_admin_secret: a.admin_secret, p_mode: 'direct' });
  const b = await ride(db, vehicle_secret, [[1, '태호']], { 태호: 1500 }, '퇴근');
  await rpc(db, 'choose_settlement', { p_code: b.code, p_admin_secret: b.admin_secret, p_mode: 'folder',
                                       p_new_folder: '출근 카풀' });
  const waiting = await rpc(db, 'create_trip', { p_title: '내일', p_vehicle_secret: vehicle_secret });

  let res = await rpc(db, 'list_trips', { p_secret: vehicle_secret });
  assert.deepEqual(res.trips.map((x) => x.code), [b.code, a.code], 'newest first, trips not started are left out');
  assert.ok(!res.trips.some((x) => x.code === waiting.code));
  assert.equal(Number(res.month.receivable), 6500);
  assert.equal(res.month.trips, 2);
  assert.deepEqual(res.frequent[0], { name: '태호', trips: 2 });
  const [pb, pa] = res.trips;
  assert.equal(pb.folder.name, '출근 카풀');
  assert.equal(pb.folder.status, 'open');
  assert.equal(pa.folder, null);
  assert.equal(pa.people, 2);
  assert.equal(Number(pa.km), 46);
  assert.equal(Number(pa.receivable), 5000);
  assert.equal(pa.settle_done, false);

  for (const id of Object.values(a.ids)) {
    await rpc(db, 'mark_paid', { p_code: a.code, p_admin_secret: a.admin_secret, p_passenger_id: id });
  }
  res = await rpc(db, 'list_trips', { p_secret: driver_secret });
  assert.equal(res.trips.find((x) => x.code === a.code).settle_done, true, 'records can be deleted from now on');
  res = await rpc(db, 'list_trips', { p_secret: vehicle_secret, p_limit: 1, p_before: pb.date });
  assert.deepEqual(res.trips.map((x) => x.code), [a.code], 'paging back');
  await assert.rejects(rpc(db, 'list_trips', { p_secret: 'someone-else' }), /not_allowed/);
});

test('L6: a new folder chip can be made first and the trip stacked into it', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  await assert.rejects(rpc(db, 'create_folder', { p_secret: vehicle_secret, p_name: ' ' }), /name_required/);
  await assert.rejects(rpc(db, 'create_folder', { p_secret: 'someone-else', p_name: '동아리 MT' }), /not_allowed/);
  const folder = await rpc(db, 'create_folder', { p_secret: vehicle_secret, p_name: '동아리 MT' });
  assert.match(folder.code, /^[2-9A-HJ-NP-Z]{8}$/);
  const listed = await rpc(db, 'list_folders', { p_secret: vehicle_secret });
  assert.equal(listed[0].name, '동아리 MT');
  assert.equal(listed[0].trips, 0);

  const t = await ride(db, vehicle_secret, [[1, '태호']], { 태호: 1500 });
  const chosen = await rpc(db, 'choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'folder',
                                                      p_folder_code: folder.code });
  assert.equal(chosen.folder.code, folder.code);
  assert.equal((await rpc(db, 'list_folders', { p_secret: vehicle_secret }))[0].trips, 1);

  // a car nobody has linked yet can make one too
  const lone = await rpc(db, 'create_vehicle_link', {});
  const own = await rpc(db, 'create_folder', { p_secret: lone.vehicle_secret, p_name: '혼자 카풀' });
  assert.equal((await rpc(db, 'list_folders', { p_secret: lone.vehicle_secret }))[0].code, own.code);
  assert.equal((await rpc(db, 'get_vehicle_trips', { p_vehicle_secret: lone.vehicle_secret })).linked, false,
               'a car-only account does not count as linked');
  assert.equal((await rpc(db, 'get_vehicle_trips', { p_vehicle_secret: vehicle_secret })).linked, true);
});

test('L1-3: a favourite shows its places on the car, and the driver\'s own places can be fixed', async () => {
  const db = await freshDb();
  const { vehicle_secret, driver_secret } = await driverWithCar(db);
  const first = await rpc(db, 'create_trip', { p_title: '동아리 MT 가는 길', p_vehicle_secret: vehicle_secret,
                                              p_origin: place('응암동'), p_destination: place('가평 MT 숙소') });
  await rpc(db, 'join_trip', { p_code: first.code, p_device_key: device(1), p_name: '태호',
                               p_pickup: place('신촌역 2번 출구'), p_dropoff: place('가평역') });
  const id = (await admin(db, first)).passengers[0].id;
  await rpc(db, 'post_settlement', { p_code: first.code, p_admin_secret: first.admin_secret,
                                     p_payload: { total: 9000, shares: [{ passenger_id: id, name: '태호', amount: 4000 }] } });
  const { carpool_code } = await rpc(db, 'save_carpool', { p_code: first.code, p_admin_secret: first.admin_secret });

  const cars = (await rpc(db, 'get_vehicle_trips', { p_vehicle_secret: vehicle_secret })).carpools;
  assert.equal(cars[0].code, carpool_code);
  assert.equal(cars[0].members, 1);
  const fav = await rpc(db, 'get_carpool_admin', { p_carpool_code: carpool_code, p_secret: vehicle_secret });
  assert.equal(fav.title, '동아리 MT 가는 길');
  assert.equal(fav.origin.label, '응암동');
  assert.equal(fav.members[0].pickup.label, '신촌역 2번 출구');
  assert.ok(fav.members[0].pickup.lat, 'the car needs coordinates for its waypoints');
  await assert.rejects(rpc(db, 'get_carpool_admin', { p_carpool_code: carpool_code, p_secret: 'someone-else' }),
                       /not_allowed/);

  await assert.rejects(rpc(db, 'update_carpool', { p_carpool_code: carpool_code, p_secret: vehicle_secret,
                                                   p_origin: { label: '불광동' } }), /place_needs_location/);
  const fixed = await rpc(db, 'update_carpool', { p_carpool_code: carpool_code, p_secret: driver_secret,
                                                  p_origin: place('불광동') });
  assert.equal(fixed.origin.label, '불광동');
  assert.equal(fixed.destination.label, '가평 MT 숙소', 'what is not sent stays');
  const next = await rpc(db, 'recreate_trip', { p_carpool_code: carpool_code, p_vehicle_secret: vehicle_secret });
  assert.equal((await admin(db, next)).origin.label, '불광동', 'the next trip starts from the fixed place');
});

test('a car takes up to 4 passengers, 5 people with the driver', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  for (const seats of [0, 5, 8]) {
    await assert.rejects(rpc(db, 'create_trip', { p_title: 'MT', p_vehicle_secret: vehicle_secret, p_seats: seats }),
                         /bad_seats/);
  }
  const t = await rpc(db, 'create_trip', { p_title: 'MT', p_vehicle_secret: vehicle_secret, p_seats: 4 });
  for (const n of [1, 2, 3, 4]) {
    await rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(n), p_name: `동승자${n}`, p_dropoff: place('가평') });
  }
  await assert.rejects(rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(5), p_name: '다섯째',
                                              p_dropoff: place('가평') }), /trip_full/);
  await assert.rejects(db.query(`update public.trips set seats = 6 where code = $1`, [t.code]), /check/);
});

test('L3: the driver\'s own stops join the waypoints, and passengers see them only as a stop', async () => {
  const db = await freshDb();
  const { vehicle_secret } = await driverWithCar(db);
  const t = await rpc(db, 'create_trip', { p_title: '퇴근', p_vehicle_secret: vehicle_secret });
  await rpc(db, 'join_trip', { p_code: t.code, p_device_key: device(1), p_name: '태호',
                               p_pickup: place('신촌역'), p_dropoff: place('연희동') });
  const set = (stops) => rpc(db, 'set_driver_stops', { p_code: t.code, p_admin_secret: vehicle_secret, p_stops: stops });

  // from the navi favourites and recent destinations
  let a = await set([{ label: '거래처', address: '서울 중구', lat: 37.56, lng: 126.98 }, place('세탁소')]);
  assert.deepEqual(a.driver_stops.map((x) => x.label), ['거래처', '세탁소']);
  assert.ok(a.driver_stops.every((x) => x.id));
  // a fixed order: pickups, drop-offs, then the driver's stops in their order
  assert.deepEqual(a.waypoints.map((w) => w.kind), ['pickup', 'dropoff', 'driver_stop', 'driver_stop']);
  assert.deepEqual(a.waypoints.filter((w) => w.kind === 'driver_stop').map((w) => w.label), ['거래처', '세탁소']);
  assert.equal(a.waypoints.find((w) => w.kind === 'pickup').label, '신촌역');
  assert.ok(a.waypoints.filter((w) => w.kind === 'driver_stop').every((w) => w.lat && w.stop_id));

  // reorder and remove are the same call; ids stay
  const [first, second] = a.driver_stops;
  a = await set([second, first]);
  assert.deepEqual(a.driver_stops.map((x) => x.id), [second.id, first.id]);
  a = await set([first]);
  assert.deepEqual(a.driver_stops.map((x) => x.label), ['거래처']);
  await assert.rejects(set([{ label: '어딘가' }]), /place_needs_location/);
  await assert.rejects(set(Array.from({ length: 6 }, (_, i) => place(`곳${i}`))), /too_many_stops/);

  // the car's course: passengers see "들를 곳" without where it is
  await rpc(db, 'set_trip_status', { p_code: t.code, p_admin_secret: t.admin_secret, p_status: 'in_progress' });
  await rpc(db, 'update_progress', { p_code: t.code, p_admin_secret: t.admin_secret, p_progress: { stops: [
    { kind: 'start', label: '회사', state: 'done' },
    { kind: 'driver_stop', label: '거래처', eta_min: 5, state: 'now' },
    { kind: 'pickup', passenger_id: (await admin(db, t)).passengers[0].id, label: '신촌역', eta_min: 12, state: 'next' },
  ] } });
  const v = await rpc(db, 'get_trip', { p_code: t.code, p_device_key: device(1) });
  const stop = v.progress.stops.find((s) => s.kind === 'other_stop');
  assert.deepEqual(stop, { kind: 'other_stop', eta_min: 5, state: 'now' });
  assert.ok(!JSON.stringify(v).includes('거래처'));

  // gone with the other places a day after the trip ends
  const id = (await admin(db, t)).passengers[0].id;
  await rpc(db, 'post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret,
                                     p_payload: { total: 5000, shares: [{ passenger_id: id, name: '태호', amount: 1500 },
                                                                        { passenger_id: null, name: '지훈', role: 'driver', amount: 3500 }] } });
  await assert.rejects(set([place('세탁소')]), /trip_closed/);
  await db.query(`update public.trips set ended_at = now() - interval '25 hours' where code = $1`, [t.code]);
  await rpc(db, 'purge_old_locations');
  assert.deepEqual((await admin(db, t)).driver_stops, []);
});
