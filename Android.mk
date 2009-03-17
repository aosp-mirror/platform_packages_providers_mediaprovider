LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := user

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := 

LOCAL_PACKAGE_NAME := MediaProvider
LOCAL_CERTIFICATE := media

include $(BUILD_PACKAGE)
