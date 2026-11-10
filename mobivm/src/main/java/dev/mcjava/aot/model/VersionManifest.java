package dev.mcjava.aot.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors the shape of Mojang's version manifest entry
 * ({@code https://piston-meta.mojang.com/v1/packages/.../<version>.json}).
 *
 * <p>Fields are package-visible and set by Gson through reflection, which is the
 * interesting part for AOT: without field metadata the deserialization fails at
 * runtime even though the code compiles.
 */
public final class VersionManifest {
    String id;
    String type;
    String mainClass;
    List<Library> libraries = new ArrayList<>();

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getMainClass() {
        return mainClass;
    }

    public List<Library> getLibraries() {
        return libraries;
    }
}
