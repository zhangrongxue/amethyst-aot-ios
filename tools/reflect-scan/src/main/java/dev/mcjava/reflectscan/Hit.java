package dev.mcjava.reflectscan;

import java.nio.file.Path;

/**
 * One reflection fact found while scanning bytecode.
 *
 * <p>Kinds:
 * <ul>
 *   <li>{@code class-load} - a class name was looked up dynamically (Class.forName /
 *       ClassLoader.loadClass / Class.forName with a String constant).</li>
 *   <li>{@code method} - a method was looked up via reflection.</li>
 *   <li>{@code field} - a field was looked up via reflection.</li>
 *   <li>{@code ctor} - a constructor was looked up via reflection.</li>
 *   <li>{@code method-handle} - MethodHandles.Lookup#find* call.</li>
 *   <li>{@code proxy} - java.lang.reflect.Proxy.newProxyInstance over an interface.</li>
 *   <li>{@code service} - a class declared in META-INF/services (ServiceLoader entry point).</li>
 * </ul>
 */
public final class Hit implements Comparable<Hit> {
    public final String kind;
    /** Fully qualified owner class (dot separated), or "" when unknown. */
    public final String owner;
    /** Member name (method/field/interface) or the looked-up class name. */
    public final String member;
    /** Class that contains the reflective callsite (dot separated). */
    public final String caller;
    public final String jar;
    public final String evidence;

    public Hit(String kind, String owner, String member, String caller, String jar, String evidence) {
        this.kind = kind;
        this.owner = owner == null ? "" : owner;
        this.member = member == null ? "" : member;
        this.caller = caller == null ? "" : caller;
        this.jar = jar == null ? "" : jar;
        this.evidence = evidence == null ? "" : evidence;
    }

    @Override
    public int compareTo(Hit o) {
        int c = kind.compareTo(o.kind);
        if (c != 0) return c;
        c = owner.compareTo(o.owner);
        if (c != 0) return c;
        c = member.compareTo(o.member);
        if (c != 0) return c;
        return caller.compareTo(o.caller);
    }

    @Override
    public String toString() {
        return kind + " " + (owner.isEmpty() ? "?" : owner) + "#" + member + " <- " + caller + " (" + jar + ")";
    }

    /** Stable identity used for de-duplication. */
    public String key() {
        return kind + "\u0000" + owner + "\u0000" + member + "\u0000" + caller;
    }

    public static String jarName(Path p) {
        String s = p.getFileName().toString();
        return s;
    }
}
