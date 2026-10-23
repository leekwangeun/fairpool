// Pleos 에뮬레이터의 `geo fix`는 NMEA 체크섬을 잘못 계산해서(항상 *47) 순정 내비가 위치를 버린다.
// 그래서 체크섬을 바르게 계산한 GPRMC, GPGGA 문장을 만들어 `adb emu geo nmea`로 직접 보낸다.
// 사용: node nmea.mjs <경도> <위도> [속도 km/h] [진행 방향 도]  → adb emu geo nmea 인자 두 줄을 출력
export function sentences(lng, lat, kmh = 0, heading = 0, date = new Date()) {
  const pad = (n, w) => String(Math.floor(n)).padStart(w, '0');
  const hhmmss = pad(date.getUTCHours(), 2) + pad(date.getUTCMinutes(), 2) + pad(date.getUTCSeconds(), 2);
  const ddmmyy = pad(date.getUTCDate(), 2) + pad(date.getUTCMonth() + 1, 2) + pad(date.getUTCFullYear() % 100, 2);
  const dm = (v, degW) => {
    const a = Math.abs(v), d = Math.floor(a), m = (a - d) * 60;
    return pad(d, degW) + m.toFixed(4).padStart(7, '0');
  };
  const la = dm(lat, 2), lo = dm(lng, 3);
  const ns = lat >= 0 ? 'N' : 'S', ew = lng >= 0 ? 'E' : 'W';
  const knots = (kmh / 1.852).toFixed(2), course = heading.toFixed(2);
  const ck = (body) => {
    let c = 0;
    for (const ch of body) c ^= ch.charCodeAt(0);
    return c.toString(16).toUpperCase().padStart(2, '0');
  };
  const rmc = `GPRMC,${hhmmss},A,${la},${ns},${lo},${ew},${knots},${course},${ddmmyy},,`;
  const gga = `GPGGA,${hhmmss},${la},${ns},${lo},${ew},1,08,0.9,30.0,M,0.0,M,,`;
  return [`$${rmc}*${ck(rmc)}`, `$${gga}*${ck(gga)}`];
}

if (process.argv[1] && process.argv[1].endsWith('nmea.mjs')) {
  const [lng, lat, kmh, heading] = process.argv.slice(2).map(Number);
  for (const s of sentences(lng, lat, kmh || 0, heading || 0)) console.log(s);
}
