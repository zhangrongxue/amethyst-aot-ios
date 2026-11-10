/*
 * AOTBridge.m - enters the RoboVM-AOT Minecraft image from the Amethyst host app.
 *
 * In the AOT build the RoboVM VM is created by McAotFramework's constructor at
 * app launch. When the player presses Play, JavaLauncher.m calls
 * AOTBridgeLaunchGame() instead of JLI_Launch, and we invoke
 * dev.mcjava.spike.AotMain.main({username, versionId}) on that VM.
 *
 * This keeps GLFW, the GL context and input callbacks inside a single VM.
 */
#import <Foundation/Foundation.h>
#import <dlfcn.h>

#include "jni.h"
#include "environ.h"

typedef jint (*JNI_GetCreatedJavaVMs_t)(JavaVM **, jsize, jsize *);

static JavaVM *aot_vm(void) {
    JNI_GetCreatedJavaVMs_t getVMs = (JNI_GetCreatedJavaVMs_t) dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs");
    if (getVMs == NULL) {
        NSLog(@"[AOT] JNI_GetCreatedJavaVMs missing (framework not loaded?)");
        return NULL;
    }
    JavaVM *vm = NULL;
    jsize count = 0;
    jint res = getVMs(&vm, 1, &count);
    if (res != JNI_OK || count < 1 || vm == NULL) {
        NSLog(@"[AOT] no JavaVM (res=%d count=%d)", (int) res, (int) count);
        return NULL;
    }
    return vm;
}

BOOL AOTBridgeAvailable(void) {
#if defined(AOT_ALWAYS)
    return YES;
#else
    return NO;
#endif
}

int AOTBridgeLaunchGame(const char *username, const char *versionId) {
    JavaVM *vm = aot_vm();
    if (vm == NULL) {
        return -1;
    }
    /* Native callbacks (input bridge, clipboard, egl renderer) go through this. */
    runtimeJavaVMPtr = vm;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
            NSLog(@"[AOT] AttachCurrentThread failed");
            return -1;
        }
    }

    jclass cls = (*env)->FindClass(env, "dev/mcjava/spike/AotMain");
    if (cls == NULL) {
        NSLog(@"[AOT] AotMain not found");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return -1;
    }
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V");
    if (mid == NULL) {
        NSLog(@"[AOT] AotMain.main not found");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return -1;
    }

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray args = (*env)->NewObjectArray(env, 2, strCls, NULL);
    (*env)->SetObjectArrayElement(env, args, 0, (*env)->NewStringUTF(env, username ? username : "Player"));
    (*env)->SetObjectArrayElement(env, args, 1, (*env)->NewStringUTF(env, versionId ? versionId : ""));

    NSLog(@"[AOT] invoking AotMain.main(%s, %s)", username, versionId);
    (*env)->CallStaticVoidMethod(env, cls, mid, args);
    if ((*env)->ExceptionCheck(env)) {
        NSLog(@"[AOT] AotMain.main threw");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return -1;
    }
    return 0;
}
