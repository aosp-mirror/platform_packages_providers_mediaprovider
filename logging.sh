#!/bin/bash

level=$1
uids=$(adb shell cat /data/system/packages.list |grep -Po "providers.media[a-z\.]* \K\d+")

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
    for uid in $uids;
        do adb shell setprop db.log.slow_query_threshold.$uid 0;
    done
    adb shell setprop db.log.bindargs 1
else
    for uid in $uids;
        do adb shell setprop db.log.slow_query_threshold.$uid 10000;
    done
    adb shell setprop db.log.bindargs 0
fi

# Kill process to kick new settings into place
adb shell am force-stop com.android.providers.media
adb shell am force-stop com.android.providers.media.module
adb shell am force-stop com.google.android.providers.media.module
