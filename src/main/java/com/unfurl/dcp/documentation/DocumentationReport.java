package com.unfurl.dcp.documentation;

import java.util.Comparator;
import java.util.List;

/**
 * Value Object: the documentation report (DCP HLD-C2 G.4): one entry per child contract and every product API operation
 * that was not rendered.
 *
 * @param entries       sorted by contract id
 * @param apiOperations sorted by component, document and operation
 */
public record DocumentationReport(List<ReportEntry> entries, List<ApiOperationExclusion> apiOperations) {
    /** Defensive-copy constructor: sorted copies, so equal reports are equal records. */
    public DocumentationReport {
        entries = entries == null ? List.of() : entries.stream()
                .sorted(Comparator.comparing((ReportEntry e) -> e.contractId().toString())).toList();
        apiOperations = apiOperations == null ? List.of() : apiOperations.stream()
                .sorted(Comparator.comparing((ApiOperationExclusion e) -> e.componentUri().toString())
                        .thenComparing(ApiOperationExclusion::apiId)
                        .thenComparing(ApiOperationExclusion::operation))
                .toList();
    }
}
