package dev.mcjava.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites calls to Java 8 APIs that RoboVM's runtime does not provide into
 * calls to {@code dev.mcjava.aot.Compat} helpers (which are Java 7 compatible).
 *
 * <p>Rules are applied to every class in the jar. Used on LWJGL for
 * {@code ThreadLocal.withInitial}; more rules can be added as the AOT port hits
 * missing APIs.
 *
 * <pre>java8-shim &lt;in.jar&gt; &lt;out.jar&gt;</pre>
 */
public final class Java8Shim {

    /** owner, name, descriptor -> owner, name, descriptor */
    private static final String[][] RULES = {
            {
                    "java/lang/ThreadLocal", "withInitial",
                    "(Ljava/util/function/Supplier;)Ljava/lang/ThreadLocal;",
                    "dev/mcjava/aot/Compat", "threadLocalWithInitial",
                    "(Ljava/util/function/Supplier;)Ljava/lang/ThreadLocal;"
            },
            {
                    "org/lwjgl/system/APIUtil", "apiGetFunctionAddress",
                    "(Lorg/lwjgl/system/FunctionProvider;Ljava/lang/String;)J",
                    "dev/mcjava/aot/Compat", "addressOf",
                    "(Ljava/lang/Object;Ljava/lang/String;)J"
            },
            {
                    "org/lwjgl/system/APIUtil", "apiGetFunctionAddressOptional",
                    "(Lorg/lwjgl/system/SharedLibrary;Ljava/lang/String;)J",
                    "dev/mcjava/aot/Compat", "addressOptional",
                    "(Ljava/lang/Object;Ljava/lang/String;)J"
            },
            {
                    "java/lang/System", "load",
                    "(Ljava/lang/String;)V",
                    "dev/mcjava/aot/Compat", "loadNoop",
                    "(Ljava/lang/String;)V"
            },
            {
                    "java/lang/System", "loadLibrary",
                    "(Ljava/lang/String;)V",
                    "dev/mcjava/aot/Compat", "loadLibraryNoop",
                    "(Ljava/lang/String;)V"
            },
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java8-shim <in.jar> <out.jar>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        int classes = 0;
        int rewritten = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(in.toFile()));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = readAll(zin);
                if (e.getName().endsWith(".class")) {
                    Object[] res = rewrite(data);
                    if ((Integer) res[1] > 0) {
                        data = (byte[]) res[0];
                        rewritten += (Integer) res[1];
                    }
                    classes++;
                }
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.println("[java8-shim] classes=" + classes + " rewrites=" + rewritten + " -> " + out);
    }

    private static Object[] rewrite(byte[] bytes) {
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(bytes).accept(cn, 0);
        } catch (Exception ex) {
            return new Object[]{bytes, 0};
        }
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode m = (MethodInsnNode) insn;
                for (String[] rule : RULES) {
                    if (m.owner.equals(rule[0]) && m.name.equals(rule[1]) && m.desc.equals(rule[2])) {
                        m.owner = rule[3];
                        m.name = rule[4];
                        m.desc = rule[5];
                        count++;
                        break;
                    }
                }
            }
        }
        if (count == 0) {
            return new Object[]{bytes, 0};
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return new Object[]{cw.toByteArray(), count};
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private Java8Shim() {
    }
}
