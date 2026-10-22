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
| `vehicle_secret` | 지훈의 차. 운전자 정보(지훈, 뒤 4자리 1234)와 받을 계좌(카카오뱅크)가 들어 있음 |
| `driver_secret` | 지훈의 휴대폰(차 연결, 보내기 확인용) |
| `carpool_code`, `folder_code` | 즐겨찾기 "출근 카풀"(수진, 민호)과 같은 이름의 카풀 폴더. 폴더에 운행 3회가 요청 전으로 쌓여 있음 |
| `trips.mt` | "동아리 MT 귀갓길" 준비 중 운행. 태호(신촌역 2번 출구에서 탐), 서연, 민지가 참여함 |
| `devices` | 시연 동승자의 기기 키. 웹 localStorage `fairpool.deviceKey` 에 넣으면 그 사람 화면으로 열림 |

## 웹을 이 서버에 붙이기

`web/.env.local` 에 `VITE_API_URL=http://localhost:54321` 을 넣고 `npm run dev` 를 실행합니다. 비워 두면 브라우저 안 데모 DB를 씁니다.
