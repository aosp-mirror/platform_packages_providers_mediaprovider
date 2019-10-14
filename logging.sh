#!/bin/bash

level=$1
uid=$(adb shell cat /data/system/packages.list |grep "com.android.providers.media " |cut -b 29-33)

if [ $level == "on" ] || [ $level == "extreme" ]
then
    adb shell setprop log.tag.MediaProvider VERBOSE
    adb shell setprop log.tag.ModernMediaScanner VERBOSE
else
    adb shell setprop log.tag.MediaProvider INFO
    adb shell setprop log.tag.ModernMediaScanner INFO
fi

if [ $level == "extreme" ]
then
    adb shell setprop db.log.slow_query_threshold.$uid 0
    adb shell setprop db.log.bindargs 1
else
    adb shell setprop db.log.slow_query_threshold.$uid 10000
    adb shell setprop db.log.bindargs 0
fi

# Kill process to kick new settings into place
adb shell am force-stop com.android.providers.media
