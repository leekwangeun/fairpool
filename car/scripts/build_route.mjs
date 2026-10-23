// 시나리오 A의 실제 도로 경로를 공개 OSRM에서 받아 시연용 주행 데이터(route_mt_return.json, .gpx)를 만든다.
// 각 점에 누적 거리(km)를 붙이고, 정차 지점이 기획서의 누적 주행거리(69.6, 72.4, 74.3, 75.3, 80.0km)에
// 정확히 오도록 구간마다 비율을 맞춘다. 사용: node car/scripts/build_route.mjs
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const stops = [
  { name: '가평 MT 숙소', lng: 127.51020, lat: 37.81518, km: 0 },
  { name: '신촌역 2번 출구', lng: 126.93662, lat: 37.55534, km: 69.6 },
  { name: '망원역', lng: 126.91025, lat: 37.55591, km: 72.4 },
  { name: '연남동 공원', lng: 126.92339, lat: 37.56245, km: 74.3 },
  { name: '연희동 주민센터', lng: 126.93121, lat: 37.56679, km: 75.3 },
  { name: '응암동 지훈 집', lng: 126.91757, lat: 37.59870, km: 80.0 },
];

const coords = stops.map((s) => `${s.lng.toFixed(5)},${s.lat.toFixed(5)}`).join(';');
const url = `https://router.project-osrm.org/route/v1/driving/${coords}?overview=false&steps=true&geometries=geojson`;
const res = await (await fetch(url, { headers: { 'User-Agent': 'FairPool-demo' } })).json();
if (res.code !== 'Ok') throw new Error('OSRM: ' + res.code);

const R = 6371;
const hav = (a, b) => {
  const dLat = ((b[1] - a[1]) * Math.PI) / 180, dLng = ((b[0] - a[0]) * Math.PI) / 180;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos((a[1] * Math.PI) / 180) * Math.cos((b[1] * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
};

const points = [];
res.routes[0].legs.forEach((leg, i) => {
  const line = leg.steps.flatMap((st) => st.geometry.coordinates);
  const dedup = line.filter((p, k) => k === 0 || p[0] !== line[k - 1][0] || p[1] !== line[k - 1][1]);
  const lens = dedup.map((p, k) => (k === 0 ? 0 : hav(dedup[k - 1], p)));
  const legKm = lens.reduce((a, b) => a + b, 0);
  const from = stops[i].km, to = stops[i + 1].km;
  let acc = 0;
  dedup.forEach((p, k) => {
    acc += lens[k];
    if (i > 0 && k === 0) return; // the joint point is already the previous leg's last point
    points.push({ lng: +p[0].toFixed(6), lat: +p[1].toFixed(6), km: +(from + ((to - from) * acc) / legKm).toFixed(3), leg: i });
  });
  console.log(`${stops[i].name} → ${stops[i + 1].name}: OSRM ${(leg.distance / 1000).toFixed(1)}km, 점 ${dedup.length}개`);
});

const here = dirname(fileURLToPath(import.meta.url));
writeFileSync(join(here, 'route_mt_return.json'), JSON.stringify({ stops, points }, null, 0));
const gpx = ['<?xml version="1.0" encoding="UTF-8"?>', '<gpx version="1.1" creator="FairPool" xmlns="http://www.topografix.com/GPX/1/1">',
  ...stops.map((s) => `<wpt lat="${s.lat}" lon="${s.lng}"><name>${s.name}</name></wpt>`),
  '<trk><name>동아리 MT 귀갓길</name><trkseg>', ...points.map((p) => `<trkpt lat="${p.lat}" lon="${p.lng}"></trkpt>`), '</trkseg></trk></gpx>'];
writeFileSync(join(here, 'route_mt_return.gpx'), gpx.join('\n'));
console.log(`점 ${points.length}개, 마지막 ${points.at(-1).km}km`);
