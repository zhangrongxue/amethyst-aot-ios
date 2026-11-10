/*
 * AOTBridge.m - enters the RoboVM-AOT Minecraft image from the Amethyst host app.
 *
 * When MEOW_AOT is set in the environment, this waits for the app run loop to
 * settle, grabs the JavaVM that McAotFramework created at load time, and calls
 * dev.mcjava.spike.McMain.main(String[]) on it. In the full prototype this is
 * where the launcher would pass the game arguments; for the spike it is empty
 * so we can observe how far the AOT image boots inside the real host app.
 */
#import <Foundation/Foundation.h>
#import <dlfcn.h>

#include "jni.h"

typedef jint (*JNI_GetCreatedJavaVMs_t)(JavaVM **, jsize, jsize *);

static void ame_aot_invoke(JavaVM *vm) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
            NSLog(@"[AOT] AttachCurrentThread failed");
            return;
        }
    }

    jclass cls = (*env)->FindClass(env, "dev/mcjava/spike/McMain");
    if (cls == NULL) {
        NSLog(@"[AOT] class dev.mcjava.spike.McMain not found");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V");
    if (mid == NULL) {
        NSLog(@"[AOT] main(String[]) not found");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return;
    }

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray args = (*env)->NewObjectArray(env, 0, strCls, NULL);

    NSLog(@"[AOT] invoking McMain.main");
    (*env)->CallStaticVoidMethod(env, cls, mid, args);
    if ((*env)->ExceptionCheck(env)) {
        NSLog(@"[AOT] McMain.main threw");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    NSLog(@"[AOT] McMain.main returned");
}

static void ame_aot_start(void) {
    JNI_GetCreatedJavaVMs_t getVMs = (JNI_GetCreatedJavaVMs_t) dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs");
    if (getVMs == NULL) {
        NSLog(@"[AOT] JNI_GetCreatedJavaVMs not available (framework not loaded?)");
        return;
    }
    JavaVM *vm = NULL;
    jsize count = 0;
    jint res = getVMs(&vm, 1, &count);
    if (res != JNI_OK || count < 1 || vm == NULL) {
        NSLog(@"[AOT] no JavaVM created (res=%d, count=%d)", (int) res, (int) count);
        return;
    }
    ame_aot_invoke(vm);
}

__attribute__((constructor))
static void ame_aot_constructor(void) {
#if defined(AOT_ALWAYS)
    const char *enabled = "1"; /* build with the framework linked in */
#else
    const char *enabled = getenv("MEOW_AOT");
#endif
    if (enabled == NULL) {
        return;
    }
    NSLog(@"[AOT] will enter AOT image in 6s");
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t) (6 * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{
        ame_aot_start();
    });
}
