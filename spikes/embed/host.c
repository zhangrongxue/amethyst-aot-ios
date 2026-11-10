/*
 * host.c - macOS embedding harness for McAotFramework.
 *
 * Mirrors Natives/AOTBridge.m: dlopen the RoboVM framework (its constructor
 * creates the RoboVM VM), fetch the JavaVM through JNI_GetCreatedJavaVMs, then
 * invoke dev.mcjava.spike.McMain.main(String[]) with an empty argument array.
 *
 * If this prints "[host] McMain returned/threw" we know the embedding path works
 * independently of iOS specifics.
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <jni.h>

typedef jint (*GetCreatedJavaVMsFn)(JavaVM **, jsize, jsize *);

/* ---------------------------------------------------------------------------
 * Amethyst host-side functions normally provided by the app binary. The AOT
 * image resolves them through Compat.addressOf() -> dlsym(RTLD_DEFAULT, ...).
 * ------------------------------------------------------------------------- */
__attribute__((used)) int pojavInit(int flag) {
    printf("[host] pojavInit(%d)\n", flag);
    return 1;
}

__attribute__((used)) long pojavCreateContext(long share) {
    printf("[host] pojavCreateContext(%ld)\n", (long) share);
    return 0x1111;
}

__attribute__((used)) void pojavMakeCurrent(long context) {
    printf("[host] pojavMakeCurrent(%ld)\n", (long) context);
}

__attribute__((used)) void pojavSwapBuffers(long context) {
    printf("[host] pojavSwapBuffers(%ld)\n", (long) context);
}

__attribute__((used)) long pojavGetCurrentContext(void) {
    return 0x1111;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <McAotFramework-binary>\n", argv[0]);
        return 2;
    }

    void *handle = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        fprintf(stderr, "[host] dlopen failed: %s\n", dlerror());
        return 1;
    }
    printf("[host] framework loaded\n");

    GetCreatedJavaVMsFn getVMs = (GetCreatedJavaVMsFn) dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs");
    if (getVMs == NULL) {
        fprintf(stderr, "[host] JNI_GetCreatedJavaVMs missing\n");
        return 1;
    }

    JavaVM *vm = NULL;
    jsize count = 0;
    jint res = getVMs(&vm, 1, &count);
    printf("[host] JNI_GetCreatedJavaVMs res=%d count=%d\n", (int) res, (int) count);
    if (res != JNI_OK || count < 1 || vm == NULL) {
        fprintf(stderr, "[host] no VM\n");
        return 1;
    }

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, (void **) &env, NULL) != JNI_OK) {
            fprintf(stderr, "[host] attach failed\n");
            return 1;
        }
    }

    jclass cls = (*env)->FindClass(env, "dev/mcjava/spike/McMain");
    if (cls == NULL) {
        fprintf(stderr, "[host] McMain not found\n");
        (*env)->ExceptionDescribe(env);
        return 1;
    }
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V");
    if (mid == NULL) {
        fprintf(stderr, "[host] main() not found\n");
        (*env)->ExceptionDescribe(env);
        return 1;
    }
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray args = (*env)->NewObjectArray(env, 0, strCls, NULL);

    printf("[host] invoking McMain.main\n");
    fflush(stdout);
    (*env)->CallStaticVoidMethod(env, cls, mid, args);
    if ((*env)->ExceptionCheck(env)) {
        fprintf(stderr, "[host] McMain.main threw\n");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return 3;
    }
    printf("[host] McMain.main returned\n");
    return 0;
}
