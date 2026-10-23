// 시연 시나리오 A "동아리 MT 귀갓길" 실감 버전.
// 에뮬레이터 GPS로 실제 도로 경로(route_mt_return.json)를 재생해 차를 움직이고, 정차 지점에 닿으면
// Virtual Console로 기어 P, 문, 좌석을 바꾼다. 페어풀 확인 카드 → 다음 경로 → 내비 → 다시 이동이 이어진다.
// 가평 → 신촌 고속도로 구간은 빨리 감고, 서울 구간은 천천히 보여 준다(전체 약 4분).
//
// 준비: 에뮬레이터, devserver, 차 앱(설정의 차 비밀값 = seed.json vehicle_secret), 준비 중인 시드 운행
// 사용: node car/scripts/demo_drive.mjs [준비 중인 운행 줄 번호(기본 1)]
//       RECORD=0 이면 화면 녹화를 하지 않는다. SHOTS=폴더 로 캡처 위치를 바꾼다.
import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ADB = process.env.ADB || join(process.env.LOCALAPPDATA || '', 'Android/Sdk/platform-tools/adb.exe');
const VC = 'http://127.0.0.1:8088';
const ROW = Number(process.argv[2] || 1);
const SHOTS = process.env.SHOTS || './demo-shots';
const RECORD = process.env.RECORD !== '0';
const { points } = JSON.parse(readFileSync(join(here, 'route_mt_return.json'), 'utf8'));
mkdirSync(SHOTS, { recursive: true });

const t0 = Date.now();
const say = (m) => console.log(`[${((Date.now() - t0) / 1000).toFixed(0).padStart(3)}s] ${m}`);
const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));
const adb = (...a) => execFileSync(ADB, a.map(String), { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
/** One Virtual Console write; it is refused while no browser session is alive, so retry until it lands. */
async function put(path) {
  for (let i = 0; i < 8; i++) {
    const text = await fetch(`${VC}/vehicle/${path}`, { method: 'PUT' }).then((r) => r.text()).catch(() => 'emulator_inactive');
    if (!text.includes('emulator_inactive')) return text;
    await fetch(`${VC}/browser/session?client=fairpool-drive`, { method: 'POST' }).catch(() => {});
    await new Promise((ok) => setTimeout(ok, 400));
  }
  throw new Error(`Virtual Console refused ${path}`);
}
const tap = (x, y) => adb('shell', 'input', 'tap', x, y);
const shot = (name) => writeFileSync(join(SHOTS, `${name}.png`), execFileSync(ADB, ['exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 }));
const front = async () => { adb('shell', 'am', 'start', '--user', '10', '-n', 'kr.fairpool.car/.MainActivity'); await sleep(2500); };
const geo = (p) => adb('emu', 'geo', 'fix', p.lng.toFixed(6), p.lat.toFixed(6));
const seat = (area, v) => put(`seat?kind=occupied&area=${area}&value=${v}`);
const door = (d, v) => put(`body?${d}=${v}`);
const gear = (g) => put(`gear?gear=${g}`);
const speed = (k) => put(`controls?speed=${Math.round(k)}&accelerator=${k > 0 ? 25 : 0}&brake=${k > 0 ? 0 : 40}`);
let BASE = 0;
const odo = (km) => put(`odometer?traveled_distance_km=${(BASE + km).toFixed(1)}`);

// Virtual Console only takes writes while a browser session is alive
adb('forward', 'tcp:8088', 'tcp:8088');
const keep = setInterval(() => fetch(`${VC}/browser/session?client=fairpool-drive`, { method: 'POST' }).catch(() => {}), 1000);

// screen recording in segments (screenrecord stops at 3 minutes)
const recordings = [];
let recTimer = null;
function record(i) {
  const file = `/sdcard/fairpool_drive_${i}.mp4`;
  recordings.push(file);
  spawn(ADB, ['shell', 'screenrecord', '--time-limit', '178', '--size', '1920x1080', '--bit-rate', '8000000', file], { stdio: 'ignore' });
  recTimer = setTimeout(() => record(i + 1), 176_000);
}

/** Drives one leg of the route: [ms] between GPS updates, every [stride]-th point, cruising at [kmh]. */
async function drive(leg, { ms, stride, kmh }) {
  const pts = points.filter((p) => p.leg === leg);
  const picked = pts.filter((_, i) => i % stride === 0);
  if (picked.at(-1) !== pts.at(-1)) picked.push(pts.at(-1));
  await gear('Drive');
  for (let i = 0; i < picked.length; i++) {
    const p = picked[i];
    geo(p);
    const f = i / picked.length;
    // pull away, cruise, slow down into the stop
    const v = f < 0.08 ? kmh * (0.4 + (f / 0.08) * 0.6) : f > 0.88 ? kmh * Math.max(0.15, (1 - f) / 0.12) : kmh;
    if (i % 4 === 0) { await odo(p.km); await speed(SPEED ? v : 0); }
    await sleep(ms);
  }
  await odo(pts.at(-1).km);
  await speed(0);
  await gear('Park');
}
// SPEED=0 keeps the speedometer at 0 (the driving restriction then never covers the app)
const SPEED = process.env.SPEED !== '0';

/** At a stop: the door opens, the seat changes, the card shows; then the navi gets the next leg and FairPool comes back. */
async function stop(name, doorName, area, occupied, shotName) {
  await sleep(2000); // stopped at the curb before the door opens
  say(`${name}: ${occupied ? '탑승' : '하차'}`);
  await door(doorName, true);
  await sleep(600);
  await seat(area, occupied);
  await sleep(2500);
  shot(shotName);
  await door(doorName, false);
  await sleep(8500); // the next leg goes to the navi 10 s after the stop; let it come forward
  say('내비에 다음 경로');
  await sleep(4000);
  shot(`${shotName}_navi`);
  await front();
}

async function main() {
  await fetch(`${VC}/browser/session?client=fairpool-drive`, { method: 'POST' });
  await sleep(1500);
  const state = await (await fetch(`${VC}/vehicle/state`)).json();
  BASE = (Math.floor((state.odometer?.traveled_distance_km || 0) / 100) + 1) * 100;
  say(`주행거리 기준 ${BASE}km`);

  say('주차, 좌석 비움, 문 닫힘, 가평 MT 숙소');
  await gear('Park'); await speed(0);
  for (const a of ['passenger', 'rear_left', 'rear_right']) await seat(a, false);
  for (const d of ['door_fr', 'door_rl', 'door_rr']) await door(d, false);
  await odo(0);
  for (let i = 0; i < 3; i++) { geo(points[0]); await sleep(500); }
  adb('shell', 'am', 'force-stop', 'kr.fairpool.car');
  await front(); await sleep(3000);
  if (RECORD) record(1);

  say(`L1 준비 중인 운행 열기 (${ROW}번째 줄)`);
  tap(2140, ROW === 2 ? 460 : 343); await sleep(4000); shot('01_L2_invite');
  tap(1783, 1204); await sleep(2000);

  say('숙소에서 서연(조수석), 민지(뒷좌석 오른쪽) 탑승');
  await door('door_fr', true); await seat('passenger', true); await door('door_fr', false);
  await door('door_rr', true); await seat('rear_right', true); await door('door_rr', false);
  await sleep(1500);

  say('L3 좌석과 들를 곳 순서');
  tap(2266, 1204); await sleep(3500);
  tap(1314, 600); await sleep(1500); shot('02_L3_seats');
  say('경로 보내고 출발 → 내비');
  tap(1692, 1204); await sleep(6000); shot('03_navi_route');
  await front(); await sleep(1500);

  say('가평 → 신촌역 (고속도로, 빨리 감기)');
  await drive(0, { ms: 170, stride: 8, kmh: 100 });
  shot('04_L4_arrive_sinchon');
  await stop('신촌역 2번 출구 69.6km, 태호', 'door_rl', 'rear_left', true, '05_L5_taeho_in');

  say('신촌역 → 망원역');
  await drive(1, { ms: 150, stride: 1, kmh: 35 });
  await stop('망원역 72.4km, 민지', 'door_rr', 'rear_right', false, '06_L5_minji_out');

  say('망원역 → 연남동 공원');
  await drive(2, { ms: 150, stride: 1, kmh: 30 });
  await stop('연남동 공원 74.3km, 서연', 'door_fr', 'passenger', false, '07_L5_seoyeon_out');

  say('연남동 공원 → 연희동 주민센터');
  await drive(3, { ms: 150, stride: 1, kmh: 30 });
  await stop('연희동 주민센터 75.3km, 태호', 'door_rl', 'rear_left', false, '08_L5_taeho_out');

  say('연희동 → 응암동 지훈 집');
  await drive(4, { ms: 130, stride: 2, kmh: 40 });
  await front();
  say('도착, 정산');
  tap(2342, 1204); await sleep(13000); shot('09_L6_settlement');

  say('통행료 3,000원으로 고치기');
  tap(1425, 474); await sleep(2000);
  tap(1692, 696); await sleep(800);
  adb('shell', 'input', 'keycombination', '113', '29'); adb('shell', 'input', 'keyevent', '67');
  adb('shell', 'input', 'text', '3000'); await sleep(800);
  adb('shell', 'input', 'keyevent', '66'); await sleep(12000); shot('10_L6_toll_fixed');

  say('바로 정산으로 확정');
  tap(1711, 1082); await sleep(1000);
  tap(2358, 1204); await sleep(3000); shot('11_done');
}

try {
  await main();
} finally {
  clearInterval(keep);
  if (recTimer) clearTimeout(recTimer);
  if (RECORD) {
    await sleep(1500);
    try { adb('shell', 'pkill', '-INT', 'screenrecord'); } catch {}
    await sleep(3000);
    for (const f of recordings) {
      try { adb('pull', f, join(SHOTS, f.split('/').pop())); say(`녹화 ${f.split('/').pop()}`); } catch {}
    }
  }
  say(`끝. 캡처: ${SHOTS}`);
}
