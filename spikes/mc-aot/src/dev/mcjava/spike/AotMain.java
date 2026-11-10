package dev.mcjava.spike;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

/**
 * Single-VM game bootstrap.
 *
 * Amethyst's Obj-C UI normally starts the launcher JVM with JLI_Launch when the
 * player presses Play. In the AOT build JavaLauncher.m calls this class instead
 * (through Natives/AOTBridge.m), so the game runs inside the RoboVM VM that the
 * embedded McAotFramework created at app launch. That keeps the GLFW window,
 * input callbacks and GL context in one VM.
 *
 * args[0] = player name, args[1] = version id.
 */
public final class AotMain {
    private AotMain() {
    }

    public static void main(String[] args) throws Throwable {
        String username = args.length > 0 && !args[0].isEmpty() ? args[0] : "Player";
        String versionId = args.length > 1 ? args[1] : System.getenv("MEOW_VERSION");
        if (versionId == null || versionId.isEmpty()) {
            System.err.println("[AotMain] no version id given");
            return;
        }

        System.out.println("[AotMain] version=" + versionId + " user=" + username
                + " gameDir=" + System.getenv("GAME_DIR"));

        String screen = System.getenv("MEOW_SCREEN");
        System.setProperty("glfw.windowSize", screen != null ? screen : "1280x720");
        System.setProperty("org.lwjgl.vulkan.libname", "libMoltenVK.dylib");

        // MinecraftAccount.<clinit> is patched out of launcher.jar for the AOT
        // build (it calls System.loadLibrary), so fill the fields by hand.
        MinecraftAccount account = new MinecraftAccount();
        account.username = username;
        account.accessToken = "0";
        account.clientToken = "0";
        account.profileId = "00000000-0000-0000-0000-000000000000";
        account.xuid = "0";

        JMinecraftVersionList.Version version = Tools.getVersionInfo(versionId);
        System.out.println("[AotMain] resolved version " + version.id + " mainClass=" + version.mainClass);

        if (version.logging != null && version.logging.client != null && version.logging.client.file != null) {
            System.setProperty("log4j.configurationFile", Tools.DIR_BUNDLE + "/" + version.logging.client.file.id);
        }

        String[] mcArgs = Tools.getMinecraftArgs(account, version);
        System.out.println("[AotMain] starting net.minecraft.client.main.Main");
        net.minecraft.client.main.Main.main(mcArgs);
    }
}
