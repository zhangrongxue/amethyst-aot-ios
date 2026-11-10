package dev.mcjava.spike;

/**
 * Feasibility spike entry point.
 *
 * <p>The class is compiled against a real Minecraft client jar, so RoboVM has to
 * compile the whole static closure of {@code net.minecraft.client.main.Main}:
 * obfuscated MC classes, Guava, Gson, Netty, Log4j, authlib, LWJGL, ...
 *
 * <p>This spike is not meant to boot the game (no GL context). It exists to find
 * out where AOT compilation breaks: missing JDK classes (java.awt), unsupported
 * bytecode, and the JNI surface of LWJGL.
 */
public final class McMain {

    private McMain() {
    }

    public static void main(String[] args) throws Exception {
        // The Amethyst launcher normally sets these before starting the game
        // (PojavLauncher does it from the native launcher UI). GLFW's static
        // initializer reads them directly, so a headless probe has to provide them.
        setIfAbsent("glfw.windowSize", "1280x720");
        setIfAbsent("UIScreen.maximumFramesPerSecond", "60");
        setIfAbsent("glfw.primaryMonitorSize", "1280x720");

        System.out.println("[spike] about to enter net.minecraft.client.main.Main");
        // Static reference: RoboVM follows the closure from here.
        net.minecraft.client.main.Main.main(args);
        System.out.println("[spike] returned from MC main");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
            System.out.println("[spike] " + key + "=" + value);
        }
    }
}
