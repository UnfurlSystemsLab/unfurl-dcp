package com.unfurl.dcp.documentation;

import com.unfurl.dcp.runtimebinding.ConfigRef;

import java.util.List;
import java.util.Objects;

/**
 * Value Object: one output surface the projection records (DCP HLD-C2 G.2 {@code surfaces}), with what it includes at
 * one visibility.
 *
 * @param kind       the output kind
 * @param visibility the visibility of everything this surface includes
 * @param baseUrlRef optional reference to where the surface is published
 * @param includes   capability names and {@code api_id}s it includes, sorted
 */
public record DocumentationSurface(SurfaceKind kind, Visibility visibility, ConfigRef baseUrlRef, List<String> includes) {
    /** Validating constructor: required fields and a sorted copy. */
    public DocumentationSurface {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(visibility, "visibility");
        includes = includes == null ? List.of() : includes.stream().sorted().distinct().toList();
    }
}
