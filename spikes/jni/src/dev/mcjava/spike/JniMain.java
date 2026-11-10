package dev.mcjava.spike;

/**
 * Minimal JNI smoke test for the AOT plan.
 *
 * <p>If RoboVM can resolve a native method that lives in a statically linked
 * library, then the 4000+ JNI functions of the iOS LWJGL/MetalANGLE/OpenAL
 * libraries can be supplied to an AOT image. If it cannot, the whole
 * "AOT the game" approach needs a different native bridge.
 */
public final class JniMain {

    static {
        try {
            System.loadLibrary("spikejni");
        } catch (Throwable t) {
            System.out.println("loadLibrary failed (expected on RoboVM): " + t);
        }
    }

    public static native int add(int a, int b);

    private JniMain() {
    }

    public static void main(String[] args) {
        try {
            int r = add(20, 22);
            System.out.println("JNI-RESULT " + r);
        } catch (Throwable t) {
            System.out.println("JNI-FAILED " + t);
            t.printStackTrace();
        }
    }
}
