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

- 내비는 경로를 받을 때마다 앞으로 나온다. 그래서 탑승/하차 뒤 다음 경로는 확인 카드가 사라진 뒤(10초) 보낸다.
- 에뮬레이터에서는 주행거리가 움직이지 않으면 OSRM 공개 서버의 도로 거리로 정산한다. 길찾기도 안 되면 직선거리에 1.3을 곱한다.
- 경유지 도착 이벤트는 에뮬레이터에서 오지 않으므로 탑승과 하차는 좌석 센서와 수동 버튼으로 기록한다.
