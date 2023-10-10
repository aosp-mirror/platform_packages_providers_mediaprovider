$ANDROID_BUILD_TOP/external/perfetto/tools/record_android_trace \
  -c $ANDROID_BUILD_TOP/packages/providers/MediaProvider/perfetto_config.pbtx \
  -o /tmp/perfetto-traces/$(date +"%d-%m-%Y_%H-%M-%S").perfetto-trace
