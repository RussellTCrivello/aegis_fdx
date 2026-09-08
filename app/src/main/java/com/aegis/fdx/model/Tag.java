package com.aegis.fdx.model;

import java.util.List;

/** F-19 / F-20: user-defined coloured tags plus the five fixed system tags. */
public record Tag(String name, String color, boolean system, String shortcut) {

    public static final List<Tag> FIXED = List.of(
            new Tag("Responsive",   "#22c55e", true, "Ctrl+1"),
            new Tag("Featured",     "#eab308", true, "Ctrl+2"),
            new Tag("Trending",     "#f97316", true, "Ctrl+3"),
            new Tag("Needs Review", "#3b82f6", true, "Ctrl+4"),
            new Tag("Hidden",       "#6b7280", true, "Ctrl+5"));

    public static Tag of(String name, String color) {
        return new Tag(name, color, false, null);
    }
}
