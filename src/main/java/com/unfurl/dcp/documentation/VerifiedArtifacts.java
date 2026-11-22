package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ApiDocument;
import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.OperationDocumentation;
import com.unfurl.dcp.validation.ErrorCode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapter (in-memory, read-only): the verified bytes of every artifact a projection uses, captured once when it plans.
 * Renderers read from it, so rendering does no I/O and always sees the bytes that were validated.
 */
final class VerifiedArtifacts implements DocumentationArtifactStore {
    private final Map<String, byte[]> bySha;

    private VerifiedArtifacts(Map<String, byte[]> bySha) {
        this.bySha = bySha;
    }

    /** Factory: reads and verifies every reference of the given claims from {@code source}. */
    static VerifiedArtifacts capture(Iterable<Claim> claims, DocumentationArtifactStore source) {
        Map<String, byte[]> bytes = new HashMap<>();
        for (Claim claim : claims) {
            claim.offers().forEach(offer -> {
                if (offer.documentation() == null) return;
                for (OperationDocumentation operation : offer.documentation().operations()) {
                    put(bytes, operation.requestSchema(), source);
                    put(bytes, operation.responseSchema(), source);
                }
            });
            for (ApiDocument document : claim.apiDocuments()) {
                put(bytes, document.openapi(), source);
            }
        }
        return new VerifiedArtifacts(Collections.unmodifiableMap(bytes));
    }

    /** Factory: the union of several captures. */
    static VerifiedArtifacts union(Iterable<VerifiedArtifacts> parts) {
        Map<String, byte[]> bytes = new HashMap<>();
        parts.forEach(part -> bytes.putAll(part.bySha));
        return new VerifiedArtifacts(Collections.unmodifiableMap(bytes));
    }

    private static void put(Map<String, byte[]> bytes, ArtifactRef ref, DocumentationArtifactStore source) {
        if (ref != null && !bytes.containsKey(ref.sha256())) {
            bytes.put(ref.sha256(), source.read(ref));
        }
    }

    /** Returns a copy of captured bytes; a reference that was never captured is unavailable. */
    @Override
    public byte[] read(ArtifactRef ref) {
        Artifacts.requireWellFormed(ref);
        byte[] bytes = bySha.get(ref.sha256());
        if (bytes == null) {
            throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE, ref.path() + " was not captured by the plan");
        }
        return Artifacts.verified(ref, bytes, "the plan's verified artifacts");
    }
}
