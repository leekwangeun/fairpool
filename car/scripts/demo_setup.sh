#!/usr/bin/env bash
# Pleos 에뮬레이터를 시연 시작 상태로 맞추고 앱을 띄운다.
# Virtual Console(좌석, 문, 기어)로 "주차, 뒷좌석 오른쪽 착석, 문 닫힘"을 만든다. 운전석 좌석 값은 건드리지 않는다.
#
# 사용: bash car/scripts/demo_setup.sh [drive]
#   drive 를 붙이면 플레이 중에 문 열기, 문 닫기, 주행 전환을 차례로 보내 안전 가드(E3)를 시연한다.
set -u
ADB=${ADB:-"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"}
[ -x "$ADB" ] || ADB=adb
VC="http://127.0.0.1:8088"
APK=${APK:-apk/CarEscape_차량탈출_release.apk}

say() { echo "[$(date +%H:%M:%S)] $*"; }
put() { curl -s -m 5 -o /dev/null -X PUT "$VC/vehicle/$1"; }
seat() { put "seat?kind=occupied&area=$1&value=$2"; }
door() { put "body?$1=$2"; }

# Virtual Console only accepts writes while a browser session is alive: keep one open in the background
"$ADB" forward tcp:8088 tcp:8088 >/dev/null
( while true; do curl -s -m 2 -o /dev/null -X POST "$VC/browser/session?client=carescape-demo"; sleep 1; done ) &
KEEP=$!
trap 'kill $KEEP 2>/dev/null' EXIT
sleep 2

if [ -f "$APK" ]; then
    say "앱 설치"
    "$ADB" install -r "$APK" >/dev/null
fi

say "주차, 뒷좌석 오른쪽 착석, 조수석과 뒷좌석 왼쪽 빈자리, 문 닫힘"
put "gear?gear=Park"
put "controls?speed=0"
seat passenger false; seat rear_left false; seat rear_right true
door door_fr false; door door_rl false; door door_rr false

say "앱 실행"
"$ADB" shell am start --user 10 -n kr.carescape.multiverse/.MainActivity >/dev/null 2>&1

if [ "${1:-}" = "drive" ]; then
    say "플레이 화면에서 엔터를 누르면 안전 가드 시연을 시작합니다"
    read -r _
    say "뒷좌석 오른쪽 문 열림 (E3 일시정지)"
    door door_rr true; sleep 5
    say "문 닫힘 (자동으로 이어짐)"
    door door_rr false; sleep 5
    say "기어 D (게임 종료, 차량 설정 원상 복구)"
    put "gear?gear=Drive"; sleep 5
    put "gear?gear=Park"
fi
say "끝"
