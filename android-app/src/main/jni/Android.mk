LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := nexirahost
LOCAL_SRC_FILES := jvmhost.c glfwbridge.c
LOCAL_LDLIBS    := -llog -ldl -landroid
include $(BUILD_SHARED_LIBRARY)
