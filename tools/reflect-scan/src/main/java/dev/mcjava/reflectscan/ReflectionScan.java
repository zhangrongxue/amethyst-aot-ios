package dev.mcjava.reflectscan;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Static reflection scanner.
 *
 * <p>It walks every class in a set of jars (Minecraft client, libraries, Gson, ...) and records
 * the places where code looks things up by name at runtime: {@code Class.forName}, reflective
 * method/field/constructor lookups, method handles, dynamic proxies and ServiceLoader entries.
 *
 * <p>The result is turned into RoboVM/MobiVM {@code forceLinkClasses} patterns, which is what the
 * AOT compiler needs in order to keep reflectively-reachable classes in the native image.
 */
public final class ReflectionScan {

    private static final Set<String> CLASS_OWNERS = new HashSet<>(Arrays.asList(
            "java/lang/Class", "java/lang/ClassLoader"));

    private static final Set<String> METHOD_LOOKUPS = new HashSet<>(Arrays.asList(
            "getDeclaredMethod", "getMethod", "getDeclaredMethods", "getMethods"));

    private static final Set<String> FIELD_LOOKUPS = new HashSet<>(Arrays.asList(
            "getDeclaredField", "getField", "getDeclaredFields", "getFields"));

    private static final Set<String> CTOR_LOOKUPS = new HashSet<>(Arrays.asList(
            "getDeclaredConstructor", "getConstructor", "getDeclaredConstructors", "getConstructors"));

    private final List<Path> jars;
    private final int minPackageHits;

    private final Set<String> knownClasses = new TreeSet<>();
    private final Map<String, String> classToJar = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Set<String> services = new TreeSet<>();
    private final Set<String> unresolved = new TreeSet<>();
    private final Map<String, Integer> perJarClasses = new TreeMap<>();

    public ReflectionScan(List<Path> jars, int minPackageHits) {
        this.jars = jars;
        this.minPackageHits = minPackageHits;
    }

    public Result scan() throws IOException {
        indexAll();
        scanAll();
        return buildResult();
    }

    // ---------------------------------------------------------------- indexing

    private void indexAll() throws IOException {
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                int count = 0;
                for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class")) continue;
                    if (n.startsWith("META-INF/")) continue;
                    String cn = n.substring(0, n.length() - ".class".length()).replace('/', '.');
                    if (cn.endsWith("module-info") || cn.endsWith("package-info")) continue;
                    knownClasses.add(cn);
                    classToJar.put(cn, jar.getFileName().toString());
                    count++;
                }
                perJarClasses.put(jar.getFileName().toString(), count);
                ZipEntry svc = zip.getEntry("META-INF/services/");
                // individual service files are read in scanJar()
            }
        }
    }

    // ------------------------------------------------------------------ scanning

    private void scanAll() throws IOException {
        for (Path jar : jars) {
            scanJar(jar);
        }
    }

    private void scanJar(Path jar) throws IOException {
        String jarName = jar.getFileName().toString();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("META-INF/services/") && !e.isDirectory()) {
                    readServiceEntry(zip, e, jarName);
                }
            }
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                ClassNode cn = new ClassNode();
                try (InputStream in = zip.getInputStream(e)) {
                    new ClassReader(in).accept(cn, 0);
                } catch (Exception ex) {
                    // Damaged/unsupported class: skip, it will show up in the report counts.
                    continue;
                }
                scanClass(cn, jarName);
            }
        }
    }

    private void readServiceEntry(ZipFile zip, ZipEntry e, String jarName) throws IOException {
        String type = e.getName().substring("META-INF/services/".length());
        if (type.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(zip.getInputStream(e), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash).trim();
                if (line.isEmpty()) continue;
                lines.add(line);
            }
        }
        for (String impl : lines) {
            services.add(impl);
            addHit(new Hit("service", type, impl, e.getName(), jarName, "META-INF/services entry"));
        }
    }

    private void scanClass(ClassNode cn, String jarName) {
        String caller = cn.name.replace('/', '.');
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) continue;
            scanMethod(cn, caller, mn, jarName);
        }
    }

    private void scanMethod(ClassNode cn, String caller, MethodNode mn, String jarName) {
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
            insns.add(in);
        }

        String pendingClass = null;
        String lastClassLiteral = null;
        boolean sawReflection = false;

        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode in = insns.get(i);
            if (in instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) in).cst;
                if (cst instanceof Type) {
                    Type t = (Type) cst;
                    if (t.getSort() == Type.OBJECT) {
                        lastClassLiteral = t.getClassName();
                    }
                }
            }
            if (!(in instanceof MethodInsnNode)) continue;
            MethodInsnNode m = (MethodInsnNode) in;

            boolean isClassOwner = CLASS_OWNERS.contains(m.owner);
            boolean isLookup = "java/lang/invoke/MethodHandles$Lookup".equals(m.owner);
            boolean isProxy = "java/lang/reflect/Proxy".equals(m.owner) && "newProxyInstance".equals(m.name);
            boolean isServiceLoad = "java/util/ServiceLoader".equals(m.owner) && "load".equals(m.name);

            if (!isClassOwner && !isLookup && !isProxy && !isServiceLoad) continue;

            String arg = nearestStringConst(insns, i);
            String classLiteral = lastClassLiteral;

            if (isClassOwner && ("forName".equals(m.name) || "loadClass".equals(m.name))) {
                sawReflection = true;
                if (arg != null) {
                    String normalized = normalizeClassName(arg);
                    pendingClass = normalized;
                    if (knownClasses.contains(normalized)) {
                        addHit(new Hit("class-load", normalized, "", caller, jarName, "Class." + m.name + "(\"" + arg + "\")"));
                    } else if (looksLikeClassName(normalized)) {
                        unresolved.add(normalized);
                    }
                }
                continue;
            }

            if (isClassOwner && METHOD_LOOKUPS.contains(m.name)) {
                sawReflection = true;
                String owner = classLiteral != null ? classLiteral : pendingClass;
                if (arg != null) {
                    addHit(new Hit("method", owner, arg, caller, jarName, "Class." + m.name + "(\"" + arg + "\")"));
                }
                continue;
            }

            if (isClassOwner && FIELD_LOOKUPS.contains(m.name)) {
                sawReflection = true;
                String owner = classLiteral != null ? classLiteral : pendingClass;
                if (arg != null) {
                    addHit(new Hit("field", owner, arg, caller, jarName, "Class." + m.name + "(\"" + arg + "\")"));
                }
                continue;
            }

            if (isClassOwner && CTOR_LOOKUPS.contains(m.name)) {
                sawReflection = true;
                String owner = classLiteral != null ? classLiteral : pendingClass;
                addHit(new Hit("ctor", owner, "<init>", caller, jarName, "Class." + m.name + "()"));
                continue;
            }

            if (isLookup) {
                sawReflection = true;
                String owner = classLiteral != null ? classLiteral : pendingClass;
                addHit(new Hit("method-handle", owner, arg == null ? "" : arg, caller, jarName,
                        "MethodHandles.Lookup." + m.name));
                continue;
            }

            if (isProxy) {
                sawReflection = true;
                if (classLiteral != null) {
                    addHit(new Hit("proxy", classLiteral, "", caller, jarName, "Proxy.newProxyInstance"));
                }
                continue;
            }

            if (isServiceLoad) {
                sawReflection = true;
                if (classLiteral != null) {
                    addHit(new Hit("service", classLiteral, "", caller, jarName, "ServiceLoader.load"));
                }
            }
        }

        if (sawReflection) {
            // Any fully-qualified class name constant inside a reflection-heavy method is
            // very likely a dynamic lookup target that travelled through a local variable.
            for (AbstractInsnNode in : insns) {
                if (!(in instanceof LdcInsnNode)) continue;
                Object cst = ((LdcInsnNode) in).cst;
                if (!(cst instanceof String)) continue;
                String s = (String) cst;
                if (!looksLikeClassName(s)) continue;
                String n = normalizeClassName(s);
                if (knownClasses.contains(n)) {
                    addHit(new Hit("class-name", n, "", caller, jarName, "string constant \"" + s + "\" in reflection method"));
                }
            }
        }
    }

    // --------------------------------------------------------------- extraction

    private static String nearestStringConst(List<AbstractInsnNode> insns, int at) {
        for (int i = at - 1; i >= 0 && i >= at - 8; i--) {
            AbstractInsnNode in = insns.get(i);
            if (in instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) in).cst;
                if (cst instanceof String) return (String) cst;
            }
        }
        return null;
    }

    private static String nearestClassLiteral(List<AbstractInsnNode> insns, int at) {
        for (int i = at - 1; i >= 0 && i >= at - 8; i--) {
            AbstractInsnNode in = insns.get(i);
            if (in instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) in).cst;
                if (cst instanceof Type) {
                    Type t = (Type) cst;
                    if (t.getSort() == Type.OBJECT) {
                        return t.getClassName();
                    }
                }
            }
        }
        return null;
    }

    static String normalizeClassName(String s) {
        String out = s.trim();
        if (out.startsWith("L") && out.endsWith(";")) {
            out = out.substring(1, out.length() - 1);
        }
        out = out.replace('/', '.');
        return out;
    }

    static boolean looksLikeClassName(String s) {
        if (s == null || s.length() < 3 || s.length() > 255) return false;
        if (s.indexOf('.') < 1) return false;
        if (s.startsWith(".") || s.endsWith(".")) return false;
        if (s.contains(" ")) return false;
        for (String part : s.split("\\.")) {
            if (part.isEmpty()) return false;
            char c = part.charAt(0);
            if (!(Character.isJavaIdentifierStart(c))) return false;
        }
        return true;
    }

    private void addHit(Hit h) {
        hits.add(h);
    }

    // ---------------------------------------------------------------- result

    private Result buildResult() {
        // Exact classes that must be force-linked.
        Set<String> exact = new TreeSet<>();
        for (Hit h : hits) {
            if ("class-load".equals(h.kind) || "class-name".equals(h.kind)) {
                if (knownClasses.contains(h.owner)) exact.add(h.owner);
            } else if ("service".equals(h.kind)) {
                if (!h.member.isEmpty()) exact.add(h.member);
            } else if ("proxy".equals(h.kind)) {
                if (knownClasses.contains(h.owner)) exact.add(h.owner);
            } else if ("method-handle".equals(h.kind) || "method".equals(h.kind)
                    || "field".equals(h.kind) || "ctor".equals(h.kind)) {
                if (!h.owner.isEmpty() && knownClasses.contains(h.owner)) exact.add(h.owner);
            }
        }
        exact.removeIf(c -> !knownClasses.contains(c));

        // Names that were looked up but live outside the scanned jars: keep them as
        // best-effort force-link entries (they may come from jars added to the AOT
        // classpath later), except JDK runtime classes which are resolved by RoboVM's
        // own runtime library.
        Set<String> external = new TreeSet<>();
        Set<String> jdkRuntime = new TreeSet<>();
        for (String u : unresolved) {
            String n = normalizeClassName(u);
            if (!looksLikeClassName(n)) continue;
            if (isJdkClass(n)) {
                jdkRuntime.add(n);
            } else {
                external.add(n);
            }
        }
        exact.addAll(external);
        exact.addAll(services);

        // Package wildcards: when many classes from the same package are reflected over,
        // keep the whole package instead of a brittle list of names.
        Map<String, Integer> perPackage = new TreeMap<>();
        for (String c : exact) {
            int idx = c.lastIndexOf('.');
            if (idx < 0) continue;
            perPackage.merge(c.substring(0, idx), 1, Integer::sum);
        }
        Set<String> patterns = new TreeSet<>();
        for (String c : exact) patterns.add(c);
        for (Map.Entry<String, Integer> e : perPackage.entrySet()) {
            if (e.getValue() >= minPackageHits) {
                patterns.add(e.getKey() + ".**");
            }
        }

        List<Hit> sortedHits = new ArrayList<>(hits);
        Collections.sort(sortedHits);
        // De-duplicate by key, keeping first occurrence.
        LinkedHashMap<String, Hit> unique = new LinkedHashMap<>();
        for (Hit h : sortedHits) unique.putIfAbsent(h.key(), h);

        return new Result(new ArrayList<>(unique.values()), exact, patterns, services,
                unresolved, jdkRuntime, perJarClasses, knownClasses.size());
    }

    private static boolean isJdkClass(String n) {
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                || n.startsWith("sun.") || n.startsWith("com.sun.");
    }

    /** Immutable scan outcome. */
    public static final class Result {
        public final List<Hit> hits;
        public final Set<String> exactClasses;
        public final Set<String> patterns;
        public final Set<String> services;
        public final Set<String> unresolved;
        public final Set<String> jdkRuntime;
        public final Map<String, Integer> perJarClasses;
        public final int totalClasses;

        Result(List<Hit> hits, Set<String> exactClasses, Set<String> patterns, Set<String> services,
               Set<String> unresolved, Set<String> jdkRuntime, Map<String, Integer> perJarClasses,
               int totalClasses) {
            this.hits = hits;
            this.exactClasses = exactClasses;
            this.patterns = patterns;
            this.services = services;
            this.unresolved = unresolved;
            this.jdkRuntime = jdkRuntime;
            this.perJarClasses = perJarClasses;
            this.totalClasses = totalClasses;
        }
    }
}
