LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
	src/com/android/providers/media/IMtpService.aidl

LOCAL_JAVA_LIBRARIES := 

LOCAL_PACKAGE_NAME := MediaProvider
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := media
LOCAL_PRIVILEGED_MODULE := true

include $(BUILD_PACKAGE)

include $(call all-makefiles-under, $(LOCAL_PATH))
