package dev.mcjava.aot.model;

/** One entry of {@code libraries[]} in a Minecraft version json. */
public final class Library {
    String name;
    String url;

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }
}
