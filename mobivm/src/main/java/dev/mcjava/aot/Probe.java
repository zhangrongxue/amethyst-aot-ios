package dev.mcjava.aot;

/**
 * A reflective entry point that only exists in the AOT image if
 * {@code forceLinkClasses} kept it. Reaching it through {@link Class#forName}
 * is exactly the pattern the reflection scanner looks for in Minecraft jars.
 */
public final class Probe {

    public static final int VERSION = 7;

    private Probe() {
    }

    public static String run() {
        return "probe-ok:" + VERSION;
    }
}
