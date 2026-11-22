package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Value Object: one host (one service root) with at least one documented capability (DCP HLD-C2 G.2). It is rendered as
 * one OpenAPI document. A component with no active capability is absent here and appears only in the report.
 *
 * @param componentUri       the host's claim URI
 * @param componentVersion   its pinned version
 * @param name               its claim identity name
 * @param claimHash          its pinned canonical claim hash
 * @param serviceRoot        its service root, the OpenAPI {@code servers} entry
 * @param protectedComponent whether the identity contract protects it; decides rendered security
 * @param capabilities       its own and its hosted documented capabilities, sorted by surface then capability
 * @param apiDocuments       its projected product API documents, sorted by id
 */
public record DocumentedComponent(URI componentUri, String componentVersion, String name, String claimHash,
                                  URI serviceRoot, boolean protectedComponent,
                                  List<DocumentedCapability> capabilities, List<DocumentedApi> apiDocuments) {
    /** Validating constructor: required fields and sorted copies. */
    public DocumentedComponent {
        Objects.requireNonNull(componentUri, "componentUri");
        Objects.requireNonNull(serviceRoot, "serviceRoot");
        capabilities = capabilities == null ? List.of() : capabilities.stream()
                .sorted(Comparator.comparing(DocumentedCapability::surface)
                        .thenComparing(DocumentedCapability::capability)
                        .thenComparing(capability -> capability.providerComponentUri().toString()))
                .toList();
        apiDocuments = apiDocuments == null ? List.of() : apiDocuments.stream()
                .sorted(Comparator.comparing(DocumentedApi::apiId)).toList();
    }
}
