#!/bin/sh
# Build the launcher, install it on the TV and make it the default Home.
# Usage: TV_HOST=<tv ip> ./install.sh   (TV_PORT defaults to 5555)
set -e
cd "$(dirname "$0")"
TV=tools/tv
(cd launcher && ./gradlew -q assembleDebug)
APK=launcher/app/build/outputs/apk/debug/app-debug.apk
$TV --push "$APK" /data/local/tmp/cleantv.apk
$TV 'pm install -r /data/local/tmp/cleantv.apk; rm /data/local/tmp/cleantv.apk'
# Android 10+ only tells apps the Wi-Fi name if they hold location permission.
$TV 'pm grant tv.clean.launcher android.permission.ACCESS_FINE_LOCATION' || true
$TV 'cmd package set-home-activity tv.clean.launcher/.MainActivity'
$TV 'am start -a android.intent.action.MAIN -c android.intent.category.HOME'
sleep 2
$TV 'dumpsys activity activities | grep -E "mFocusedApp|topResumedActivity"'
