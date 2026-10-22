# 차 앱 (Pleos Connect)

페어풀(FairPool)의 차량용 앱입니다. 운행 만들기, 초대 QR, 좌석 센서로 탑승과 하차 기록, 내비 경유지 안내, 도착 정산, 카풀 폴더 정산을 차 화면에서 처리합니다. 서버 함수는 `docs/차량앱_연동.md`를 따릅니다.

## 빌드

- JDK 21, Gradle 8.10.2, Android SDK 35 (Android Studio 내장 JBR 25는 Gradle이 거부함)
- 저장소 경로에 한글이 있으면 aapt2가 실패하므로 빌드 결과는 `C:/dev/build/fairpool-car/app`에 만든다(`app/build.gradle.kts`의 `buildDirectory`). 다른 PC에서는 이 줄을 지우거나 바꾼다.

```bash
gradle -p car assembleRelease
gradle -p car testDebugUnitTest
```

release는 디버그 키로 서명한다(시연용).

## 에뮬레이터에서 실행

```bash
adb install -r C:/dev/build/fairpool-car/app/outputs/apk/release/app-release.apk
adb shell am start --user 10 -n kr.fairpool.car/.MainActivity
```

- 서버 주소 기본값은 `http://10.0.2.2:54321`(로컬 devserver)이다. 헤더의 "페어풀(FairPool)"을 누르면 설정이 열리고 서버 주소, QR 사이트 주소, 휘발유 단가, 차 비밀값, 데모 모드를 바꿀 수 있다.
- 서버에 연결되지 않으면 처음 등록할 때 데모 모드로 바뀐다. 데모 모드는 앱 안의 가짜 서버가 응답하고, 초대 화면에서 동승자 세 명이 3초마다 들어온다.
- devserver 시연 데이터의 차(지훈의 차)를 쓰려면 `devserver/data/seed.json`의 `vehicle_secret`을 설정의 "차 비밀값"에 넣는다.

## 시연 시나리오 자동 재현

```bash
bash car/scripts/demo_mt_return.sh
```

- 시드의 "동아리 MT 귀갓길"(준비 중인 운행 첫 줄)을 열고, Virtual Console로 좌석, 문, 기어, 주행거리만 바꿔 대본 순서대로 진행한다. 조수석 서연, 뒷좌석 오른쪽 민지가 숙소에서 타고, 신촌역(69.6km)에서 뒷좌석 왼쪽 태호가 탄다. 72.4km 민지, 74.3km 서연, 75.3km 태호가 내리고 80.0km에 도착한다. 도착 정산에서 통행료를 3,000원으로 고친 뒤 바로 정산으로 확정한다.
- Virtual Console은 에뮬레이터 안의 웹 서버(`adb forward tcp:8088 tcp:8088`)이고, 브라우저 세션이 살아 있을 때만 값을 받는다. 스크립트가 세션 신호를 대신 보낸다. 주행거리는 줄일 수 없어서 매번 다음 100km 단위에서 시작한다.
- 수동 탑승/하차 버튼은 센서가 놓쳤을 때를 위해 주행 화면에 그대로 있다.

## Pleos SDK

| SDK | 쓰는 곳 | 에뮬레이터 확인 |
|---|---|---|
| Vehicle 2.3.2 | 좌석, 벨트, 문, 주행거리, 기어, 연비 | 연비만 실패(EV), 나머지 수신 |
| NaviHelper 3.0.5 | 경유지 2곳 + 목적지 경로, 통행료, 내비에서 보기 | 경로 요청과 통행료 수신 |
| TextToSpeech 3.3.1 | L5 확인 카드 한 번 읽기, L6 총비용 안내 | 온디바이스 음성으로 재생됨(서버 모드는 앱 자격 정보가 없어 자동 전환) |
| SpeechToText 3.3.1 | L5 카드에서 "맞아", "잘못 인식"만 듣기 | 인식 요청과 녹음 시작까지 확인, 말소리 인식은 마이크 입력이 없어 미확인 |
| FusedLocation 1.1.3 | 탑승, 하차 위치 보조 기록 | 좌표 수신 |

권한: `pleos.car.permission.TTS_SERVICE`, `STT_SERVICE`(normal), `pleos.car.permission.FUSED_LOCATION`, `RECORD_AUDIO`(실행 중 허용).

Vehicle SDK 2.3.2는 SEAT_OCCUPANCY를 `value == 1`로 바꿔 넘기는데 VHAL에서 1은 빈자리다. 에뮬레이터에서 "Occupied"가 false로 오는 것을 확인했고, 앱은 반대로 읽는다(`TripService.isOccupied`).

## 구성

| 파일 | 내용 |
|---|---|
| `MainActivity.kt` | 화면 전체(L0 등록, L1 운행 만들기, L1-9 운전자 정보, L2 초대, L3 좌석과 경로, L4 주행, L5 탑승/하차 카드, L6 정산, L1-6/L1-7 카풀 폴더, 설정) |
| `trip/TripEngine.kt` | 운행 상태, 센서 입력을 탑승/하차로 바꾸는 규칙, 서버 기록, 정산 계산 |
| `trip/TripService.kt` | 포그라운드 서비스(좌석, 벨트, 문, 주행거리, 기어, 연비 리스너)와 NaviHelper 연결 |
| `settlement/Settlement.kt` | 샤플리 값(지나가기 규칙, 혼자 갈 때 상한, 운전자 제외, 10원 단위 맞춤)과 구간별 N분의 1 |
| `map/CourseMap.kt` | MapLibre로 그리는 코스 지도(탄 곳 초록 원, 내린 곳 파란 핀) |
| `data/Backend.kt` | 서버 RPC 호출, 설정 저장, 데모 서버 |

## 알려진 제약

- 내비는 경로를 받을 때마다 앞으로 나온다. 그래서 탑승/하차 뒤 다음 경로는 확인 카드가 사라진 뒤(10초) 보낸다. 내비가 앞에 있는 동안 다음 정차의 확인 카드는 가려진다. 정차 때 앱이 스스로 앞으로 나오게 해 봤지만 일반 앱은 백그라운드 화면 전환이 막혀 있다(`BAL_BLOCK`, 내비는 SYSTEM_ALERT_WINDOW가 있어 허용). 실차에서는 화면 오른쪽 창 바를 끌어 분할 화면으로 두면 내비와 카드가 함께 보인다. 시연 스크립트는 정차마다 앱을 다시 앞으로 부른다.
- 사람별 몫은 함께 탄 조합마다 OSRM 공개 서버에서 받은 도로 거리(0.1km 단위)로 계산한다. 시드 장소를 기획서 표를 만든 좌표로 두면 조합 거리 15개가 표와 같고, 시나리오 A 결과도 기획서 예시(지훈 5,170 / 민지 5,110 / 서연 4,820 / 태호 260원, 합계 15,360원)와 같다. 장소 좌표가 다르면 금액도 수십 원 달라진다.
- 출발할 때 계획된 코스로 조합 거리표를 미리 받아 둔다. 도착 후 길찾기가 안 되면 이 표로 계산하고, 설정의 "시연 대비" 스위치를 켜면 처음부터 이 표를 쓴다. 정산에 쓴 조합 거리표는 post_settlement의 `coalitions`(members, km)와 `distance_source`(osrm, saved, segments)로 함께 저장되고, 도착 정산 화면의 총 거리 칸을 누르면 보인다.
- 주행거리가 움직이지 않으면 조합 전체의 도로 거리를 쓰고, 길찾기도 안 되면 직선거리에 1.3을 곱한다.
- 에뮬레이터 내비는 차 위치가 실제 경로와 달라 통행료를 엉뚱하게 알려 준다. 도착 정산의 통행료 칸을 눌러 고칠 수 있다.
- 경유지 도착 이벤트는 에뮬레이터에서 오지 않으므로 탑승과 하차는 좌석 센서와 수동 버튼으로 기록한다.
