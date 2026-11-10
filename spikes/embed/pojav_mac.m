/*
 * pojav_mac.m - minimal macOS implementation of the "pojav*" host functions that
 * Amethyst's custom GLFW resolves through APIUtil -> Compat.addressOf.
 *
 * Used by the macOS AOT probe: it gives the AOT-compiled Minecraft a real (or at
 * least valid) NSOpenGL context so we can see how far the desktop run goes
 * without an iPhone.
 */
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <OpenGL/gl.h>

#include <jni.h>

static NSOpenGLContext *g_context;

__attribute__((used)) int pojavInit(int useStackQueue) {
    NSLog(@"[pojav-mac] pojavInit(%d)", useStackQueue);
    return 1;
}

__attribute__((used)) int pojavInitOpenGL(void) {
    NSLog(@"[pojav-mac] pojavInitOpenGL");
    return 1;
}

__attribute__((used)) void pojavSetWindowHint(int hint, int value) {
    (void) hint;
    (void) value;
}

__attribute__((used)) long pojavCreateContext(long share) {
    if (g_context == nil) {
        NSOpenGLPixelFormatAttribute attrs[] = {
                NSOpenGLPFAAccelerated,
                NSOpenGLPFADoubleBuffer,
                NSOpenGLPFAColorSize, 24,
                NSOpenGLPFAAlphaSize, 8,
                NSOpenGLPFADepthSize, 24,
                NSOpenGLPFAOpenGLProfile, NSOpenGLProfileVersionLegacy,
                0
        };
        NSOpenGLPixelFormat *format = [[NSOpenGLPixelFormat alloc] initWithAttributes:attrs];
        if (format == nil) {
            NSLog(@"[pojav-mac] no pixel format (headless?)");
            return 0;
        }
        g_context = [[NSOpenGLContext alloc] initWithFormat:format shareContext:nil];
        if (g_context == nil) {
            NSLog(@"[pojav-mac] no OpenGL context");
            return 0;
        }
        [g_context makeCurrentContext];
    }
    NSLog(@"[pojav-mac] context ready: %p", g_context);
    return (long) (__bridge void *) g_context;
}

__attribute__((used)) void pojavMakeCurrent(long context) {
    NSOpenGLContext *ctx = (__bridge NSOpenGLContext *) (void *) context;
    if (ctx != nil) {
        [ctx makeCurrentContext];
    }
}

__attribute__((used)) void pojavSwapBuffers(void) {
    [[NSOpenGLContext currentContext] flushBuffer];
}

__attribute__((used)) void pojavSwapInterval(int interval) {
    GLint value = interval;
    [[NSOpenGLContext currentContext] setValues:&value forParameter:NSOpenGLContextParameterSwapInterval];
}

__attribute__((used)) long pojavGetCurrentContext(void) {
    return (long) (__bridge void *) [NSOpenGLContext currentContext];
}

/* JNI natives that the AOT image cannot resolve without the iOS app binary. */
__attribute__((used)) JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(JNIEnv *env, jclass cls, jboolean grab) {
    (void) env;
    (void) cls;
    (void) grab;
}

__attribute__((used)) JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv *env, jclass cls, jint action, jbyteArray copy) {
    (void) env;
    (void) cls;
    (void) action;
    (void) copy;
    return NULL;
}

#define MAC_GLFW_CALLBACK(NAME) \
    JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback(JNIEnv *env, jclass cls, jlong window, jlong callback) { \
        (void) env; (void) cls; (void) window; (void) callback; return 0; \
    }
MAC_GLFW_CALLBACK(Char)
MAC_GLFW_CALLBACK(CharMods)
MAC_GLFW_CALLBACK(CursorEnter)
MAC_GLFW_CALLBACK(CursorPos)
MAC_GLFW_CALLBACK(FramebufferSize)
MAC_GLFW_CALLBACK(Key)
MAC_GLFW_CALLBACK(MouseButton)
MAC_GLFW_CALLBACK(Scroll)
MAC_GLFW_CALLBACK(WindowSize)
#undef MAC_GLFW_CALLBACK

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(JNIEnv *env, jclass cls, jlong window) {
    (void) env;
    (void) cls;
    (void) window;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, jclass cls, jlong window, jobject xpos, jobject ypos) {
    (void) env;
    (void) cls;
    (void) window;
    (void) xpos;
    (void) ypos;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, jclass cls, jlong window, jdoubleArray xpos, jdoubleArray ypos) {
    (void) env;
    (void) cls;
    (void) window;
    (void) xpos;
    (void) ypos;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(JNIEnv *env, jclass cls, jlong window, jdouble x, jdouble y) {
    (void) env;
    (void) cls;
    (void) window;
    (void) x;
    (void) y;
}
