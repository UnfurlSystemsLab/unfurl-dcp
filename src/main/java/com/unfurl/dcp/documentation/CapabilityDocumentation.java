package com.unfurl.dcp.documentation;

import com.unfurl.dcp.runtimebinding.TargetEnvironment;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Value Object (the projection model, DCP HLD-C2 G.2): one deterministic description of what a composition (or, in a
 * host view, one host) documents right now, plus the report on everything it does not. Every rendered output, the
 * OpenAPI documents, the summary and the report, is a view of this one record.
 *
 * @param documentId        the projection's id
 * @param targetEnvironment where the documented bindings are deployed
 * @param source            what the projection was derived from
 * @param identity          whether the composition has an identity contract
 * @param surfaces          the output surfaces and what each includes, by visibility
 * @param components        hosts with at least one documented capability, sorted by URI
 * @param report            every entry and every excluded API operation
 * @param lifecycle         who produced it and when
 */
public record CapabilityDocumentation(URI documentId, TargetEnvironment targetEnvironment, DocumentationSource source,
                                      IdentityMode identity, List<DocumentationSurface> surfaces,
                                      List<DocumentedComponent> components, DocumentationReport report,
                                      DocumentationLifecycle lifecycle) {
    /** Validating constructor: required fields and sorted copies. */
    public CapabilityDocumentation {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(targetEnvironment, "targetEnvironment");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(lifecycle, "lifecycle");
        surfaces = surfaces == null ? List.of() : surfaces.stream()
                .sorted(Comparator.comparing(DocumentationSurface::kind).thenComparing(DocumentationSurface::visibility))
                .toList();
        components = components == null ? List.of() : components.stream()
                .sorted(Comparator.comparing(component -> component.componentUri().toString())).toList();
    }
}
