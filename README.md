# 페어풀(FairPool) : 공정한 카풀 정산

차 안의 Pleos Connect 화면에서 동작하는 카풀 정산 앱입니다. 이 저장소에는 운행 서버(Supabase)와 휴대폰 웹(동승자 페이지, 운전자 준비 페이지)이 들어 있습니다.

## 운행마다 전용 페이지와 QR

운행을 만들면 그 운행만의 코드(예: `SDQ6NH`)가 발급되고, 동승자 페이지 주소 `https://<사이트>/t/SDQ6NH` 와 그 주소의 QR이 생깁니다.

```
차 화면(L2 초대 QR) 또는 운전자 휴대폰 ──create_trip──▶ 운행 서버 ──▶ 코드, 관리 비밀값
        │
        └─ QR: https://<사이트>/t/<코드> ──▶ 동승자 휴대폰 ──join_trip(탈 곳, 내릴 곳 좌표)──▶ 운행 서버
                                                                                  │
차량 앱 ◀──get_trip_admin(코드, 관리 비밀값)── 동승자 장소 ──▶ NaviHelper 경유지 ◀┘
```

- 사이트는 하나이고, 운행마다 주소와 QR만 새로 생깁니다. 사이트를 매번 새로 배포하지 않습니다.
- 코드는 헷갈리는 글자(0, O, 1, I, L)를 뺀 6자리입니다.
- 운행을 만든 기기(차나 운전자 휴대폰)만 관리 비밀값을 가집니다. 동승자는 코드로 참여만 할 수 있습니다.

## 폴더 구성

| 폴더 | 내용 |
|---|---|
| `supabase/migrations/` | DB 테이블과 서버 함수(SQL). 계정 없이 비밀값의 해시로 구분 |
| `supabase/tests/` | 실제 마이그레이션을 PGlite(Node에서 도는 Postgres)로 돌리는 테스트 10개 |
| `web/` | 휴대폰 웹 (React, TypeScript, Vite, Tailwind CSS, MapLibre) |
| `docs/` | 서버 설계, 차량 앱 연동 방법 |

## 화면 (휴대폰 웹)

동승자 화면은 피그마 `카풀로그` 파일의 "3. 동승자 휴대폰 웹" 시안(M1~M4)을 그대로 구현했습니다.

| 주소 | 시안 | 내용 |
|---|---|---|
| `/t/:code` | M1 | 초대, 이름, 휴대폰 뒤 4자리, 탈 곳(출발지에서 같이 타요, 가는 길에 태워 주세요), 내릴 곳 검색, 장소 저장(선택) |
| `/t/:code` | M1-1 | 다른 휴대폰에서 열면 이름과 뒤 4자리로 확인 |
| `/t/:code` | M2 | 탑승 전: 도착까지 남은 시간, 차 위치 지도, 예상 내 몫, 코스 |
| `/t/:code` | M2-1 | 탑승 후: 내릴 때까지 남은 시간, 위치 공유 꺼짐 안내, 코스 |
| `/t/:code` | M3 | 정산: 내 몫, 나눈 방법, 총비용, 모두의 몫, 저장된 장소, 계좌번호 복사, 문제 신고 |
| `/t/:code/how` | M4 | 계산 방법(섀플리 값) |
| `/c/:code` | - | 정기 카풀 고정 링크. 최신 운행으로 연결, 이번 달 분담액 |

운전자 준비 페이지(`/`, `/new`, `/d/:code`, `/link/:code`, `/settings`)는 피그마 시안이 없어 같은 디자인 토큰으로만 맞췄습니다.

## 로컬에서 실행

Supabase 설정 없이 실행하면 같은 SQL을 브라우저 안에서 돌리는 **데모 모드**로 동작합니다(오른쪽 위 "데모 모드" 표시). 운전자 페이지의 "시연 도구"로 차가 보내는 진행 상황과 정산을 흉내 낼 수 있습니다.

```bash
cd web
npm install
npm run dev
```

DB 함수 테스트:

```bash
cd supabase
npm install
npm test
```

## 배포

### 1. Supabase (운행 서버)

1. supabase.com에서 프로젝트를 만듭니다(무료 요금제).
2. Database > Extensions에서 `pg_cron`을 켭니다(끝난 운행의 위치 자동 삭제용).
3. 마이그레이션을 올립니다.

```bash
npx supabase login
npx supabase link --project-ref <프로젝트 ref>
npx supabase db push
```

4. Settings > API에서 Project URL과 anon key를 확인합니다. anon key로는 테이블을 직접 읽을 수 없고, 정해진 함수만 부를 수 있습니다.

### 2. Vercel (휴대폰 웹)

1. vercel.com에서 이 GitHub 저장소를 가져옵니다.
2. Root Directory를 `web`으로 지정합니다.
3. 환경 변수를 넣습니다.

| 이름 | 값 |
|---|---|
| `VITE_SUPABASE_URL` | Supabase Project URL |
| `VITE_SUPABASE_ANON_KEY` | Supabase anon key |
| `VITE_PUBLIC_BASE_URL` | QR에 찍힐 사이트 주소 (비우면 현재 주소) |

## 외부 데이터

- 지도: [MapLibre GL JS](https://maplibre.org/)와 [OpenFreeMap](https://openfreemap.org/) 타일, 데이터 © OpenStreetMap 기여자
- 장소 검색: [Photon](https://photon.komoot.io/)(OpenStreetMap 검색). 카카오 로컬 검색을 서버 함수로 붙이면 교체할 수 있게 `web/src/api.ts`의 `searchPlaces` 한 곳에 모아 두었습니다.

## 개인정보

- 회원가입, 휴대폰 번호, 계정이 없습니다. 운전자, 동승자, 차는 각자 기기에 둔 무작위 비밀값으로 구분하고 서버에는 해시만 저장합니다. 휴대폰 뒤 4자리도 운행 ID와 섞은 해시로만 저장합니다.
- 동승자의 탈 곳과 내릴 곳은 운전자(와 연결된 차)만 봅니다. 다른 동승자에게는 "동승자 1명 내림"처럼 가려서 보이고, 운전자의 목적지도 보이지 않습니다.
- 차 위치는 그 동승자가 타기 전까지만 보입니다.
- 정산이 끝나면 좌표를 동네 수준(약 1km)으로 줄이고, 24시간 뒤에는 장소를 지웁니다. 장소 저장에 동의한 사람의 장소만 정기 카풀에 남고, "내 장소 지우기"로 언제든 지울 수 있습니다.
- 운전자 계좌는 운전자가 동의한 경우에만 저장하고, 정산이 끝난 운행에 실제로 탄 사람에게만 보여 줍니다. 페어풀은 송금하지 않습니다.

자세한 내용은 [docs/서버_설계.md](docs/서버_설계.md), 차량 앱 쪽 호출 방법은 [docs/차량앱_연동.md](docs/차량앱_연동.md)에 있습니다.
