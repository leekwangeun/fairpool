// The dev server answers like Supabase's PostgREST: POST /rest/v1/rpc/<fn> with named JSON arguments.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { start } from '../server.mjs';
import { callRpc, openDb } from '../db.mjs';
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
    assert.equal(view.body.origin, '집');
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

test('the demo data gives the car a linked driver, a favourite with a folder and a trip ready to go', async () => {
  const db = await openDb(null);
  const info = await seed(db);
  const car = await callRpc(db, 'get_vehicle_trips', { p_vehicle_secret: info.vehicle_secret });
  assert.equal(car.linked, true);
  assert.deepEqual(car.trips.map((t) => t.code), [info.trips.mt.code]);
  assert.equal(car.trips[0].passengers, 3);
  assert.equal(car.carpools[0].code, info.carpool_code);
  const folders = await callRpc(db, 'list_folders', { p_secret: info.vehicle_secret });
  assert.deepEqual([folders[0].name, folders[0].status, folders[0].trips], ['출근 카풀', 'open', 3]);
  assert.equal((await callRpc(db, 'get_bank_account', { p_vehicle_secret: info.vehicle_secret })).account,
               '3333-01-2345678');
  const sujin = await callRpc(db, 'get_trip', { p_code: info.trips.commute[2].code, p_device_key: info.devices.수진 });
  assert.equal(sujin.folder.rides.length, 3);
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
