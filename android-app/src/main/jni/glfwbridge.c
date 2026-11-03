/*
 * The way into libglfw.so for the process the game runs in.
 *
 * The GLFW library is not linked here: LWJGL loads it by path, and a second load of the same path gives the same
 * library, so the functions are found in that very copy. They are the plain C entry points of nxglfw.h, which work
 * before the game has called glfwInit, so the surface can be handed over while the VM is still starting.
 */
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>

#define TAG "NexiraGlfw"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct {
    void (*setDisplay)(int, int, float, float);
    void (*attachSurface)(ANativeWindow *);
    void (*postKey)(int, int, int);
    void (*postChar)(unsigned int);
    void (*postCursor)(double, double);
    void (*postMouseButton)(int, int, int);
    void (*postScroll)(double, double);
    void (*postRelativeMotion)(double, double);
    void (*postFocus)(int);
    void (*postCloseRequest)(void);
} glfw;

static int resolve(void *lib, const char *name, void **slot) {
    *slot = dlsym(lib, name);
    if (*slot == NULL) LOGE("%s is missing from the GLFW library", name);
    return *slot != NULL;
}

/* Loads a library by its path so that a later open by name, or by the same path, finds it already there. */
JNIEXPORT jboolean JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_preload(JNIEnv *env, jclass cls, jstring path) {
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
Java_dev_hivens_nexira_host_GlfwBridge_load(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    void *lib = dlopen(chars, RTLD_NOW | RTLD_LOCAL);
    if (lib == NULL) {
        LOGE("dlopen %s failed: %s", chars, dlerror());
        (*env)->ReleaseStringUTFChars(env, path, chars);
        return JNI_FALSE;
    }
    int ok = resolve(lib, "nxglfwSetDisplay", (void **)&glfw.setDisplay)
          && resolve(lib, "nxglfwAttachSurface", (void **)&glfw.attachSurface)
          && resolve(lib, "nxglfwPostKey", (void **)&glfw.postKey)
          && resolve(lib, "nxglfwPostChar", (void **)&glfw.postChar)
          && resolve(lib, "nxglfwPostCursor", (void **)&glfw.postCursor)
          && resolve(lib, "nxglfwPostMouseButton", (void **)&glfw.postMouseButton)
          && resolve(lib, "nxglfwPostScroll", (void **)&glfw.postScroll)
          && resolve(lib, "nxglfwPostRelativeMotion", (void **)&glfw.postRelativeMotion)
          && resolve(lib, "nxglfwPostFocus", (void **)&glfw.postFocus)
          && resolve(lib, "nxglfwPostCloseRequest", (void **)&glfw.postCloseRequest);
    if (ok) LOG("GLFW host entry points from %s", chars);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_setDisplay(JNIEnv *env, jclass cls, jint width, jint height, jfloat hz, jfloat dpi) {
    (void)env; (void)cls;
    if (glfw.setDisplay) glfw.setDisplay(width, height, hz, dpi);
}

/* A null surface says it is gone. The library takes a reference of its own, so the one made here goes at once. */
JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_attachSurface(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    if (!glfw.attachSurface) return;
    if (surface == NULL) {
        glfw.attachSurface(NULL);
        return;
    }
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    glfw.attachSurface(window);
    if (window != NULL) ANativeWindow_release(window);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_postKey(JNIEnv *env, jclass cls, jint code, jint action, jint meta) {
    (void)env; (void)cls;
    if (glfw.postKey) glfw.postKey(code, action, meta);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_postChar(JNIEnv *env, jclass cls, jint codepoint) {
    (void)env; (void)cls;
    if (glfw.postChar) glfw.postChar((unsigned int)codepoint);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_postCursor(JNIEnv *env, jclass cls, jdouble x, jdouble y) {
    (void)env; (void)cls;
    if (glfw.postCursor) glfw.postCursor(x, y);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_postMouseButton(JNIEnv *env, jclass cls, jint button, jint action, jint meta) {
    (void)env; (void)cls;
    if (glfw.postMouseButton) glfw.postMouseButton(button, action, meta);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_postScroll(JNIEnv *env, jclass cls, jdouble dx, jdouble dy) {
    (void)env; (void)cls;
    if (glfw.postScroll) glfw.postScroll(dx, dy);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_postRelativeMotion(JNIEnv *env, jclass cls, jdouble dx, jdouble dy) {
    (void)env; (void)cls;
    if (glfw.postRelativeMotion) glfw.postRelativeMotion(dx, dy);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_postFocus(JNIEnv *env, jclass cls, jboolean focused) {
    (void)env; (void)cls;
    if (glfw.postFocus) glfw.postFocus(focused ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_GlfwBridge_postCloseRequest(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (glfw.postCloseRequest) glfw.postCloseRequest();
}
