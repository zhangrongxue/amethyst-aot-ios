#include <jni.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL Java_dev_mcjava_spike_JniMain_add(JNIEnv *env, jclass cls, jint a, jint b) {
    return a + b;
}
