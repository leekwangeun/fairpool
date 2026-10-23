# 로컬 개발 서버 (devserver)

Supabase 프로젝트 없이 차 앱과 휴대폰 웹을 붙여 보기 위한 서버입니다. `supabase/migrations` 의 마이그레이션을 PGlite(Node에서 도는 Postgres)로 띄우고, Supabase(PostgREST)와 같은 모양으로 서버 함수를 엽니다. 나중에 실제 Supabase로 옮길 때는 주소만 바꾸면 됩니다.

## 실행

```bash
cd devserver
npm install
npm run reset   # DB를 새로 만들고 시연 데이터를 넣음 (서버를 끈 상태에서)
npm start       # http://0.0.0.0:54321
```

| 명령 | 하는 일 |
|---|---|
| `npm start` | 서버 실행. 포트 `PORT`(기본 54321), 주소 `HOST`(기본 0.0.0.0) |
| `npm run reset` | `data/` 를 지우고 마이그레이션과 시연 데이터를 다시 넣음 |
| `npm run seed` | 시연 데이터만 넣음(이미 있으면 건너뜀, `-- --force` 로 강제) |
| `npm run upgrade` | 서버 함수만 바뀌었을 때 데이터와 비밀값을 그대로 두고 함수만 다시 적용(서버를 끈 상태에서). 테이블이 바뀌면 `npm run reset` |
| `npm test` | HTTP 응답 모양, 오류, 시드, 재시작 뒤 데이터 유지 테스트 |

- DB는 `data/pg` 폴더에 남아 서버를 다시 켜도 그대로입니다. 마이그레이션이 바뀌면 시작할 때 알려 주니 `npm run reset` 으로 다시 만듭니다.
- `data/` 는 git에 올리지 않습니다.

## 접속 주소

| 기기 | 주소 |
|---|---|
| 이 PC(웹, curl) | `http://localhost:54321` |
| 안드로이드 에뮬레이터(차 앱) | `http://10.0.2.2:54321` |
| 같은 와이파이의 휴대폰 | `http://<PC의 IP>:54321` (서버를 켜면 출력됨. 윈도우 방화벽에서 Node 허용 필요) |

## 호출 방법 (Supabase와 같음)

```
POST /rest/v1/rpc/<함수 이름>
Content-Type: application/json

{ "p_vehicle_secret": "..." }
```

- 결과는 함수가 돌려주는 JSON 그대로입니다.
- 오류는 HTTP 400과 `{ "code": "P0001", "message": "not_allowed", "details": null, "hint": null }` 입니다. `message` 가 서버 오류 코드(`trip_not_found`, `settle_started` 등)입니다.
- 없는 함수, 인자 이름이 틀린 호출, 마이그레이션의 공개 목록(api 배열)에 없는 함수는 404 `PGRST202` 입니다.
- `apikey`, `Authorization` 헤더는 받기만 하고 확인하지 않습니다. 모든 출처에서 부를 수 있습니다(CORS 허용).
- `GET /health` 는 열려 있는 함수 목록을 돌려줍니다.

```bash
curl -X POST http://localhost:54321/rest/v1/rpc/get_vehicle_trips \
  -H "Content-Type: application/json" -d "{\"p_vehicle_secret\":\"<data/seed.json 의 vehicle_secret>\"}"
```

## 시연 데이터

`npm run reset` 을 하면 `data/seed.json` 에 비밀값과 코드가 저장됩니다.

| 항목 | 내용 |
|---|---|
| `vehicle_secret` | 지훈의 차. 운전자 정보(지훈, 뒤 4자리 1234)와 받을 계좌(페어은행)가 들어 있음 |
| `driver_secret` | 지훈의 휴대폰(차 연결, 보내기 확인용) |
| `folder_code` | 카풀 폴더 "출근 카풀". 기획서 3.4 계산 예시 3과 같은 10월 평일 16회(수진만 6회, 수진과 민호 5회, 셋 3회, 민호와 지은 2회)가 요청 전으로 쌓여 있음. 수진 14회 29,090원, 민호 10회 16,000원, 지은 5회 7,610원, 운전자 44,930원, 받을 금액 52,700원 |
| `carpools` | 즐겨찾기 "출근 카풀"(수진, 민호, 지은)과 "동아리 MT 가는 길"(태호, 서연, 민지) |
| `trips.mt_return` | "동아리 MT 귀갓길" 준비 운행(시나리오 A, 계산 예시 1). 출발 가평 MT 숙소, 도착 응암동 지훈 집. 민지(망원역 하차)와 서연(연남동 하차)은 숙소에서, 태호는 신촌역 2번 출구에서 타서 연희동에서 내림 |
| `expected.mt_return` | 차 앱이 맞춰 볼 기대 정산: 총 15,360원(기름값 12,360원, 통행료 3,000원, 80.0km, 11.0km/L, 1,700원/L), 지훈 5,170원, 민지 5,110원, 서연 4,820원, 태호 260원, 좌석과 들를 곳 순서 |
| `devices`, `phone4` | 시연 동승자의 기기 키와 뒤 4자리. 기기 키를 웹 localStorage `fairpool.deviceKey` 에 넣으면 그 사람 화면으로 열림 |

## 웹을 이 서버에 붙이기

`web/.env.local` 에 `VITE_API_URL=http://localhost:54321` 을 넣고 `npm run dev` 를 실행합니다. 비워 두면 브라우저 안 데모 DB를 씁니다.
