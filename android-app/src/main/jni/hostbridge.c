/*
 * The way into the host core (libnxhost.so) for the process the game runs in.
 *
 * The core is not linked here: the stand-ins for GLFW and SDL that the game loads depend on it by name, so it is opened
 * by its path first and what they ask for by name finds it already loaded. The functions are the plain C entry points
 * of nxhost.h, which work before the game has started anything, so the surface can be handed over while the VM is
 * still coming up.
 */
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>

#define TAG "NexiraHost"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct {
    void (*setDisplay)(int, int, float, float);
    void (*attachSurface)(ANativeWindow *);
    void (*postKey)(int, int, int);
    void (*postText)(unsigned int);
    void (*postPointerMove)(double, double, int);
    void (*postPointerButton)(int, int, int, int);
    void (*postScroll)(double, double);
    void (*postRelativeMotion)(double, double, int);
    void (*postTouch)(int, int, double, double, double);
    void (*postFocus)(int);
    void (*postClose)(void);
} core;

static int resolve(void *lib, const char *name, void **slot) {
    *slot = dlsym(lib, name);
    if (*slot == NULL) LOGE("%s is missing from the host core", name);
    return *slot != NULL;
}

/* Loads a library by its path so that a later open by name, or by the same path, finds it already there. */
JNIEXPORT jboolean JNICALL
Java_dev_hivens_nexira_host_HostBridge_preload(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    void *lib = dlopen(chars, RTLD_NOW | RTLD_LOCAL);
    if (lib == NULL) {
        LOGE("dlopen %s failed: %s", chars, dlerror());
    } else {
        LOG("preloaded %s", chars);
    }
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return lib != NULL;
}

JNIEXPORT jboolean JNICALL
Java_dev_hivens_nexira_host_HostBridge_load(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    void *lib = dlopen(chars, RTLD_NOW | RTLD_LOCAL);
    if (lib == NULL) {
        LOGE("dlopen %s failed: %s", chars, dlerror());
        (*env)->ReleaseStringUTFChars(env, path, chars);
        return JNI_FALSE;
    }
    int ok = resolve(lib, "nxhostSetDisplay", (void **)&core.setDisplay)
          && resolve(lib, "nxhostAttachSurface", (void **)&core.attachSurface)
          && resolve(lib, "nxhostPostKey", (void **)&core.postKey)
          && resolve(lib, "nxhostPostText", (void **)&core.postText)
          && resolve(lib, "nxhostPostPointerMove", (void **)&core.postPointerMove)
          && resolve(lib, "nxhostPostPointerButton", (void **)&core.postPointerButton)
          && resolve(lib, "nxhostPostScroll", (void **)&core.postScroll)
          && resolve(lib, "nxhostPostRelativeMotion", (void **)&core.postRelativeMotion)
          && resolve(lib, "nxhostPostTouch", (void **)&core.postTouch)
          && resolve(lib, "nxhostPostFocus", (void **)&core.postFocus)
          && resolve(lib, "nxhostPostClose", (void **)&core.postClose);
    if (ok) LOG("host core entry points from %s", chars);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_setDisplay(JNIEnv *env, jclass cls, jint width, jint height, jfloat hz, jfloat dpi) {
    (void)env; (void)cls;
    if (core.setDisplay) core.setDisplay(width, height, hz, dpi);
}

/* A null surface says it is gone. The core takes a reference of its own, so the one made here goes at once. */
JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_attachSurface(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    if (!core.attachSurface) return;
    if (surface == NULL) {
        core.attachSurface(NULL);
        return;
    }
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    core.attachSurface(window);
    if (window != NULL) ANativeWindow_release(window);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postKey(JNIEnv *env, jclass cls, jint code, jint action, jint meta) {
    (void)env; (void)cls;
    if (core.postKey) core.postKey(code, action, meta);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postText(JNIEnv *env, jclass cls, jint codepoint) {
    (void)env; (void)cls;
    if (core.postText) core.postText((unsigned int)codepoint);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postPointerMove(JNIEnv *env, jclass cls, jdouble x, jdouble y, jint source) {
    (void)env; (void)cls;
    if (core.postPointerMove) core.postPointerMove(x, y, source);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postPointerButton(JNIEnv *env, jclass cls, jint button, jint action, jint meta, jint source) {
    (void)env; (void)cls;
    if (core.postPointerButton) core.postPointerButton(button, action, meta, source);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postScroll(JNIEnv *env, jclass cls, jdouble dx, jdouble dy) {
    (void)env; (void)cls;
    if (core.postScroll) core.postScroll(dx, dy);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postRelativeMotion(JNIEnv *env, jclass cls, jdouble dx, jdouble dy, jint source) {
    (void)env; (void)cls;
    if (core.postRelativeMotion) core.postRelativeMotion(dx, dy, source);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postTouch(JNIEnv *env, jclass cls, jint finger, jint action, jdouble x, jdouble y, jdouble pressure) {
    (void)env; (void)cls;
    if (core.postTouch) core.postTouch(finger, action, x, y, pressure);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postFocus(JNIEnv *env, jclass cls, jboolean focused) {
    (void)env; (void)cls;
    if (core.postFocus) core.postFocus(focused ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_HostBridge_postClose(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (core.postClose) core.postClose();
}
