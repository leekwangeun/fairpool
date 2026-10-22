// Demo data that matches the demo video script and the plan's calculation examples:
// - 지훈's car with driver info and an account (페어은행)
// - the 출근 카풀 folder: 16 October mornings with different riders (plan 3.4 example 3), not requested yet
// - favourites "출근 카풀" and "동아리 MT 가는 길"
// - "동아리 MT 귀갓길" ready to leave (plan 3.4 example 1, scenario A); its expected settlement is in seed.json
// Secrets and codes go to data/seed.json.
import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { callRpc, defaultDataDir, openDb } from './db.mjs';

const place = (label, area, lat, lng) => ({ label, area, lat, lng });
// The MT trip uses the points the plan's distance table was measured with (OSRM, plan 3.4 example 1).
const P = {
  home: place('응암동 지훈 집', '은평구 응암동', 37.59870, 126.91757),
  office: place('광화문 회사', '종로구 세종로', 37.5714, 126.9768),
  sujin: place('불광역 버스정류장', '은평구 불광동', 37.6105, 126.9297),
  minho: place('역촌역', '은평구 역촌동', 37.6060, 126.9226),
  jieun: place('녹번역', '은평구 녹번동', 37.6008, 126.9358),
  mt: place('가평 MT 숙소', '가평군 가평읍', 37.81518, 127.51020),
  sinchon: place('신촌역 2번 출구', '서대문구 창천동', 37.55534, 126.93662),
  mangwon: place('망원역', '마포구 망원동', 37.55591, 126.91025),
  yeonnam: place('연남동 공원', '마포구 연남동', 37.56245, 126.92339),
  yeonhui: place('연희동 주민센터', '서대문구 연희동', 37.56679, 126.93121),
};

// device keys and last 4 digits of the demo passengers; a browser opens their pages with the key in
// localStorage fairpool.deviceKey
export const devices = {
  수진: 'seed-device-sujin-000000000000',
  민호: 'seed-device-minho-000000000000',
  지은: 'seed-device-jieun-000000000000',
  태호: 'seed-device-taeho-000000000000',
  서연: 'seed-device-seoyeon-0000000000',
  민지: 'seed-device-minji-000000000000',
};
const phone4 = { 수진: '1111', 민호: '2222', 지은: '3333', 태호: '5678', 서연: '4444', 민지: '6666' };

// plan 3.4 example 3: who rides, actual distance, total, each share (지훈 = driver), km each rider was in the car
const COMMUTE = {
  A: { km: 46, total: 5590, shares: { 수진: 2430, 지훈: 3160 }, rode: { 수진: 34 } },
  B: { km: 52, total: 6310, shares: { 수진: 1900, 민호: 1780, 지훈: 2630 }, rode: { 수진: 40, 민호: 32 } },
  C: { km: 56, total: 6800, shares: { 수진: 1670, 민호: 1300, 지은: 1430, 지훈: 2400 }, rode: { 수진: 44, 민호: 36, 지은: 31 } },
  D: { km: 50, total: 6070, shares: { 민호: 1600, 지은: 1660, 지훈: 2810 }, rode: { 민호: 36, 지은: 31 } },
};
// the 16 weekday mornings of October up to today: A x6, B x5, C x3, D x2
const COMMUTE_DAYS = [
  ['2026-10-01', 'A'], ['2026-10-02', 'A'], ['2026-10-05', 'A'], ['2026-10-06', 'A'], ['2026-10-07', 'A'], ['2026-10-08', 'A'],
  ['2026-10-09', 'B'], ['2026-10-12', 'B'], ['2026-10-13', 'B'], ['2026-10-14', 'B'], ['2026-10-15', 'B'],
  ['2026-10-16', 'C'], ['2026-10-19', 'C'], ['2026-10-20', 'C'],
  ['2026-10-21', 'D'], ['2026-10-22', 'D'],
];
const RIDER_PLACE = { 수진: P.sujin, 민호: P.minho, 지은: P.jieun };

// plan 3.4 example 1 (scenario A): what the car should compute for the MT 귀갓길 trip
const MT_RETURN_EXPECTED = {
  total: 15360,
  toll: 3000,
  fuel: { distance_km: 80.0, efficiency_km_l: 11.0, price_per_l: 1700, amount: 12360 },
  order: ['신촌역 2번 출구(태호 탑승)', '망원역(민지 하차)', '연남동 공원(서연 하차)', '연희동 주민센터(태호 하차)', '응암동 지훈 집'],
  odometer_km: [69.6, 72.4, 74.3, 75.3, 80.0],
  seats: { 서연: '조수석', 민지: '뒷좌석 오른쪽', 태호: '뒷좌석 왼쪽(신촌역에서 탑승)' },
  shares: [
    { name: '민지', amount: 5110, km: 72.4, shapley_km: 26.6, from: '가평군 가평읍', to: '마포구 망원동', alone: 14170 },
    { name: '서연', amount: 4820, km: 74.3, shapley_km: 24.7, from: '가평군 가평읍', to: '마포구 연남동', alone: 14070 },
    { name: '태호', amount: 260, km: 5.7, shapley_km: 1.7, from: '서대문구 창천동', to: '서대문구 연희동', alone: 310 },
    { name: '지훈', role: 'driver', amount: 5170, km: 80.0, shapley_km: 27.0, alone: 13740 },
  ],
};

export async function seed(db) {
  const rpc = (fn, args) => callRpc(db, fn, args);
  const { driver_secret } = await rpc('create_driver', { p_display_name: '지훈' });
  const car = await rpc('create_vehicle_link', {});
  await rpc('claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  const vehicle_secret = car.vehicle_secret;
  await rpc('set_driver_info', { p_vehicle_secret: vehicle_secret, p_name: '지훈', p_phone4: '1234' });
  await rpc('set_bank_account', { p_vehicle_secret: vehicle_secret, p_consent: true, p_bank_name: '페어은행',
                                  p_bank_account: '1002-345-127890', p_account_holder: '지훈' });

  // one finished trip, dated in the past: riders join, ride and get off, then the car posts the settlement
  async function finished({ title, origin, destination, riders, shares, rode, fuel, toll = 0, start, end, alone = {} }) {
    const t = await rpc('create_trip', { p_title: title, p_origin: origin, p_destination: destination,
                                         p_seats: Math.max(riders.length, 1), p_vehicle_secret: vehicle_secret });
    for (const r of riders) {
      await rpc('join_trip', { p_code: t.code, p_device_key: devices[r.name], p_name: r.name, p_phone4: phone4[r.name],
                               p_pickup: r.pickup, p_dropoff: r.dropoff });
    }
    await rpc('set_trip_status', { p_code: t.code, p_admin_secret: t.admin_secret, p_status: 'in_progress' });
    const ps = (await rpc('get_trip_admin', { p_code: t.code, p_admin_secret: t.admin_secret })).passengers;
    for (const p of ps) {
      await rpc('mark_passenger', { p_code: t.code, p_admin_secret: t.admin_secret, p_passenger_id: p.id,
                                    p_boarded: true, p_alighted: true });
    }
    const id = (name) => ps.find((p) => p.name === name).id;
    const list = riders.map((r) => ({ passenger_id: id(r.name), name: r.name, role: 'passenger', amount: shares[r.name],
                                      km: rode[r.name], from: (r.pickup ?? origin).area, to: r.dropoff.area,
                                      ...(alone[r.name] ? { alone: alone[r.name] } : {}) }));
    list.push({ passenger_id: null, name: '지훈', role: 'driver', amount: shares.지훈 });
    await rpc('post_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_payload: {
      rule: 'shapley', include_driver: true, total: Object.values(shares).reduce((a, b) => a + b, 0), fuel, toll, shares: list } });
    await db.query(`update public.trips set created_at = $2, started_at = $2, ended_at = $3 where code = $1`, [t.code, start, end]);
    await db.query(`update public.settlements s set settled_at = $2 from public.trips t where t.id = s.trip_id and t.code = $1`,
                   [t.code, end]);
    await db.query(`update public.trip_passengers p set joined_at = $2, boarded_at = $2, alighted_at = $3
                    from public.trips t where t.id = p.trip_id and t.code = $1`, [t.code, start, end]);
    return t;
  }

  // 출근 카풀: 16 mornings stacked in one folder (요청 전); the favourite is saved on the first day all three rode
  let folder = null;
  let commuteCarpool = null;
  const commutes = [];
  for (const [day, kind] of COMMUTE_DAYS) {
    const c = COMMUTE[kind];
    const riders = Object.keys(c.rode).map((name) => ({ name, pickup: RIDER_PLACE[name], dropoff: P.office }));
    const t = await finished({
      title: '출근 카풀', origin: P.home, destination: P.office, riders, shares: c.shares, rode: c.rode,
      fuel: { distance_km: c.km, efficiency_km_l: 14, amount: c.total }, start: `${day}T08:20:00+09:00`, end: `${day}T09:10:00+09:00`,
    });
    const chosen = await rpc('choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'folder',
                                                    ...(folder ? { p_folder_code: folder } : { p_new_folder: '출근 카풀' }) });
    folder = chosen.folder.code;
    if (kind === 'C' && !commuteCarpool) {
      commuteCarpool = (await rpc('save_carpool', { p_code: t.code, p_admin_secret: t.admin_secret })).carpool_code;
    }
    commutes.push({ day, kind, code: t.code });
  }

  // 동아리 MT 가는 길 (last Saturday): settled right away and paid, then kept as a favourite
  const goingShares = { 민지: 3880, 서연: 3760, 태호: 3690, 지훈: 4190 };
  const going = await finished({
    title: '동아리 MT 가는 길', origin: P.home, destination: P.mt,
    riders: [{ name: '태호', pickup: P.sinchon, dropoff: P.mt }, { name: '서연', pickup: P.yeonnam, dropoff: P.mt },
             { name: '민지', pickup: P.mangwon, dropoff: P.mt }],
    shares: goingShares, rode: { 태호: 78.2, 서연: 76.9, 민지: 75.8 }, toll: 3000,
    fuel: { distance_km: 81.0, efficiency_km_l: 11.0, amount: 12520 },
    start: '2026-10-17T10:00:00+09:00', end: '2026-10-17T11:40:00+09:00',
  });
  await rpc('choose_settlement', { p_code: going.code, p_admin_secret: going.admin_secret, p_mode: 'direct' });
  for (const p of (await rpc('get_trip_admin', { p_code: going.code, p_admin_secret: going.admin_secret })).passengers) {
    await rpc('mark_paid', { p_code: going.code, p_admin_secret: going.admin_secret, p_passenger_id: p.id });
  }
  const mtCarpool = (await rpc('save_carpool', { p_code: going.code, p_admin_secret: going.admin_secret })).carpool_code;

  // 동아리 MT 귀갓길 (today, ready to leave): 민지 and 서연 get on at the lodge, 태호 at 신촌역
  const mt = await rpc('create_trip', { p_title: '동아리 MT 귀갓길', p_origin: P.mt, p_destination: P.home,
                                        p_seats: 3, p_vehicle_secret: vehicle_secret });
  await rpc('join_trip', { p_code: mt.code, p_device_key: devices.민지, p_name: '민지', p_phone4: phone4.민지,
                           p_dropoff: P.mangwon });
  await rpc('join_trip', { p_code: mt.code, p_device_key: devices.서연, p_name: '서연', p_phone4: phone4.서연,
                           p_dropoff: P.yeonnam });
  await rpc('join_trip', { p_code: mt.code, p_device_key: devices.태호, p_name: '태호', p_phone4: phone4.태호,
                           p_pickup: P.sinchon, p_dropoff: P.yeonhui });

  return {
    note: '로컬 개발 서버의 시연 데이터입니다. 실제 서비스 값이 아니에요.',
    vehicle_secret,
    driver_secret,
    driver: { name: '지훈', phone4: '1234', bank: '페어은행 1002-345-127890 (지훈)' },
    carpools: { commute: commuteCarpool, mt_going: mtCarpool },
    folder_code: folder,
    trips: {
      mt_return: { code: mt.code, admin_secret: mt.admin_secret },
      mt_going: { code: going.code },
      commute: commutes,
    },
    devices,
    phone4,
    expected: {
      mt_return: MT_RETURN_EXPECTED,
      commute_folder: {
        status: '요청 전', trips: 16, km: 804, total: 97630, receivable: 52700, driver: 44930,
        people: { 수진: { trips: 14, amount: 29090 }, 민호: { trips: 10, amount: 16000 }, 지은: { trips: 5, amount: 7610 } },
      },
    },
  };
}

export async function seedInto(dataDir = defaultDataDir) {
  const db = await openDb(dataDir);
  const info = await seed(db);
  await db.close();
  writeFileSync(join(dataDir, 'seed.json'), JSON.stringify(info, null, 2));
  return info;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  if (existsSync(join(defaultDataDir, 'seed.json')) && !process.argv.includes('--force')) {
    console.log('이미 시연 데이터가 있어요(data/seed.json). 처음부터 다시 만들려면 npm run reset');
  } else {
    const info = await seedInto();
    console.log(`시연 데이터를 넣었어요. 비밀값과 코드: ${join(defaultDataDir, 'seed.json')}`);
    console.log(`  출근 카풀 폴더 ${info.folder_code}, 동아리 MT 귀갓길 ${info.trips.mt_return.code}`);
  }
}
