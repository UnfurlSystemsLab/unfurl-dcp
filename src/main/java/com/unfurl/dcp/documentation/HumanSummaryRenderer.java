package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Renderer (Strategy): a Markdown summary of a projection (DCP HLD-C2 G.2). It covers every entry, including hosted and
 * external ones, and the identity mode. It carries no observation times, so its bytes change only when documented
 * content or reasons change.
 */
public final class HumanSummaryRenderer implements DocumentationRenderer<String> {

    /** Renders the summary. */
    @Override
    public String render(CapabilityDocumentation documentation, DocumentationArtifactStore artifacts) {
        StringBuilder out = new StringBuilder();
        out.append("# API documentation: ").append(documentation.documentId()).append("\n\n");
        out.append("- Environment: ").append(documentation.targetEnvironment().name()).append('\n');
        if (documentation.source().rootContract() != null) {
            out.append("- Composition: ").append(documentation.source().rootContract()).append('\n');
        }
        out.append("- Identity: ").append(documentation.identity() == IdentityMode.IN_CONTRACT
                ? "the composition has an identity contract; protected components need OAuth 2.0 bearer tokens"
                : "no identity contract; every operation is served without authentication on a trusted network").append("\n\n");

        Map<URI, DocumentedCapability> documented = new HashMap<>();
        for (DocumentedComponent component : documentation.components()) {
            out.append("## ").append(component.name()).append(" (").append(component.componentUri()).append('@')
                    .append(component.componentVersion()).append(")\n\n");
            out.append("Service root: ").append(component.serviceRoot()).append(component.protectedComponent() ? " (protected)" : "")
                    .append("\n\n");
            for (DocumentedCapability capability : component.capabilities()) {
                documented.put(capability.contractId(), capability);
                out.append("### `").append(capability.capability()).append("` ")
                        .append(capability.surface() == EntrySurface.HTTP ? "POST " + capability.path()
                                : "hosted in process, provided by " + capability.providerComponentUri())
                        .append("\n\n");
                if (capability.description() != null) out.append(capability.description()).append("\n\n");
                capability.operations().forEach(operation -> out.append("- Operation `").append(operation.operation()).append('`')
                        .append(operation.summary() == null ? "" : ": " + operation.summary()).append('\n'));
                if (!capability.faults().isEmpty()) {
                    out.append("- Faults: ").append(String.join(", ", capability.faults())).append('\n');
                }
                out.append("- Visibility: ").append(capability.visibility().wireValue()).append("\n\n");
            }
            component.apiDocuments().forEach(api -> out.append("- Product API `").append(api.apiId()).append("`, served surfaces: ")
                    .append(api.servedSurfaces().isEmpty() ? "none" : String.join(", ", api.servedSurfaces())).append('\n'));
            if (!component.apiDocuments().isEmpty()) out.append('\n');
        }

        out.append("## Report\n\n| Capability | Provider | Surface | Status | Reason | Detail |\n|---|---|---|---|---|---|\n");
        for (ReportEntry entry : documentation.report().entries()) {
            out.append("| `").append(entry.capability()).append("` | ").append(entry.providerComponentUri()).append(" | ")
                    .append(entry.surface().wireValue()).append(" | ").append(entry.status().wireValue())
                    .append(" | ").append(entry.reason()).append(" | ").append(entry.detail() == null ? "" : escape(entry.detail()))
                    .append(" |\n");
        }
        if (!documentation.report().apiOperations().isEmpty()) {
            out.append("\n## Product API operations not shown\n\n");
            documentation.report().apiOperations().forEach(op -> out.append("- ").append(op.apiId()).append(": `")
                    .append(op.operation()).append("` ").append(op.reason()).append('\n'));
        }
        return out.toString();
    }

    /** Keeps a detail inside its table cell. */
    private static String escape(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
