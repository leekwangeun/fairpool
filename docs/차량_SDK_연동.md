# 차량 SDK 연동

앱이 Pleos Connect SDK의 어떤 함수를 언제 부르는지 정리했습니다. 함수 이름과 인자는 Pleos Maven 저장소의 SDK(Vehicle 2.3.2, LLM 2.1.3.2 등)에서 직접 확인한 것입니다.

## 1. 구조

| 파일 | 하는 일 |
|---|---|
| `vehicle/VehicleBus.kt` | 차량 신호를 한곳에 모음(`Signals`). 화면과 게임은 여기만 읽음 |
| `vehicle/PleosVehicle.kt` | Vehicle SDK와 FusedLocation 리스너 등록, 제어 명령, 백업과 복구 |
| `vehicle/FakeVehicle.kt` | 데모 모드의 가짜 차. 같은 제어 인터페이스(`Controls`) |
| `vehicle/CarService.kt` | 포그라운드 서비스. 다른 앱이 앞에 있어도 안전 감시를 계속함 |
| `game/SafetyGuard.kt` | 안전 확인 목록과 일시정지 이유 (순수 함수) |
| `game/StageJudge.kt` | 연속된 두 신호로 퍼즐 진행 판정 (순수 함수) |
| `game/Effects.kt` | 4D 연출 (앰비언트, 스마트 글래스, 독서등, 시트 통풍) |
| `game/GameEngine.kt` | 상태 기계: 홈, 안전 확인, 플레이, 일시정지, 탈출 성공 |
| `ai/` | AI 게임마스터 (Pleos LLM SDK와 내장 생성기), 프롬프트와 응답 검사 |
| `voice/Voice.kt` | TextToSpeech로 NPC 대사 읽기, SpeechToText로 질문과 정답 듣기 |

## 2. 읽는 신호 (리스너)

| 도메인 | 함수 | 쓰는 곳 |
|---|---|---|
| Gear | `registerGearSelection`, `getCurrentGearSelectionState` | 주행 전환 감지 |
| AAOS CarPropertyManager | `PERF_VEHICLE_SPEED` | 주행 감지 (Pleos SDK에는 속도 신호가 없음) |
| Seat | `registerSeatOccupancy`, `registerSeatBeltBuckledState` (운전석, 조수석, 2열 좌우) | 착석과 안전벨트 감시 |
| Door | `registerDoorPosition`, `registerDoorLockState`, `registerGloveBoxLockState` | 문 열림 일시정지, 잠금 확인 |
| Window | `registerWindowLockState`, `registerWindowPosition`, `getSmartGlassConfig` | 창문 잠금과 닫힘 확인, 스마트 글래스 지원 여부 |
| HVAC | `registerHvacDefrosterState`, `registerHvacMaxDefrostState`, `registerHvacRecirculationState`, `registerHvacAirCleanerState`, `registerHvacTemperature`, `registerHvacFanSpeed` | 성애 제거, 공기 정화, 온도 암호 |
| SideMirror | `registerHvacSideMirrorHeatState` | 성애 제거 (사이드미러 열선) |
| Light | `registerReadingLightState` (좌석별) | 손전등 신호 |
| SteeringWheel | `registerSteeringWheelAngle` | 금고 다이얼 |
| Brake | `registerBrakePedalStatus` | 금고 다이얼 |
| TurnSignal | `registerTurnSignalState` | 금고 다이얼 |
| EvBattery | `registerEvBatteryStateOfCharge` | 이야기 배경, 코드 레드 에너지 표시 |
| Wiper | `registerWiperState` (앞 유리) | 비 오는 날 판별, 고스트 택시 특별편 |
| FusedLocation | `registerFusedLocationCallback`, `getLastKnownLocation` | 장소 판별 |

## 3. 보내는 명령

| 언제 | 함수 |
|---|---|
| 안전 확인 시작 | `Door.setDoorLock(전체, true)`, `Window.setWindowPosition(전체, 0)`, `Window.setWindowLock(전체, true)`, `Door.setGloveBoxLock(true)` |
| 플레이 시작 | `Light.setCabinLightSwitch(OFF)`, `Light.setAmbientLightSwitch`, `setAmbientLightBrightnessColor`, `setAmbientLightBrightness`, `Window.setSmartGlassState(true)`, `Window.moveWindowBlind`, `Toll.setEtcsVoiceGuidanceVolume(0)` |
| 빛의 조각 | `Window.setSmartGlassZoneState(창문별, [ON 또는 OFF])` 로 한 창문만 투명 |
| 공기 정화 | 독가스 색 앰비언트, `HVAC.setHvacFanSpeed` |
| 고스트 택시 | 2열 `Light.setReadingLightSwitch` 깜빡임, 핏빛 앰비언트, 온도 단계에서 `Seat.setHvacSeatVentilation(아이 좌석, 3)` |
| 탈출 성공 | `Door.setGloveBoxLock(false)`, 조수석 독서등 스포트라이트, 스마트 글래스 투명 |
| 복구 | 백업한 값으로 위 명령을 다시 보냄. 탈출 성공 때는 글로브박스만 열어 둠 |
| 비상 전체 해제 | 복구 뒤 문, 창문, 글로브박스 잠금 해제 |

## 4. 백업과 복구

게임을 시작할 때 아래 값을 `get` 함수로 읽어(1.2초 안에 답이 없으면 그 항목은 건너뜀) 기기에 JSON으로 저장합니다. 복구하면 지우고, 앱이 비정상 종료돼 남아 있으면 다음 실행 3초 뒤 복구합니다.

| 항목 | 읽는 함수 |
|---|---|
| 문 잠금 (좌석별) | `Door.getDoorLockState` |
| 창문 잠금 | `Window.getCurrentWindowLockState` |
| 글로브박스 | `Door.getCurrentGloveBoxLockState` |
| 독서등 (좌석별), 실내등 | `Light.getCurrentReadingLightState`, `getCabinLightSwitch` |
| 앰비언트 색과 밝기 (좌석별) | `Light.getAmbientLightBrightnessColor`, `getAmbientLightBrightness` |
| 시트 통풍과 열선 (좌석별) | `Seat.getHvacSeatVentilationState`, `getHvacSeatTemperature` |
| 공조 온도, 풍량, 내기 순환, 공기청정 | `HVAC.getHvacTemperature`, `getHvacFanSpeed`, `getHvacRecirculationState`, `getHvacAirCleanerState` |
| 스마트 글래스 | `Window.getCurrentSmartGlassState` |
| 하이패스 안내음 | `Toll.getCurrentEtcsVoiceGuidanceVolume` |

## 5. 음성과 AI

| SDK | 쓰는 곳 |
|---|---|
| TextToSpeech 3.3.1 (`Mode.ON_DEVICE`) | 에피소드 시작, 단계 대사, 통과, 일시정지, NPC 대답 |
| SpeechToText 3.3.1 (`Mode.ON_DEVICE`) | 말하기 버튼: NPC에게 질문, 빛의 조각 창문 답, 최종 암호 |
| LLM 2.1.3.2 (`Mode.ON_DEVICE`, `generateContent`) | 에피소드 이야기와 NPC 대답. 앱 자격 정보(`registerApp`)나 외부 API 키 없이 차량의 LLM 서비스를 부름 |

- LLM에는 기본 에피소드의 퍼즐 값과 허용 범위를 함께 주고, JSON으로만 답하게 합니다(`ai/LlmPrompt.kt`).
- 답에서 첫 `{`부터 마지막 `}`까지 꺼내 합치고, 범위를 벗어난 값(각도, 온도, 깜빡임, 숫자)은 기본값으로 둡니다.
- NPC 대답은 두 문장으로 자르고, 최종 암호가 들어 있으면 다른 말로 바꿉니다.

## 6. 권한

| 권한 | 이유 |
|---|---|
| `pleos.car.permission.CAR_*` (DOORS, WINDOWS, SEATS, SAFETY, CLIMATE, LIGHTS, MIRRORS, DRIVE, ENERGY, INFO, TOLL) | 신호 읽기 |
| `pleos.car.permission.CONTROL_CAR_*` (DOORS, WINDOWS, SEATS, CLIMATE, LIGHTS, MIRRORS, TOLL) | 잠금, 조명, 공조, 스마트 글래스 제어 |
| `pleos.car.permission.TTS_SERVICE`, `STT_SERVICE`, `LLM_SERVICE`, `FUSED_LOCATION` | 음성, AI, 위치 |
| `android.car.permission.CAR_POWERTRAIN`, `CAR_SPEED` | 기어와 차속 |
| `RECORD_AUDIO` (실행 중 허용) | 말하기 |

## 7. 확인하지 못한 것

이 저장소의 빌드와 단위 테스트는 PC에서 확인했습니다. 아래는 에뮬레이터나 실제 차량에서 아직 확인하지 않았습니다.

- 에뮬레이터가 제어 명령(문 잠금, 글로브박스, 스마트 글래스, 앰비언트, 시트 통풍)을 받아들이는지. 화면 아래 "4D 연출" 줄은 앱이 보낸 명령을 보여 주고, 차가 거절하면 설정 화면의 차량 기록에 실패가 남습니다.
- 차량에 온디바이스 LLM 서비스가 있는지와 응답 시간, JSON 형식을 지키는 비율. 없으면 내장 생성기로 동작합니다.
- LLM `ResultListener.onResponse` 의 두 번째 인자(완료 여부로 가정)의 실제 의미. 조각으로 오면 이어 붙이고, 완료 신호가 없으면 제한 시간에 받은 만큼 씁니다.
- Virtual Console의 운전석 좌석, 안전벨트, 공조, 핸들 항목 이름. 시연 스크립트는 이전에 확인된 항목(조수석과 2열 좌석, 문, 기어, 속도)만 씁니다.
