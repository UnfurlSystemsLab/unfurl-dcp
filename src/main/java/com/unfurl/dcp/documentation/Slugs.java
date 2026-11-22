package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.Locale;

/**
 * Utility: the stable, URL-safe and OpenAPI-component-safe names documentation derives from identifiers, so every
 * consumer (renderers, the API catalog's routes) derives the same name the same way.
 */
public final class Slugs {
    private Slugs() { }

    /**
     * A component slug: the URI lower-cased, every character outside {@code [a-z0-9]} mapped to {@code -}, repeated dashes
     * collapsed and leading or trailing dashes removed. Two URIs can share a slug; callers that publish slugs reject
     * collisions.
     */
    public static String of(URI component) {
        String slug = component.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        return slug.isEmpty() ? "component" : slug;
    }

    /** An OpenAPI component name: characters outside {@code [A-Za-z0-9._-]} mapped to {@code _}. */
    public static String componentName(String text) {
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
