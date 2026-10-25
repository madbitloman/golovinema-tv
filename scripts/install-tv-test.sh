#!/bin/bash
set -euo pipefail
tv_root="$(cd "$(dirname "$0")/.." && pwd)"
tv_adb="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"
tv_apk="$tv_root/dist/cinema.apk"
tv_address="${1:-}"
if [ ! -x "$tv_adb" ]; then printf 'ADB was not found: %s\n' "$tv_adb"; exit 1; fi
if [ ! -f "$tv_apk" ]; then printf 'Build the APK first: bash scripts/build-tv-test.sh\n'; exit 1; fi
if [ -z "$tv_address" ]; then read -r -p 'TV IP address (from Network / Status): ' tv_address; fi
if [[ ! "$tv_address" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]{1,5})?$ ]]; then
    printf 'Enter the TV IPv4 address, for example 192.168.1.50 (optionally :5555).\n'; exit 1
fi
if [[ "$tv_address" != *:* ]]; then tv_address="$tv_address:5555"; fi
printf '\nConnecting to %s. Keep the TV awake.\n' "$tv_address"
tv_connect_status=0
tv_connect_output="$("$tv_adb" connect "$tv_address" 2>&1)" || tv_connect_status=$?
printf '%s\n' "$tv_connect_output"
# ADB can report connection failure while returning exit status zero. Do not
# wait for a TV authorization prompt until a connection actually exists.
case "$tv_connect_output" in
    *authenticate*|*unauthorized*) ;;
    *"failed to connect"*|*"unable to connect"*|*"cannot connect"*|*"Network is unreachable"*|*"No route to host"*|*"Connection refused"*|*"Operation timed out"*)
        printf '\nThe Mac could not reach the TV debugging service; no authorization prompt is expected yet.\n'
        printf 'Recheck the TV IP in Network / Status and put the TV and Mac on the same home network.\n'
        printf 'For connection refused, check USB/ADB debugging. Send the connection error back if it persists.\n'
        exit 1 ;;
    *)
        if [ "$tv_connect_status" -ne 0 ]; then
            printf '\nADB could not connect. Send the error above back before continuing.\n'
            exit "$tv_connect_status"
        fi ;;
esac
printf '\nIf the TV shows “Allow USB debugging?”, approve this Mac.\n'
tv_connected=false
for ((tv_try=0; tv_try<30; tv_try++)); do
    if [ "$("$tv_adb" -s "$tv_address" get-state 2>/dev/null || true)" = device ]; then tv_connected=true; break; fi
    sleep 2
done
if [ "$tv_connected" != true ]; then
    printf '\nNo authorized connection. Check the TV IP, debugging setting and same-network connection.\n'
    printf 'If it says connection refused, send that output back; this TV may need another sideload method.\n'
    exit 1
fi
printf '\nInstalling GOLOVINEMA…\n'
"$tv_adb" -s "$tv_address" install -r "$tv_apk"
"$tv_adb" -s "$tv_address" shell am start -n local.tv.streamtest/.CatalogActivity
printf '\nOn the TV:\n  Browse posters with the remote. OK opens details; Play starts the movie.\n  Search is in the top bar. Save movies with + My List.\n  Back returns to the catalog; playback position is saved automatically.\n'
sleep 3
"$tv_adb" -s "$tv_address" exec-out screencap -p > "$tv_root/dist/cinema-home.png" || true
printf '\nLaunch screenshot: dist/cinema-home.png\nApp diagnostics: dist/tv-playback.log\nCtrl+C stops logging; GOLOVINEMA stays installed.\n'
"$tv_adb" -s "$tv_address" logcat -v time -T 200 'TvStreamTest:I' 'Cinema:I' '*:S' | tee "$tv_root/dist/tv-playback.log"
