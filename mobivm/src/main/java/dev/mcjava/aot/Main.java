package dev.mcjava.aot;

import com.google.gson.Gson;
import dev.mcjava.aot.model.VersionManifest;

/**
 * Headless "preflight" workload: the kind of work the launcher does before it
 * starts the JVM for the game (parse a version manifest, resolve libraries,
 * sanity check the runtime).
 *
 * <p>It is deliberately reflection-heavy:
 * <ul>
 *   <li>{@link Class#forName} on {@link Probe} - only works when the AOT image
 *       force-links it (see robovm.xml / force-link-classes.txt).</li>
 *   <li>Gson deserialization - reflection over fields and Unsafe allocation.</li>
 *   <li>{@link Class#getDeclaredMethod} invocation on a looked-up class.</li>
 * </ul>
 *
 * <p>Output is deterministic so the CI can diff the JVM run against the AOT run.
 */
public final class Main {

    private static final String SAMPLE_MANIFEST = "{"
            + "\"id\":\"1.12.2\","
            + "\"type\":\"release\","
            + "\"mainClass\":\"net.minecraft.client.main.Main\","
            + "\"libraries\":["
            + "{\"name\":\"org.lwjgl.lwjgl:lwjgl:2.9.4\",\"url\":\"https://libraries.minecraft.net/\"},"
            + "{\"name\":\"com.google.code.gson:gson:2.8.0\",\"url\":\"https://libraries.minecraft.net/\"},"
            + "{\"name\":\"net.java.jinput:jinput:2.0.5\",\"url\":\"https://libraries.minecraft.net/\"}"
            + "]}";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String json = SAMPLE_MANIFEST;
        if (args.length > 0) {
            java.nio.file.Path p = java.nio.file.Paths.get(args[0]);
            json = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        }

        // (1) dynamic class lookup + reflective method invocation
        Class<?> probeClass = Class.forName("dev.mcjava.aot.Probe");
        java.lang.reflect.Method run = probeClass.getDeclaredMethod("run");
        String probe = (String) run.invoke(null);

        // (2) reflective field access through Gson
        Gson gson = new Gson();
        VersionManifest manifest = gson.fromJson(json, VersionManifest.class);

        // (3) a small amount of work so the AOT binary is doing something measurable
        long hash = 17;
        for (dev.mcjava.aot.model.Library lib : manifest.getLibraries()) {
            hash = hash * 31 + lib.getName().hashCode();
        }
        hash = hash * 31 + manifest.getId().hashCode();

        System.out.println("MCJAVA-AOT id=" + manifest.getId()
                + " type=" + manifest.getType()
                + " main=" + manifest.getMainClass()
                + " libs=" + manifest.getLibraries().size()
                + " probe=" + probe
                + " hash=" + Long.toHexString(hash));
    }
}
