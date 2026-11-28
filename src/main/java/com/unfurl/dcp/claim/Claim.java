package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.unfurl.dcp.fault.FaultPolicy;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Schema record: canonical DCP claim, including declared domain boundaries,
 * offers, integration ports, and first-class fault vocabulary.
 *
 * <p>{@code apiDocuments} (DCP HLD-C2 A.13) lists the component's shipped product API documents. It is serialized only
 * when non-empty, so a claim without any keeps its pre-existing canonical hash ({@link ClaimHash}).
 *
 * <p>{@code childClaims} (DCP HLD-C2 A.14) names the components an aggregate component is built from: its declared
 * containment. It too is serialized only when non-empty, so a claim that is not an aggregate keeps its hash.
 */
public record Claim(
        Identity identity,
        DomainAssertion domain,
        @NotEmpty List<Refusal> refusals,
        Dependencies dependencies,
        List<Offer> offers,
        ConflictResolution conflictResolution,
        NegotiationSurface negotiationSurface,
        IntegrationPorts integrationPorts,
        FaultPolicy faults,
        ClaimMetadata metadata,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ApiDocument> apiDocuments,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ChildClaim> childClaims
) {
    /**
     * Compact constructor: freezes repeated sections while preserving a missing
     * fault policy so ClaimValidator can report the required-section diagnostic.
     */
    public Claim {
        refusals = refusals == null ? List.of() : List.copyOf(refusals);
        offers = offers == null ? List.of() : List.copyOf(offers);
        apiDocuments = apiDocuments == null ? List.of() : List.copyOf(apiDocuments);
        childClaims = childClaims == null ? List.of() : List.copyOf(childClaims);
    }

    /**
     * Compatibility constructor: a claim that is not an aggregate (no {@code child_claims}, HLD-C2 A.14), so every
     * producer written before A.14 compiles unchanged.
     */
    public Claim(Identity identity, DomainAssertion domain, List<Refusal> refusals, Dependencies dependencies,
                 List<Offer> offers, ConflictResolution conflictResolution, NegotiationSurface negotiationSurface,
                 IntegrationPorts integrationPorts, FaultPolicy faults, ClaimMetadata metadata,
                 List<ApiDocument> apiDocuments) {
        this(identity, domain, refusals, dependencies, offers, conflictResolution, negotiationSurface, integrationPorts,
                faults, metadata, apiDocuments, List.of());
    }

    /**
     * Compatibility constructor: a claim without API documents, so every producer written before HLD-C2 A.13 compiles
     * unchanged.
     */
    public Claim(Identity identity, DomainAssertion domain, List<Refusal> refusals, Dependencies dependencies,
                 List<Offer> offers, ConflictResolution conflictResolution, NegotiationSurface negotiationSurface,
                 IntegrationPorts integrationPorts, FaultPolicy faults, ClaimMetadata metadata) {
        this(identity, domain, refusals, dependencies, offers, conflictResolution, negotiationSurface, integrationPorts,
                faults, metadata, List.of(), List.of());
    }
}
