package com.unfurl.dcp.testing;

import com.unfurl.dcp.claim.*;
import com.unfurl.dcp.contract.*;
import com.unfurl.dcp.fault.*;
import com.unfurl.dcp.trust.SignedContract;
import com.unfurl.dcp.trust.TrustTier;

import java.net.URI;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Test fixture factory: centralizes valid DCP claims, contracts, signatures,
 * and declared fault policies used across unit and property tests.
 */
public final class Fixtures {
    private Fixtures() {
    }

    public static Claim validProviderClaim() {
        return new Claim(
                new Identity(URI.create("urn:provider"), "Provider", ComponentKind.COMPONENT, "1.0.0", "Unfurl", URI.create("urn:publisher")),
                new DomainAssertion("provider", List.of(new Concern("answers", "answers requests", null, List.of(), List.of())), List.of("no silent ownership changes")),
                List.of(new Refusal("billing", "billing belongs to finance", "urn:finance")),
                new Dependencies(List.of()),
                List.of(new Offer("answer.search", "Search answers", ConsumerAccess.ANY, new OfferInterface(InterfaceKind.IN_PROCESS, Map.of()), Stability.STABLE, "1.0.0", false, null)),
                new ConflictResolution(List.of(), List.of(), false),
                null,
                new IntegrationPorts(Map.of()),
                providerFaultPolicy(),
                new ClaimMetadata("0.2.0", "1.0.0", Instant.EPOCH, Map.of()));
    }

    /**
     * Fixture factory: declares one provider fault that degrades the parent
     * graph when answer.search cannot satisfy its capability.
     */
    public static FaultPolicy providerFaultPolicy() {
        return new FaultPolicy(List.of(new FaultDeclaration(
                "answer.search.timeout",
                FaultCategory.DEPENDENCY,
                FaultSeverity.DEGRADED,
                "Search provider timed out before satisfying answer.search",
                new FaultAffects(List.of("answer.search"), List.of("answer.search"), List.of()),
                new FaultEvidence(List.of(EvidenceSignal.INVOCATION_ERROR, EvidenceSignal.METRIC_THRESHOLD)),
                new FaultPropagation(ParentImpact.DEGRADED, "active binding uses answer.search", "alternate provider healthy"),
                new FaultRemediation(List.of("retry_with_backoff", "switch_provider")))));
    }

    public static CompositionContract validContract() {
        return new CompositionContract(
                URI.create("urn:contract"),
                "1.0.0",
                new Parties(new Party(URI.create("urn:consumer"), "1.0.0"), new Party(URI.create("urn:provider"), "1.0.0")),
                new Binding("answer.search", "answer.search", ">=1.0.0"),
                new DataMapping(Map.of(), Map.of()),
                new Transport(TransportKind.IN_PROCESS, Map.of()),
                new Expectations(1000, true, false, true),
                new Provenance(CreatedBy.FABRIC, NegotiationMode.C2C, "fabric-model", "0.2.0", false, Instant.EPOCH),
                new Trust(TrustTier.NEUTRAL),
                new Invalidation(List.of(InvalidationTrigger.CLAIM_VERSION_CHANGED), RuntimeViolationPolicy.HARD_FAIL));
    }

    public static FrozenContract frozenContract(KeyPair keyPair) {
        try {
            byte[] canonical = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(canonical);
            SignedContract signed = new SignedContract(canonical, signature.sign(), "SHA256withRSA", "test-key");
            return new FrozenContract(canonical, validContract(), signed);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
