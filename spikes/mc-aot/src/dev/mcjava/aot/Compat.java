package dev.mcjava.aot;

/**
 * Java 7 compatible replacements for Java 8 APIs missing from RoboVM's runtime.
 *
 * <p>{@code Java8Shim} rewrites bytecode calls to these helpers before AOT
 * compilation. The classes live on the AOT classpath (this package is part of
 * the spike payload).
 */
public final class Compat {

    private Compat() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ThreadLocal threadLocalWithInitial(final java.util.function.Supplier supplier) {
        final java.util.function.Supplier sup = supplier;
        return new ThreadLocal() {
            @Override
            protected Object initialValue() {
                return sup.get();
            }
        };
    }

    /**
     * Replacement for {@code APIUtil.apiGetFunctionAddress(FunctionProvider, String)}:
     * the host app exports the native functions, so a process-wide dlsym is enough.
     */
    public static native long addressOf(Object provider, String name);

    /** Replacement for {@code APIUtil.apiGetFunctionAddressOptional(SharedLibrary, String)}. */
    public static native long addressOptional(Object provider, String name);

    /**
     * Replacement for {@code System.load(String)}: RoboVM cannot dlopen a JNI
     * library, and it throws UnsupportedOperationException rather than the
     * UnsatisfiedLinkError the Amethyst code expects. No-op keeps <clinit>
     * running; the native entry points are resolved through {@link #addressOf}.
     */
    public static void loadNoop(String path) {
        System.out.println("[Compat] skipping System.load(" + path + ")");
    }

    /** Replacement for {@code System.loadLibrary(String)}. */
    public static void loadLibraryNoop(String name) {
        System.out.println("[Compat] skipping System.loadLibrary(" + name + ")");
    }

    /**
     * Replacement for MemoryUtil.getFieldOffsetObject(Object, Object).
     *
     * RoboVM NIO buffers (Android libcore) have no parent/attachment field, so
     * LWJGL cannot discover the offset it uses to keep sliced buffers alive.
     * Returning 0 lets the static initializer finish; the buffer slicing paths
     * that really need the attachment are not used by the MC 1.8.9 hot path.
     */
    public static long fieldOffsetObject(Object container, Object value) {
        return 0L;
    }
}
