/*
 * Starts a HotSpot JVM inside this process.
 *
 * The JVM is the one built for bionic (see the JDK build notes), loaded with dlopen
 * from a java.home laid out the usual way, and started through JNI_CreateJavaVM
 * rather than the java launcher. The launcher is not used because an app process
 * cannot exec a binary from its data directory.
 *
 * ART is already running in this process, so the JVM is a second runtime beside it.
 * Three things follow from that and are handled here:
 *
 *  - The heap pointer tag. HotSpot keeps addresses as plain integers and a tagged
 *    pointer is a different integer, so tagging is switched off before the VM exists.
 *  - Output. The game's stdout and stderr are read from a pipe and handed to the
 *    Kotlin side line by line, as bytes, because the JVM does not promise valid
 *    UTF-8 and ART aborts on a malformed string.
 *  - Exit. A System.exit inside the VM would end the whole process before anyone was
 *    told the code, so the VM is given an exit hook that reports it first.
 */
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <malloc.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "NexiraJvm"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* The Android jni.h stops at 1.6, and HotSpot reads the version it is given. 8 knows nothing past 1.8. */
#define JNI_VERSION_JAVA8 0x00010008
#define JNI_VERSION_JAVA10 0x000a0000

typedef jint (*CreateJavaVM)(JavaVM **, void **, void *);

static JavaVM *g_art = NULL;      /* the VM this process was started with */
static JavaVM *g_game = NULL;     /* the VM created here */
static jobject g_listener = NULL; /* Kotlin: onLine(byte[]), onExit(int) */
static jmethodID g_on_line = NULL;
static jmethodID g_on_exit = NULL;

static JNIEnv *art_env(void) {
    JNIEnv *env = NULL;
    if ((*g_art)->GetEnv(g_art, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_art)->AttachCurrentThread(g_art, &env, NULL) != JNI_OK) return NULL;
    return env;
}

static void emit_line(const char *line, size_t length) {
    __android_log_write(ANDROID_LOG_INFO, "NexiraJvm.out", line);
    if (g_listener == NULL) return;
    JNIEnv *env = art_env();
    if (env == NULL) return;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (bytes == NULL) return;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)line);
    (*env)->CallVoidMethod(env, g_listener, g_on_line, bytes);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, bytes);
}

/* Reads the pipe that stdout and stderr were pointed at and forwards each line. */
static void *pump(void *arg) {
    int fd = (int)(intptr_t)arg;
    char line[8192];
    size_t used = 0;
    char ch;
    while (read(fd, &ch, 1) == 1) {
        if (ch == '\n' || used == sizeof(line) - 1) {
            line[used] = '\0';
            emit_line(line, used);
            used = 0;
            if (ch != '\n') line[used++] = ch;
        } else {
            line[used++] = ch;
        }
    }
    if (used > 0) {
        line[used] = '\0';
        emit_line(line, used);
    }
    return NULL;
}

static void forward_stdio(void) {
    int fds[2];
    if (pipe(fds) != 0) return;
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    pthread_t thread;
    pthread_create(&thread, NULL, pump, (void *)(intptr_t)fds[0]);
    pthread_detach(thread);
}

/*
 * Called by the VM instead of exit(). Tells the Kotlin side the code, gives the
 * message a moment to leave the process, then ends it: a process that returned from
 * here would carry on running a VM that has already shut down.
 */
static void JNICALL vm_exit(jint code) {
    LOG("the game VM exits with %d", code);
    JNIEnv *env = art_env();
    if (env != NULL && g_listener != NULL) {
        (*env)->CallVoidMethod(env, g_listener, g_on_exit, code);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    usleep(300000);
    _exit(code);
}

/*
 * Loads a library of the runtime by its path, so that a library that names it as a dependency finds it
 * already loaded: the loader of an app looks in the app's own directories and not beside the VM.
 */
static void preload(const char *home, const char *relative) {
    char path[4096];
    snprintf(path, sizeof(path), "%s/%s", home, relative);
    if (access(path, R_OK) != 0) return;
    if (dlopen(path, RTLD_NOW | RTLD_GLOBAL) == NULL) {
        LOGE("dlopen %s failed: %s", path, dlerror());
    } else {
        LOG("preloaded %s", path);
    }
}

static char *utf(JNIEnv *env, jstring s) {
    const char *chars = (*env)->GetStringUTFChars(env, s, NULL);
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, s, chars);
    return copy;
}

JNIEXPORT jint JNICALL
Java_dev_hivens_nexira_host_JvmHost_run(JNIEnv *env, jclass cls, jstring javaHome, jobjectArray options,
                                         jstring mainClass, jobjectArray args, jobject listener) {
    (void)cls;
    (*env)->GetJavaVM(env, &g_art);
    g_listener = (*env)->NewGlobalRef(env, listener);
    jclass listenerClass = (*env)->GetObjectClass(env, listener);
    g_on_line = (*env)->GetMethodID(env, listenerClass, "onLine", "([B)V");
    g_on_exit = (*env)->GetMethodID(env, listenerClass, "onExit", "(I)V");

    forward_stdio();

    /* Before the first allocation the JVM makes: later ones come back untagged. */
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);

    char *home = utf(env, javaHome);
    char path[4096];
    /* 9 and later keep the VM under lib/server. 8 keeps it, and every library with it, under lib/aarch64. */
    snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", home);
    int java8 = 0;
    if (access(path, R_OK) != 0) {
        snprintf(path, sizeof(path), "%s/lib/aarch64/server/libjvm.so", home);
        java8 = 1;
        /* The C++ runtime and FreeType that 8's VM and font library are linked against travel in its image. */
        preload(home, "lib/aarch64/libc++_shared.so");
        preload(home, "lib/aarch64/libfreetype.so");
    }

    void *lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (lib == NULL) {
        LOGE("dlopen %s failed: %s", path, dlerror());
        return -1;
    }
    CreateJavaVM create = (CreateJavaVM)dlsym(lib, "JNI_CreateJavaVM");
    if (create == NULL) {
        LOGE("no JNI_CreateJavaVM in %s: %s", path, dlerror());
        return -2;
    }
    LOG("loaded %s", path);

    jsize count = (*env)->GetArrayLength(env, options);
    JavaVMOption *vmOptions = calloc((size_t)count + 1, sizeof(JavaVMOption));
    for (jsize i = 0; i < count; i++) {
        jstring o = (jstring)(*env)->GetObjectArrayElement(env, options, i);
        vmOptions[i].optionString = utf(env, o);
    }
    vmOptions[count].optionString = "exit";
    vmOptions[count].extraInfo = (void *)vm_exit;

    JavaVMInitArgs vmArgs;
    memset(&vmArgs, 0, sizeof(vmArgs));
    vmArgs.version = java8 ? JNI_VERSION_JAVA8 : JNI_VERSION_JAVA10;
    vmArgs.nOptions = count + 1;
    vmArgs.options = vmOptions;
    vmArgs.ignoreUnrecognized = JNI_FALSE;

    JNIEnv *jenv = NULL;
    jint rc = create(&g_game, (void **)&jenv, &vmArgs);
    if (rc != JNI_OK) {
        LOGE("JNI_CreateJavaVM returned %d", rc);
        return rc;
    }
    LOG("JVM created");

    char *mainName = utf(env, mainClass);
    jclass main = (*jenv)->FindClass(jenv, mainName);
    if (main == NULL) {
        LOGE("main class %s not found", mainName);
        (*jenv)->ExceptionDescribe(jenv);
        return -3;
    }
    jmethodID mainMethod = (*jenv)->GetStaticMethodID(jenv, main, "main", "([Ljava/lang/String;)V");
    if (mainMethod == NULL) {
        LOGE("no main in %s", mainName);
        return -4;
    }

    jsize argc = (*env)->GetArrayLength(env, args);
    jclass stringClass = (*jenv)->FindClass(jenv, "java/lang/String");
    jobjectArray jargs = (*jenv)->NewObjectArray(jenv, argc, stringClass, NULL);
    for (jsize i = 0; i < argc; i++) {
        char *a = utf(env, (jstring)(*env)->GetObjectArrayElement(env, args, i));
        (*jenv)->SetObjectArrayElement(jenv, jargs, i, (*jenv)->NewStringUTF(jenv, a));
        free(a);
    }

    (*jenv)->CallStaticVoidMethod(jenv, main, mainMethod, jargs);
    jint result = 0;
    if ((*jenv)->ExceptionCheck(jenv)) {
        (*jenv)->ExceptionDescribe(jenv);
        result = 1;
    }
    LOG("main returned %d", result);
    /* What the java launcher does after main: wait for the non-daemon threads and run the shutdown hooks. */
    (*g_game)->DestroyJavaVM(g_game);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_hivens_nexira_host_JvmHost_enterDirectory(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    char *dir = utf(env, path);
    int ok = chdir(dir) == 0;
    if (!ok) LOGE("chdir %s failed", dir);
    free(dir);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static void *exit_thread(void *arg) {
    (void)arg;
    JNIEnv *jenv = NULL;
    if (g_game == NULL || (*g_game)->AttachCurrentThreadAsDaemon(g_game, (void **)&jenv, NULL) != JNI_OK) return NULL;
    jclass system = (*jenv)->FindClass(jenv, "java/lang/System");
    if (system == NULL) return NULL;
    jmethodID exitMethod = (*jenv)->GetStaticMethodID(jenv, system, "exit", "(I)V");
    if (exitMethod != NULL) (*jenv)->CallStaticVoidMethod(jenv, system, exitMethod, 0);
    return NULL;
}

/*
 * Asks the game to exit the way a SIGTERM would: System.exit(0) on a thread of its own,
 * so its shutdown hooks run and it can save. The caller escalates if nothing happens.
 */
JNIEXPORT void JNICALL
Java_dev_hivens_nexira_host_JvmHost_requestExit(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    pthread_t thread;
    if (pthread_create(&thread, NULL, exit_thread, NULL) == 0) pthread_detach(thread);
}
