#!/usr/bin/env bash
# 시연 시나리오 "동아리 MT 귀갓길"을 Pleos 에뮬레이터에서 자동으로 재현한다.
# Virtual Console(좌석, 문, 기어, 주행거리)만 바꾸고, 차 앱 화면은 adb 탭으로 넘긴다(2560x1440 기준).
#
# 준비: 에뮬레이터 실행, devserver 실행(시드 운행이 준비 상태), 차 앱 설정의 차 비밀값 = seed.json의 vehicle_secret
# 사용: bash car/scripts/demo_mt_return.sh [준비 중인 운행 줄 번호(기본 1)]
set -u
ROW=${1:-1}
ADB=${ADB:-"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"}
[ -x "$ADB" ] || ADB=adb
VC="http://127.0.0.1:8088"
SHOTS=${SHOTS:-./demo-shots}
mkdir -p "$SHOTS"

say() { echo "[$(date +%H:%M:%S)] $*"; }
put() { curl -s -m 5 -o /dev/null -X PUT "$VC/vehicle/$1"; }
tap() { "$ADB" shell input tap "$1" "$2"; }
shot() { "$ADB" exec-out screencap -p > "$SHOTS/$1.png"; }
front() { "$ADB" shell am start --user 10 -n kr.fairpool.car/.MainActivity >/dev/null 2>&1; sleep 3; }
seat() { put "seat?kind=occupied&area=$1&value=$2"; }
door() { put "body?$1=$2"; }
odo() { put "odometer?traveled_distance_km=$(awk "BEGIN{printf \"%.1f\", $BASE+$1}")"; }
# the stock navi comes forward when it receives the next leg (10 s after a stop); bring FairPool back
after_stop() { sleep 12; front; }

# Virtual Console only accepts writes while a browser session is alive: keep one open in the background
"$ADB" forward tcp:8088 tcp:8088 >/dev/null
( while true; do curl -s -m 2 -o /dev/null -X POST "$VC/browser/session?client=fairpool-demo"; sleep 1; done ) &
KEEP=$!
trap 'kill $KEEP 2>/dev/null' EXIT
sleep 2

# the odometer may only go up: start this trip from the next round number
NOW=$(curl -s -m 5 "$VC/vehicle/state" | sed -n 's/.*"traveled_distance_km":\([0-9.]*\).*/\1/p' | head -1)
BASE=$(awk "BEGIN{printf \"%d\", int(${NOW:-0}/100+1)*100}")
say "주행거리 기준 ${BASE}km"

say "주차, 좌석 비움, 문 닫힘"
put "gear?gear=Park"
for a in passenger rear_left rear_right; do seat "$a" false; done
door door_fr false; door door_rl false; door door_rr false
odo 0
"$ADB" shell am force-stop kr.fairpool.car
front; sleep 3

say "L1 준비 중인 운행 열기 (${ROW}번째 줄)"
if [ "$ROW" = 2 ]; then tap 2140 460; else tap 2140 343; fi
sleep 4; shot 01_L2_invite

say "L2 내 휴대폰으로 보내기"
tap 1783 1204; sleep 2; shot 02_L2_sent

say "숙소에서 서연(조수석), 민지(뒷좌석 오른쪽) 탑승"
door door_fr true; seat passenger true; door door_fr false
door door_rr true; seat rear_right true; door door_rr false
sleep 2

say "L3 좌석과 들를 곳 순서"
tap 2266 1204; sleep 4
# the car names seats in list order (민지 first); one tap on the front seat gives it to 서연 and moves 민지 back
tap 1314 600; sleep 1; shot 03_L3_seats

say "경로 보내고 출발"
tap 1692 1204; sleep 7; front; sleep 4
put "gear?gear=Drive"; odo 30; sleep 2; shot 04_L4_driving

say "신촌역 2번 출구 (69.6km): 태호 뒷좌석 왼쪽 탑승"
odo 69.6; sleep 1; put "gear?gear=Park"; sleep 1
door door_rl true; seat rear_left true; sleep 2; shot 05_L5_taeho_in
door door_rl false; after_stop

say "망원역 (72.4km): 민지 하차"
put "gear?gear=Drive"; odo 72.4; sleep 1; put "gear?gear=Park"; sleep 1
door door_rr true; seat rear_right false; sleep 2; shot 06_L5_minji_out
door door_rr false; after_stop

say "연남동 공원 (74.3km): 서연 하차"
put "gear?gear=Drive"; odo 74.3; sleep 1; put "gear?gear=Park"; sleep 1
door door_fr true; seat passenger false; sleep 2; shot 07_L5_seoyeon_out
door door_fr false; after_stop

say "연희동 주민센터 (75.3km): 태호 하차"
put "gear?gear=Drive"; odo 75.3; sleep 1; put "gear?gear=Park"; sleep 1
door door_rl true; seat rear_left false; sleep 2; shot 08_L5_taeho_out
door door_rl false; after_stop

say "응암동 지훈 집 (80.0km) 도착"
put "gear?gear=Drive"; odo 80.0; sleep 1; put "gear?gear=Park"; sleep 2
tap 2342 1204; sleep 15; shot 09_L6_settlement

say "통행료 3,000원으로 고치기"
tap 1425 474; sleep 2
tap 1692 696; sleep 1
"$ADB" shell input keycombination 113 29; "$ADB" shell input keyevent 67
"$ADB" shell input text 3000; sleep 1; shot 10_L6_toll_dialog
"$ADB" shell input keyevent 66; sleep 15; shot 10_L6_toll_fixed

say "L6-2 바로 정산으로 확정"
tap 1711 1082; sleep 1; shot 11_L6_direct
tap 2358 1204; sleep 3; shot 12_done
say "끝. 캡처: $SHOTS"
