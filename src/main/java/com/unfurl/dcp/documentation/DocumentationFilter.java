package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Specification (DCP Documentation §5): narrows a projection by tenant, environment, contract, binding and surface.
 * Filtering removes entries from a view; it never changes an entry's status or reason. A null criterion matches
 * everything.
 *
 * @param tenant      the target environment's tenant
 * @param environment the target environment's name
 * @param contract    one contract id
 * @param binding     one runtime binding id
 * @param surface     one entry surface
 */
public record DocumentationFilter(String tenant, String environment, URI contract, URI binding, EntrySurface surface) {
    /** The filter that keeps everything. */
    public static final DocumentationFilter NONE = new DocumentationFilter(null, null, null, null, null);

    /** Applies the filter. A projection outside the tenant or environment keeps no entries and no components. */
    public CapabilityDocumentation apply(CapabilityDocumentation documentation) {
        Objects.requireNonNull(documentation, "documentation");
        boolean inScope = (tenant == null || tenant.equals(documentation.targetEnvironment().tenant()))
                && (environment == null || environment.equals(documentation.targetEnvironment().name()));
        List<ReportEntry> entries = inScope ? documentation.report().entries().stream()
                .filter(e -> contract == null || contract.equals(e.contractId()))
                .filter(e -> binding == null || binding.equals(e.bindingId()))
                .filter(e -> surface == null || surface == e.surface())
                .toList() : List.of();
        List<DocumentedComponent> components = inScope ? documentation.components().stream()
                .map(component -> new DocumentedComponent(component.componentUri(), component.componentVersion(), component.name(),
                        component.claimHash(), component.serviceRoot(), component.protectedComponent(),
                        component.capabilities().stream()
                                .filter(c -> contract == null || contract.equals(c.contractId()))
                                .filter(c -> binding == null || binding.equals(c.bindingId()))
                                .filter(c -> surface == null || surface == c.surface())
                                .toList(),
                        contract == null && binding == null && (surface == null || surface == EntrySurface.HTTP)
                                ? component.apiDocuments() : List.of()))
                .filter(component -> !component.capabilities().isEmpty() || !component.apiDocuments().isEmpty())
                .toList() : List.of();
        List<ApiOperationExclusion> operations = inScope && contract == null && binding == null
                ? documentation.report().apiOperations() : List.of();
        return new CapabilityDocumentation(documentation.documentId(), documentation.targetEnvironment(), documentation.source(),
                documentation.identity(), documentation.surfaces(), components, new DocumentationReport(entries, operations),
                documentation.lifecycle());
    }
}
