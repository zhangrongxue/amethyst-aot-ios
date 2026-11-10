/*
 * AOTBridge.m - enters the RoboVM-AOT Minecraft image from the Amethyst host app.
 *
 * In the AOT build the RoboVM VM is created by McAotFramework's constructor at
 * app launch. When the player presses Play, JavaLauncher.m calls
 * AOTBridgeLaunchGame() instead of JLI_Launch, and we invoke
 * dev.mcjava.spike.AotMain.main({username, versionId}) on that VM.
 *
 * The host app implements its JNI functions as local Mach-O symbols (they are
 * not in the export trie), and registers some of them through JNI_OnLoad. RoboVM
 * neither searches the executable image nor calls JNI_OnLoad, so we resolve the
 * symbols from the loaded Mach-O symbol tables and register them ourselves.
 */
#import <Foundation/Foundation.h>
#import <dlfcn.h>

#include <string.h>

#include <mach-o/dyld.h>
#include <mach-o/loader.h>
#include <mach-o/nlist.h>

#include "jni.h"
#include "environ.h"

typedef jint (*JNI_GetCreatedJavaVMs_t)(JavaVM **, jsize, jsize *);

/* ------------------------------------------------------------------ */
/* Mach-O symbol lookup (host functions live in the executable's symtab) */
/* ------------------------------------------------------------------ */

static void *host_symbol(const char *name) {
    uint32_t count = _dyld_image_count();
    for (uint32_t i = 0; i < count; i++) {
        const struct mach_header_64 *hdr = (const struct mach_header_64 *) _dyld_get_image_header(i);
        if (hdr == NULL || hdr->magic != MH_MAGIC_64) {
            continue;
        }
        intptr_t slide = _dyld_get_image_vmaddr_slide(i);
        const uint8_t *base = (const uint8_t *) hdr;
        const struct load_command *lc = (const struct load_command *) (base + sizeof(struct mach_header_64));
        const struct symtab_command *symtab = NULL;
        for (uint32_t c = 0; c < hdr->ncmds; c++) {
            if (lc->cmd == LC_SYMTAB) {
                symtab = (const struct symtab_command *) lc;
                break;
            }
            lc = (const struct load_command *) ((const uint8_t *) lc + lc->cmdsize);
        }
        if (symtab == NULL) {
            continue;
        }
        const struct nlist_64 *syms = (const struct nlist_64 *) (base + symtab->symoff);
        const char *strs = (const char *) (base + symtab->stroff);
        for (uint32_t k = 0; k < symtab->nsyms; k++) {
            uint32_t strx = syms[k].n_un.n_strx;
            if (strx == 0 || strx >= symtab->strsize) {
                continue;
            }
            const char *s = strs + strx;
            if (strcmp(s, name) == 0 || (s[0] == '_' && strcmp(s + 1, name) == 0)) {
                return (void *) (syms[k].n_value + slide);
            }
        }
    }
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Native registration                                                  */
/* ------------------------------------------------------------------ */

typedef struct {
    const char *className;
    const char *methodName;
    const char *signature;
    const char *symbolName;
} AOTNativeEntry;

#define GLFW_CB(name) {"org/lwjgl/glfw/GLFW", "nglfwSet" name "Callback", "(JJ)J", "Java_org_lwjgl_glfw_GLFW_nglfwSet" name "Callback"}

static const AOTNativeEntry AOT_NATIVES[] = {
        {"org/lwjgl/glfw/CallbackBridge", "nativeClipboard", "(I[B)Ljava/lang/String;",
                "Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard"},
        {"org/lwjgl/glfw/CallbackBridge", "nativeSetGrabbing", "(Z)V",
                "Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing"},
        {"net/kdt/pojavlaunch/uikit/UIKit", "showError", "(Ljava/lang/String;Ljava/lang/String;Z)V",
                "Java_net_kdt_pojavlaunch_uikit_UIKit_showError"},

        GLFW_CB("Char"),
        GLFW_CB("CharMods"),
        GLFW_CB("CursorEnter"),
        GLFW_CB("CursorPos"),
        GLFW_CB("FramebufferSize"),
        GLFW_CB("Key"),
        GLFW_CB("MouseButton"),
        GLFW_CB("Scroll"),
        GLFW_CB("WindowSize"),

        {"org/lwjgl/glfw/GLFW", "nglfwSetShowingWindow", "(J)V",
                "Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow"},
        {"org/lwjgl/glfw/GLFW", "nglfwGetCursorPos", "(JLjava/nio/DoubleBuffer;Ljava/nio/DoubleBuffer;)V",
                "Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos"},
        {"org/lwjgl/glfw/GLFW", "nglfwGetCursorPosA", "(J[D[D)V",
                "Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA"},
        {"org/lwjgl/glfw/GLFW", "glfwSetCursorPos", "(JDD)V",
                "Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos"},
};

static void aot_register_host_natives(JNIEnv *env) {
    size_t n = sizeof(AOT_NATIVES) / sizeof(AOT_NATIVES[0]);
    int registered = 0;
    for (size_t i = 0; i < n; i++) {
        const AOTNativeEntry *e = &AOT_NATIVES[i];
        void *fn = host_symbol(e->symbolName);
        if (fn == NULL) {
            NSLog(@"[AOT] host symbol missing: %s", e->symbolName);
            continue;
        }
        jclass cls = (*env)->FindClass(env, e->className);
        if (cls == NULL) {
            NSLog(@"[AOT] class missing: %s", e->className);
            (*env)->ExceptionClear(env);
            continue;
        }
        JNINativeMethod method = {(char *) e->methodName, (char *) e->signature, fn};
        if ((*env)->RegisterNatives(env, cls, &method, 1) != JNI_OK) {
            NSLog(@"[AOT] RegisterNatives failed: %s.%s", e->className, e->methodName);
            (*env)->ExceptionClear(env);
            continue;
        }
        registered++;
    }
    NSLog(@"[AOT] registered %d/%zu host natives", registered, n);
}

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
    runtimeJNIEnvPtr = env;

    aot_register_host_natives(env);

    /* Populate the GLFW globals the host input bridge writes into. */
    jint (*onLoadGLFW)(void) = (jint (*)(void)) host_symbol("JNI_OnLoadGLFW");
    if (onLoadGLFW != NULL) {
        NSLog(@"[AOT] calling host JNI_OnLoadGLFW");
        onLoadGLFW();
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
