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
        System.out.println("[spike] about to enter net.minecraft.client.main.Main");
        // Static reference: RoboVM follows the closure from here.
        net.minecraft.client.main.Main.main(args);
        System.out.println("[spike] returned from MC main");
    }
}
