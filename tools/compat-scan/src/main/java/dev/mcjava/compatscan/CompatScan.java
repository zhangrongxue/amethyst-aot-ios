package dev.mcjava.compatscan;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Static compatibility scanner for "can MobiVM/RoboVM AOT this jar?" questions.
 *
 * <p>It reports the facts that decide whether a JVM application can be compiled
 * ahead of time by RoboVM:
 * <ul>
 *   <li>class file versions (RoboVM understands Java 8 / v52)</li>
 *   <li>declared {@code native} methods (each one needs a C implementation at link time)</li>
 *   <li>{@code invokedynamic} usage (lambda support exists but is a warning sign)</li>
 *   <li>references to JDK packages RoboVM does not ship (java.awt, javax.swing,
 *       java.applet, java.lang.instrument, jdk.*, sun.misc.Unsafe, ...)</li>
 *   <li>external package prefixes the jar expects on the classpath</li>
 * </ul>
 */
public final class CompatScan {

    private static final String[] BLOCKER_PREFIXES = {
            "java/awt", "javax/swing", "javax/sound", "java/applet", "java/beans",
            "java/lang/instrument", "java/lang/management", "jdk/", "sun/misc/Unsafe",
            "sun/misc/", "com/sun/", "javax/script", "javax/xml/bind", "java/sql"
    };

    private static final Set<String> JDK_PREFIXES = new HashSet<>(Arrays.asList(
            "java/", "javax/", "jdk/", "sun/", "com/sun/"
    ));

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: compat-scan <jar> <outdir> [--label name]");
            System.exit(2);
        }
        Path jar = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String label = jar.getFileName().toString();
        for (int i = 2; i < args.length; i++) {
            if ("--label".equals(args[i]) && i + 1 < args.length) {
                label = args[++i];
            }
        }

        ScanResult r = scan(jar);
        Files.createDirectories(out);
        writeJson(out.resolve("compat-" + safe(label) + ".json"), label, r);
        writeMarkdown(out.resolve("compat-" + safe(label) + ".md"), label, r);
        System.out.println("[compat-scan] " + label + ": classes=" + r.total
                + " nativeMethods=" + r.nativeMethods.size()
                + " indyClasses=" + r.indyClasses.size()
                + " blockerClasses=" + r.blockers.size()
                + " versions=" + r.versions);
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------ scan

    public static final class ScanResult {
        int total;
        final Map<Integer, Integer> versions = new TreeMap<>();
        final List<String> nativeMethods = new ArrayList<>();
        final Set<String> indyClasses = new TreeSet<>();
        /** blocked package prefix -> classes referencing it */
        final Map<String, Set<String>> blockers = new TreeMap<>();
        final Map<String, Integer> externalPackages = new TreeMap<>();
        final Map<String, Integer> internalPackages = new TreeMap<>();
    }

    private static ScanResult scan(Path jar) throws IOException {
        ScanResult r = new ScanResult();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                String cls = n.substring(0, n.length() - 6).replace('/', '.');
                if (cls.endsWith("module-info") || cls.endsWith("package-info")) continue;
                r.total++;
                try (InputStream in = zip.getInputStream(e)) {
                    new ClassReader(in).accept(new Visitor(cls, r), ClassReader.SKIP_DEBUG);
                } catch (Exception ex) {
                    // ignore unparsable entries
                }
            }
        }
        return r;
    }

    private static final class Visitor extends ClassVisitor {
        private final String cls;
        private final ScanResult r;

        Visitor(String cls, ScanResult r) {
            super(Opcodes.ASM9);
            this.cls = cls;
            this.r = r;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            int major = version & 0xFFFF;
            r.versions.merge(major, 1, Integer::sum);
            if (superName != null) ref(superName);
            if (interfaces != null) {
                for (String i : interfaces) ref(i);
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_NATIVE) != 0) {
                r.nativeMethods.add(cls + "#" + name + descriptor);
            }
            if (exceptions != null) {
                for (String ex : exceptions) ref(ex);
            }
            refDescriptor(descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int op, String owner, String name2, String desc, boolean itf) {
                    ref(owner);
                    refDescriptor(desc);
                    if (op == Opcodes.INVOKEDYNAMIC) {
                        r.indyClasses.add(cls);
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(String name2, String desc, org.objectweb.asm.Handle bsm, Object... args) {
                    r.indyClasses.add(cls);
                    if (bsm != null) {
                        ref(bsm.getOwner());
                        refDescriptor(bsm.getDesc());
                    }
                    refDescriptor(desc);
                }

                @Override
                public void visitFieldInsn(int op, String owner, String name2, String desc) {
                    ref(owner);
                    refDescriptor(desc);
                }

                @Override
                public void visitTypeInsn(int op, String type) {
                    ref(type);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type) refDescriptor(((Type) value).getDescriptor());
                }
            };
        }

        private void refDescriptor(String desc) {
            if (desc == null) return;
            try {
                for (Type t : Type.getArgumentTypes(desc)) refType(t);
                refType(Type.getReturnType(desc));
            } catch (RuntimeException ignored) {
                // not a method descriptor
            }
        }

        private void refType(Type t) {
            if (t == null) return;
            if (t.getSort() == Type.OBJECT) {
                ref(t.getInternalName());
            } else if (t.getSort() == Type.ARRAY && t.getElementType().getSort() == Type.OBJECT) {
                ref(t.getElementType().getInternalName());
            }
        }

        private void ref(String internalName) {
            if (internalName == null) return;
            if (internalName.startsWith("[")) return;
            int slash = internalName.lastIndexOf('/');
            if (slash < 0) return;
            String pkg = internalName.substring(0, slash + 1);
            if (JDK_PREFIXES.stream().anyMatch(pkg::startsWith)) {
                for (String b : BLOCKER_PREFIXES) {
                    if (pkg.startsWith(b)) {
                        r.blockers.computeIfAbsent(b, k -> new TreeSet<>()).add(cls);
                    }
                }
            } else if (!pkg.equals(internalName.substring(0, slash + 1)) || true) {
                // reference to a non-JDK type: either in this jar (internal) or a dependency
                r.externalPackages.merge(pkg, 1, Integer::sum);
            }
        }
    }

    // ------------------------------------------------------------------ output

    private static void writeJson(Path file, String label, ScanResult r) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"jar\": \"").append(esc(label)).append("\",\n");
        sb.append("  \"classes\": ").append(r.total).append(",\n");
        sb.append("  \"classVersions\": {");
        int i = 0;
        for (Map.Entry<Integer, Integer> e : r.versions.entrySet()) {
            sb.append(i++ == 0 ? "" : ", ").append(e.getKey()).append(": ").append(e.getValue());
        }
        sb.append("},\n");
        sb.append("  \"nativeMethodCount\": ").append(r.nativeMethods.size()).append(",\n");
        sb.append("  \"indyClassCount\": ").append(r.indyClasses.size()).append(",\n");
        sb.append("  \"nativeMethods\": [");
        i = 0;
        for (String m : r.nativeMethods) {
            sb.append(i++ == 0 ? "" : ", ").append('"').append(esc(m)).append('"');
        }
        sb.append("],\n");
        sb.append("  \"blockers\": {");
        i = 0;
        for (Map.Entry<String, Set<String>> e : r.blockers.entrySet()) {
            sb.append(i++ == 0 ? "" : ", ").append('"').append(esc(e.getKey())).append("\": [");
            int j = 0;
            for (String c : e.getValue()) {
                sb.append(j++ == 0 ? "" : ", ").append('"').append(esc(c)).append('"');
            }
            sb.append(']');
        }
        sb.append("},\n");
        sb.append("  \"referencePackages\": {");
        i = 0;
        List<Map.Entry<String, Integer>> top = new ArrayList<>(r.externalPackages.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : top) {
            sb.append(i++ == 0 ? "" : ", ").append('"').append(esc(e.getKey())).append("\": ").append(e.getValue());
        }
        sb.append("}\n}\n");
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeMarkdown(Path file, String label, ScanResult r) throws IOException {
        try (PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(new FileOutputStream(file.toFile()), "UTF-8"))) {
            w.println("# AOT compatibility scan: " + label);
            w.println();
            w.println("| metric | value |");
            w.println("|---|---:|");
            w.println("| classes | " + r.total + " |");
            w.println("| class file versions | " + r.versions + " |");
            w.println("| native methods | " + r.nativeMethods.size() + " |");
            w.println("| classes with invokedynamic | " + r.indyClasses.size() + " |");
            w.println("| classes touching blocked JDK packages | " + r.blockers.values().stream().mapToInt(Set::size).sum() + " |");
            w.println();
            w.println("## Blocked JDK packages");
            w.println();
            if (r.blockers.isEmpty()) {
                w.println("(none)");
            } else {
                w.println("| package | classes | sample |");
                w.println("|---|---:|---|");
                for (Map.Entry<String, Set<String>> e : r.blockers.entrySet()) {
                    String sample = e.getValue().stream().limit(5).reduce((a, b) -> a + ", " + b).orElse("");
                    w.println("| " + e.getKey() + " | " + e.getValue().size() + " | " + sample + " |");
                }
            }
            w.println();
            w.println("## Native methods (need a C implementation at link time)");
            w.println();
            if (r.nativeMethods.isEmpty()) {
                w.println("(none)");
            } else {
                w.println("```");
                r.nativeMethods.stream().limit(200).forEach(w::println);
                if (r.nativeMethods.size() > 200) {
                    w.println("... " + (r.nativeMethods.size() - 200) + " more");
                }
                w.println("```");
            }
            w.println();
            w.println("## Top referenced non-JDK packages");
            w.println();
            w.println("| package | refs |");
            w.println("|---|---:|");
            r.externalPackages.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(40)
                    .forEach(e -> w.println("| " + e.getKey() + " | " + e.getValue() + " |"));
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private CompatScan() {
    }
}

