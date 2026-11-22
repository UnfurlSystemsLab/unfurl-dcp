package com.unfurl.dcp.claim;

/**
 * Value Object: the payload schemas of one invocation operation of an offer (DCP HLD-C2 A.6).
 *
 * <p>The operation is selected by the envelope's {@code invocation.operation}; these schemas describe only that
 * operation's {@code input} and successful {@code output}, never the operation name itself. Both schemas have a root
 * {@code type: object}, which {@code ClaimArtifactValidator} checks because only it holds the bytes.
 *
 * @param operation      the exact {@code invocation.operation} value; unique within the offer
 * @param summary        optional one-line summary for generated documentation
 * @param requestSchema  JSON Schema of this operation's {@code invocation.input}
 * @param responseSchema JSON Schema of this operation's successful {@code output}
 */
public record OperationDocumentation(String operation, String summary, ArtifactRef requestSchema, ArtifactRef responseSchema) {
}
