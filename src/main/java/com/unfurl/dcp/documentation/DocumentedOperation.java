package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.claim.OperationDocumentation;

import java.util.Objects;

/**
 * Value Object: one documented operation of a capability (DCP HLD-C2 G.2), taken unchanged from the provider claim's
 * {@code documentation.operations}.
 *
 * @param operation      the exact {@code invocation.operation} value
 * @param summary        optional one-line summary
 * @param requestSchema  JSON Schema of the operation's input
 * @param responseSchema JSON Schema of the operation's successful output
 */
public record DocumentedOperation(String operation, String summary, ArtifactRef requestSchema, ArtifactRef responseSchema) {
    /** Validating constructor: the name and both schemas are required. */
    public DocumentedOperation {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestSchema, "requestSchema");
        Objects.requireNonNull(responseSchema, "responseSchema");
    }

    /** Factory: copies a claim's operation documentation. */
    public static DocumentedOperation of(OperationDocumentation documentation) {
        return new DocumentedOperation(documentation.operation(), documentation.summary(),
                documentation.requestSchema(), documentation.responseSchema());
    }
}
