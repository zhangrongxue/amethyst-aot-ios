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
}
