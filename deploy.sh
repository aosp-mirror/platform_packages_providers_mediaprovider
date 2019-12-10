
# Build both our APK and APEX combined together
./build/soong/soong_ui.bash --make-mode -j64 MediaProviderLegacy com.google.android.mediaprovider

# Push our updated APEX to device, then force apexd to remount it
adb shell stop
adb remount
adb sync
adb shell umount /apex/com.android.mediaprovider*
adb shell setprop ctl.restart apexd
adb shell start
