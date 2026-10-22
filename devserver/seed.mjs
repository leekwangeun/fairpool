// Demo data for the car app and the web: 지훈's car with an account, the 출근 카풀 favourite with three
// trips stacked in its folder, and the 동아리 MT trip ready to go. Secrets go to data/seed.json.
import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { callRpc, defaultDataDir, openDb } from './db.mjs';

const place = (label, area, lat, lng) => ({ label, area, lat, lng });
const P = {
  home: place('응암동 지훈 집', '은평구 응암동', 37.5986, 126.9155),
  office: place('광화문 회사', '종로구 세종로', 37.5714, 126.9768),
  bus: place('불광역 버스정류장', '은평구 불광동', 37.6105, 126.9297),
  sinchon: place('신촌역 2번 출구', '서대문구 창천동', 37.5552, 126.9369),
  yeonhui: place('연희동 주민센터', '서대문구 연희동', 37.5682, 126.9312),
  mangwon: place('망원역', '마포구 망원동', 37.5560, 126.9101),
  mt: place('가평 MT 숙소', '가평군 가평읍', 37.8315, 127.5096),
};

// device keys of the seeded passengers, so a browser can open their pages (localStorage fairpool.deviceKey)
export const devices = {
  수진: 'seed-device-sujin-000000000000',
  민호: 'seed-device-minho-000000000000',
  태호: 'seed-device-taeho-000000000000',
  서연: 'seed-device-seoyeon-0000000000',
  민지: 'seed-device-minji-000000000000',
};

export async function seed(db) {
  const rpc = (fn, args) => callRpc(db, fn, args);
  const { driver_secret } = await rpc('create_driver', { p_display_name: '지훈' });
  const car = await rpc('create_vehicle_link', {});
  await rpc('claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: driver_secret });
  const vehicle_secret = car.vehicle_secret;
  await rpc('set_driver_info', { p_vehicle_secret: vehicle_secret, p_name: '지훈', p_phone4: '1234' });
  await rpc('set_bank_account', { p_vehicle_secret: vehicle_secret, p_consent: true, p_bank_name: '카카오뱅크',
                                  p_bank_account: '3333-01-2345678', p_account_holder: '지훈' });

  // 출근 카풀: 수진 and 민호, three mornings stacked into the folder (요청 전)
  const ride = async (trip) => {
    await rpc('set_trip_status', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_status: 'in_progress' });
    const ps = (await rpc('get_trip_admin', { p_code: trip.code, p_admin_secret: trip.admin_secret })).passengers;
    for (const p of ps) {
      await rpc('mark_passenger', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_passenger_id: p.id,
                                    p_boarded: true, p_alighted: true });
    }
    const id = (name) => ps.find((p) => p.name === name).id;
    const fuel = { distance_km: 14.2, efficiency_km_l: 12.5, amount: 1940 };
    await rpc('post_settlement', { p_code: trip.code, p_admin_secret: trip.admin_secret, p_payload: {
      rule: 'shapley', include_driver: true, total: fuel.amount + 6000, fuel, toll: 6000,
      shares: [
        { passenger_id: id('수진'), name: '수진', amount: 2430, km: 9.8, from: '은평구 불광동', to: '종로구 세종로', alone: 3900 },
        { passenger_id: id('민호'), name: '민호', amount: 1780, km: 7.1, from: '서대문구 연희동', to: '종로구 세종로', alone: 3200 },
        { passenger_id: null, name: '지훈', role: 'driver', amount: fuel.amount + 6000 - 2430 - 1780 },
      ] } });
  };
  const first = await rpc('create_trip', { p_title: '출근 카풀', p_origin: P.home, p_destination: P.office,
                                           p_seats: 2, p_vehicle_secret: vehicle_secret });
  await rpc('join_trip', { p_code: first.code, p_device_key: devices.수진, p_name: '수진', p_phone4: '1111',
                           p_pickup: P.bus, p_dropoff: P.office });
  await rpc('join_trip', { p_code: first.code, p_device_key: devices.민호, p_name: '민호', p_phone4: '2222',
                           p_pickup: P.yeonhui, p_dropoff: P.office });
  await ride(first);
  const folder = (await rpc('choose_settlement', { p_code: first.code, p_admin_secret: first.admin_secret,
                                                   p_mode: 'folder', p_new_folder: '출근 카풀' })).folder.code;
  const { carpool_code } = await rpc('save_carpool', { p_code: first.code, p_admin_secret: first.admin_secret });
  const commutes = [first];
  for (let i = 0; i < 2; i++) {
    const t = await rpc('recreate_trip', { p_carpool_code: carpool_code, p_vehicle_secret: vehicle_secret });
    await ride(t);
    await rpc('choose_settlement', { p_code: t.code, p_admin_secret: t.admin_secret, p_mode: 'folder', p_folder_code: folder });
    commutes.push(t);
  }

  // 동아리 MT 귀갓길: 태호, 서연, 민지 joined, ready to leave (L2, L3)
  const mt = await rpc('create_trip', { p_title: '동아리 MT 귀갓길', p_origin: P.mt, p_destination: P.home,
                                        p_seats: 3, p_vehicle_secret: vehicle_secret });
  await rpc('join_trip', { p_code: mt.code, p_device_key: devices.태호, p_name: '태호', p_phone4: '5678',
                           p_pickup: P.sinchon, p_dropoff: P.yeonhui });
  await rpc('join_trip', { p_code: mt.code, p_device_key: devices.서연, p_name: '서연', p_phone4: '3333',
                           p_dropoff: P.yeonhui });
  await rpc('join_trip', { p_code: mt.code, p_device_key: devices.민지, p_name: '민지', p_phone4: '4444',
                           p_dropoff: P.mangwon });

  return {
    note: '로컬 개발 서버의 시연 데이터입니다. 실제 서비스 값이 아니에요.',
    vehicle_secret,
    driver_secret,
    driver: { name: '지훈', phone4: '1234' },
    carpool_code,
    folder_code: folder,
    trips: {
      commute: commutes.map((t) => ({ code: t.code, admin_secret: t.admin_secret })),
      mt: { code: mt.code, admin_secret: mt.admin_secret },
    },
    devices,
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
    console.log(`  출근 카풀 폴더 ${info.folder_code}, 동아리 MT 운행 ${info.trips.mt.code}`);
  }
}
