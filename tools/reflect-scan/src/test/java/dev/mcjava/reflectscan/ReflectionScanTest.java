package dev.mcjava.reflectscan;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Scaffolding-free tests: classes are generated with ASM at test time, packed
 * into a jar, and the scanner is run against it. This keeps the fixtures honest
 * (they are real bytecode, not string samples).
 */
public class ReflectionScanTest {

    private static final String TARGET = "example/Target";
    private static final String CALLER = "example/Caller";
    private static final String SERVICE = "example/Service";
    private static final String IMPL = "example/Impl";

    @Test
    public void findsClassForNameAndMemberLookup() throws Exception {
        Path jar = tempJar("lookup.jar",
                classes(
                        new ClassSpec(TARGET, null, new String[]{"run"}),
                        new ClassSpec(CALLER, null, new String[0])),
                null);
        replaceEntry(jar, CALLER + ".class", callerBytecode(CALLER, TARGET, "run"));

        ReflectionScan.Result result = new ReflectionScan(Arrays.asList(jar), 2).scan();

        assertTrue("class-load hit for example.Target",
                result.hits.stream().anyMatch(h -> "class-load".equals(h.kind) && "example.Target".equals(h.owner)));
        assertTrue("method lookup owned by example.Target",
                result.hits.stream().anyMatch(h -> "method".equals(h.kind)
                        && "example.Target".equals(h.owner) && "run".equals(h.member)));
        assertTrue("forceLink keeps example.Target", result.patterns.contains("example.Target"));
    }

    @Test
    public void readsServiceLoaderRegistrations() throws Exception {
        Path jar = tempJar("service.jar",
                classes(new ClassSpec(SERVICE, null, new String[0]),
                        new ClassSpec(IMPL, SERVICE, new String[0])),
                "example.Impl\n");

        ReflectionScan.Result result = new ReflectionScan(Arrays.asList(jar), 2).scan();

        assertTrue("service implementation discovered", result.services.contains("example.Impl"));
        assertTrue("service implementation force-linked", result.patterns.contains("example.Impl"));
    }

    @Test
    public void classifiesJdkLookupsSeparately() throws Exception {
        Path jar = tempJar("jdk.jar",
                classes(new ClassSpec("example/Probe", null, new String[0])),
                null);
        replaceEntry(jar, "example/Probe.class", callerBytecode("example/Probe", "java/awt/Desktop", "getDesktop"));

        ReflectionScan.Result result = new ReflectionScan(Arrays.asList(jar), 2).scan();

        assertTrue("jdk runtime name tracked", result.jdkRuntime.contains("java.awt.Desktop"));
        assertFalse("jdk runtime name is not force-linked as app code",
                result.patterns.contains("java.awt.Desktop"));
    }

    // ------------------------------------------------------------------ helpers

    /** A class with an optional interface and a set of static methods. */
    private static final class ClassSpec {
        final String name;
        final String iface;
        final String[] methods;

        ClassSpec(String name, String iface, String[] methods) {
            this.name = name;
            this.iface = iface;
            this.methods = methods;
        }
    }

    private static Path tempJar(String name, Map<String, byte[]> entries, String serviceBody) throws IOException {
        Path dir = Files.createTempDirectory("reflect-scan-test");
        Path jar = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            if (serviceBody != null) {
                zip.putNextEntry(new ZipEntry("META-INF/services/example.Service"));
                zip.write(serviceBody.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static Map<String, byte[]> classes(ClassSpec... specs) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (ClassSpec spec : specs) {
            out.put(spec.name + ".class", generate(spec));
        }
        return out;
    }

    private static byte[] generate(ClassSpec spec) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int access = Opcodes.ACC_PUBLIC;
        String[] interfaces = spec.iface != null ? new String[]{spec.iface} : null;
        cw.visit(Opcodes.V1_8, access, spec.name, null, "java/lang/Object", interfaces);
        for (String m : spec.methods) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, m,
                    "()Ljava/lang/String;", null, null);
            mv.visitCode();
            mv.visitLdcInsn("ok");
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a class whose {@code main} does:
     * {@code Class.forName(lookedUp).getDeclaredMethod("run")}.
     */
    private static byte[] callerBytecode(String internalName, String lookedUp, String methodName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main",
                "([Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(lookedUp.replace('/', '.'));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn(Type.getObjectType(lookedUp));
        mv.visitLdcInsn(methodName);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void replaceEntry(Path jar, String entryName, byte[] data) throws IOException {
        Map<String, byte[]> all = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                all.put(e.getName(), readAll(zf.getInputStream(e)));
            }
        }
        all.put(entryName, data);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            for (Map.Entry<String, byte[]> e : all.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
