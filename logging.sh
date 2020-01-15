#!/bin/bash

level=$1

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
    adb shell setprop log.tag.SQLiteQueryBuilder VERBOSE
else
    adb shell setprop log.tag.SQLiteQueryBuilder INFO
fi

# Kill process to kick new settings into place
adb shell am force-stop com.android.providers.media
adb shell am force-stop com.android.providers.media.module
adb shell am force-stop com.google.android.providers.media.module
