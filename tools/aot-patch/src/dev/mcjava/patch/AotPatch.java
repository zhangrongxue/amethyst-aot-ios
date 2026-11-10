package dev.mcjava.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
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
 * Rewrites every method with a given name inside one class of a jar so that it
 * returns immediately (default value for non-void returns).
 *
 * <p>Used by the AOT port to neutralise JVM dynamic-library loading: RoboVM
 * statically links native libraries, so LWJGL's
 * {@code org.lwjgl.system.Library.loadSystem(...)} must become a no-op instead
 * of looking for {@code .dylib}/{@code .so} files on disk.
 *
 * <pre>aot-patch &lt;in.jar&gt; &lt;out.jar&gt; &lt;internal.ClassName&gt; &lt;methodName&gt;</pre>
 */
public final class AotPatch {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: aot-patch <in.jar> <out.jar> <internal.ClassName> <methodName>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String cls = args[2].replace('.', '/');
        String method = args[3];

        int patchedClasses = 0;
        int patchedMethods = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(in.toFile()));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = readAll(zin);
                if (e.getName().equals(cls + ".class")) {
                    Object[] res = patch(data, method);
                    data = (byte[]) res[0];
                    patchedClasses++;
                    patchedMethods += (Integer) res[1];
                }
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.println("[aot-patch] classes=" + patchedClasses + " methods=" + patchedMethods + " -> " + out);
        if (patchedClasses == 0) {
            System.err.println("[aot-patch] target class not found: " + cls);
            System.exit(1);
        }
    }

    private static Object[] patch(byte[] bytes, String method) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(method)) {
                continue;
            }
            mn.instructions.clear();
            mn.tryCatchBlocks.clear();
            mn.localVariables = null;
            mn.visibleLocalVariableAnnotations = null;
            mn.invisibleLocalVariableAnnotations = null;
            Type ret = Type.getReturnType(mn.desc);
            AbstractInsnNode r;
            switch (ret.getSort()) {
                case Type.VOID:
                    r = new InsnNode(Opcodes.RETURN);
                    break;
                case Type.BOOLEAN:
                case Type.BYTE:
                case Type.CHAR:
                case Type.SHORT:
                case Type.INT:
                    mn.instructions.add(new InsnNode(Opcodes.ICONST_0));
                    r = new InsnNode(Opcodes.IRETURN);
                    break;
                case Type.LONG:
                    mn.instructions.add(new InsnNode(Opcodes.LCONST_0));
                    r = new InsnNode(Opcodes.LRETURN);
                    break;
                case Type.FLOAT:
                    mn.instructions.add(new InsnNode(Opcodes.FCONST_0));
                    r = new InsnNode(Opcodes.FRETURN);
                    break;
                case Type.DOUBLE:
                    mn.instructions.add(new InsnNode(Opcodes.DCONST_0));
                    r = new InsnNode(Opcodes.DRETURN);
                    break;
                default:
                    mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                    r = new InsnNode(Opcodes.ARETURN);
            }
            mn.instructions.add(r);
            count++;
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

    private AotPatch() {
    }
}
