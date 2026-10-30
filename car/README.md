# 차 앱 (Pleos Connect)

차량탈출 : AI 멀티버스의 차량 앱입니다. 화면은 `docs/화면_설계.md`, SDK 호출은 `docs/차량_SDK_연동.md`를 따릅니다.

## 빌드

- JDK 17 이상, Android SDK 35, Gradle 8.10 이상
- Pleos SDK는 `settings.gradle.kts` 의 Pleos Maven 저장소(nexus-playground.pleos.ai)에서 받습니다.
- 윈도우에서 저장소 경로에 한글이 있으면 aapt2가 실패하므로, 윈도우에서는 빌드 결과를 `C:/dev/build/carescape/app` 에 만듭니다(`app/build.gradle.kts`).

```bash
gradle -p car assembleRelease
```

```bash
gradle -p car testReleaseUnitTest
```

release는 디버그 키로 서명합니다(시연용).

## 패키지 구성

| 패키지 | 내용 |
|---|---|
| `vehicle` | 차량 신호 모음(`VehicleBus`), Pleos SDK 연결(`PleosVehicle`), 데모용 가짜 차(`FakeVehicle`), 포그라운드 서비스(`CarService`) |
| `game` | 테마와 에피소드 모델, 내장 생성기, 퍼즐 판정, 안전 규칙, 4D 연출, 상태 기계(`GameEngine`) |
| `ai` | AI 게임마스터 인터페이스, Pleos LLM 연결, 프롬프트와 응답 검사 |
| `voice` | TextToSpeech와 SpeechToText |
| `data` | 설정과 차량 백업 저장 |
| `ui` | 화면 E0~E6 (Jetpack Compose). 1682 폭 기준으로 그리고 실제 화면 폭에 맞춰 확대 |

## 테스트

| 파일 | 확인하는 것 |
|---|---|
| `EpisodeGeneratorTest` | 같은 seed는 같은 에피소드, 테마마다 6단계, 300개 seed에서 퍼즐 값이 차가 낼 수 있는 범위, 차량 상황이 이야기에 반영됨 |
| `StageJudgeTest` | 기믹 7종의 통과 조건, 말로 한 숫자와 창문 알아듣기 |
| `SafetyGuardTest` | 안전 확인 목록, 센서 없는 항목 처리, 일시정지 이유 우선순위 |
| `LlmPromptTest` | 프롬프트 내용, LLM 응답 합치기와 범위 밖 값 거르기, 암호 누설 차단, 백업 저장과 읽기 |
