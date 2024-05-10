`media_provider_cli_wrapper.sh` is a thin wrapper script that exposes MediaProvider CLI.
It's meant to be used similarly to other on-device shell binaries that expose CLIs to Android system
services, such as `am`, `pm`, `wm`, `ime` etc.

For example:
```shell
adb shell media_provider version
```

On device `media_provider` binary is found in `/apex/com.android.mediaprovider/bin/media_provider`,
which, at the moment, is NOT included in `$PATH`, so in order to run `media_provider` you need to
provide the full path to the binary, e.g.:
```shell
adb shell /apex/com.android.mediaprovider/bin/media_provider version
```

If you find yourself using `media_provider` often you may consider settings up an alias, e.g.:
```shell
alias amp="adb shell /apex/com.android.mediaprovider/bin/media_provider"
```
or
```shell
adb root
adb remount
adb shell ln -s -t /system/bin /apex/com.android.mediaprovider/bin/media_provider
```
