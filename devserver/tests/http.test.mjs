// The dev server answers like Supabase's PostgREST: POST /rest/v1/rpc/<fn> with named JSON arguments.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { start } from '../server.mjs';
import { applyFunctions, callRpc, openDb } from '../db.mjs';
import { seed } from '../seed.mjs';

async function withServer(fn) {
  const { server, db, port } = await start({ port: 0, host: '127.0.0.1', dataDir: null, log: false });
  const call = async (name, args, init = {}) => {
    const res = await fetch(`http://127.0.0.1:${port}/rest/v1/rpc/${name}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', apikey: 'dev' },
      body: args === undefined ? undefined : typeof args === 'string' ? args : JSON.stringify(args),
      ...init,
    });
    return { status: res.status, headers: res.headers, body: res.status === 204 ? null : await res.json() };
  };
  try {
    await fn(call, port, db);
  } finally {
    server.close();
    await db.close();
  }
}

test('rpc calls take named JSON arguments and return the function result', async () => {
  await withServer(async (call) => {
    const car = await call('create_vehicle_link', {});
    assert.equal(car.status, 200);
    assert.ok(car.body.vehicle_secret && car.body.link_code);
    const trip = await call('create_trip', { p_title: '출근', p_seats: 2, p_vehicle_secret: car.body.vehicle_secret,
                                             p_origin: { label: '집', lat: 37.5, lng: 126.9 } });
    assert.equal(trip.status, 200);
    assert.match(trip.body.code, /^[2-9A-HJ-NP-Z]{8}$/);
    const view = await call('get_trip', { p_code: trip.body.code });
    assert.equal(view.body.seats_left, 2);
    assert.equal(view.body.origin, null, 'the origin is named only to riders who get on there');
    assert.equal(car.headers.get('access-control-allow-origin'), '*');
  });
});

test('errors come back as PostgREST does: 400 with code and message, 404 for unknown functions', async () => {
  await withServer(async (call, port) => {
    const denied = await call('create_trip', { p_title: '출근' });
    assert.equal(denied.status, 400);
    assert.equal(denied.body.code, 'P0001');
    assert.equal(denied.body.message, 'not_allowed');

    // not in the migration's api list: internal helpers and the nightly clean-up
    for (const name of ['purge_old_locations', '_hash', 'no_such_function']) {
      const r = await call(name, {});
      assert.equal(r.status, 404, name);
      assert.equal(r.body.code, 'PGRST202');
    }
    // wrong argument names
    assert.equal((await call('create_trip', { p_title: 'x', p_driver_secret: 'y' })).body.code, 'PGRST202');
    assert.equal((await call('get_trip', '{not json')).status, 400);
    assert.equal((await call('get_trip', [1, 2])).status, 400);

    const pre = await fetch(`http://127.0.0.1:${port}/rest/v1/rpc/get_trip`, {
      method: 'OPTIONS', headers: { Origin: 'http://localhost:5173', 'Access-Control-Request-Method': 'POST' },
    });
    assert.equal(pre.status, 204);
    assert.match(pre.headers.get('access-control-allow-headers'), /apikey/);
    const health = await (await fetch(`http://127.0.0.1:${port}/health`)).json();
    assert.ok(health.functions.includes('create_trip'));
  });
});

test('the demo data matches the plan: the 출근 카풀 folder of example 3 and the MT trip ready to go', async () => {
  const db = await openDb(null);
  const info = await seed(db);
  const car = await callRpc(db, 'get_vehicle_trips', { p_vehicle_secret: info.vehicle_secret });
  assert.equal(car.linked, true);
  assert.deepEqual(car.trips.map((t) => [t.code, t.title, t.passengers]), [[info.trips.mt_return.code, '동아리 MT 귀갓길', 3]]);
  assert.deepEqual(car.carpools.map((c) => c.title).sort(), ['동아리 MT 가는 길', '출근 카풀']);
  assert.equal((await callRpc(db, 'get_bank_account', { p_vehicle_secret: info.vehicle_secret })).bank_name, '페어은행');

  // plan 3.4 example 3: 16 trips, 804km, 97,630원; 수진 14회 29,090 / 민호 10회 16,000 / 지은 5회 7,610 / 지훈 44,930
  const folder = await callRpc(db, 'get_folder_admin', { p_folder_code: info.folder_code, p_secret: info.vehicle_secret });
  assert.equal(folder.requested, null, '요청 전');
  assert.equal(folder.open.trips.length, 16);
  assert.equal(folder.open.trips.reduce((s, t) => s + Number(t.km), 0), 804);
  assert.equal(Number(folder.open.total), 97630);
  assert.equal(Number(folder.open.driver_amount), 44930);
  assert.equal(Number(folder.open.receivable), 52700);
  assert.deepEqual(folder.open.people.map((p) => [p.name, p.trips, Number(p.amount)]).sort(),
                   [['민호', 10, 16000], ['수진', 14, 29090], ['지은', 5, 7610]]);
  // her last ride, 10월 20일 (C)
  const sujin = await callRpc(db, 'get_trip', { p_code: info.trips.commute[13].code, p_device_key: info.devices.수진 });
  assert.equal(sujin.folder.rides.length, 14);
  assert.equal(Number(sujin.folder.my_total), 29090);
  assert.equal(sujin.folder.rides.filter((r) => Number(r.amount) === 2430).length, 6, 'the six mornings alone: 14,580원');

  // the MT 귀갓길 riders as in the video script
  const mt = await callRpc(db, 'get_trip_admin', { p_code: info.trips.mt_return.code, p_admin_secret: info.vehicle_secret });
  assert.equal(mt.origin.label, '가평 MT 숙소');
  const at = Object.fromEntries(mt.passengers.map((p) => [p.name, [p.pickup?.label ?? null, p.dropoff.label]]));
  assert.deepEqual(at, { 민지: [null, '망원역'], 서연: [null, '연남동 공원'], 태호: ['신촌역 2번 출구', '연희동 주민센터'] });
  const e = info.expected.mt_return;
  assert.equal(e.shares.reduce((s, x) => s + x.amount, 0), e.total);
  assert.equal(e.fuel.amount + e.toll, e.total);
  await db.close();
});

test('npm run upgrade can apply the functions again on a database that already has data', async () => {
  const db = await openDb(null);
  const info = await seed(db);
  assert.ok((await applyFunctions(db)) > 50);
  const car = await callRpc(db, 'get_vehicle_trips', { p_vehicle_secret: info.vehicle_secret });
  assert.equal(car.trips[0].code, info.trips.mt_return.code, 'the same secret still opens the same data');
  await db.close();
});

test('the database folder keeps its data across restarts', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'fairpool-dev-'));
  try {
    let db = await openDb(dir);
    const car = await callRpc(db, 'create_vehicle_link', {});
    const trip = await callRpc(db, 'create_trip', { p_title: '남는 운행', p_vehicle_secret: car.vehicle_secret });
    await db.close();
    db = await openDb(dir);
    assert.equal((await callRpc(db, 'get_trip', { p_code: trip.code })).title, '남는 운행');
    await db.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
