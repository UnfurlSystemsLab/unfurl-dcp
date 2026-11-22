package com.unfurl.dcp.testing;

import com.unfurl.dcp.claim.ApiDocument;
import com.unfurl.dcp.claim.ArtifactMediaType;
import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.claim.Identity;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OfferDocumentation;
import com.unfurl.dcp.claim.OperationDocumentation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test Data Builder: claims with documented offers and API documents (DCP HLD-C2 A.6, A.12, A.13), plus the file bytes
 * their {@link ArtifactRef}s pin. Every ref is built from the bytes it names, so its hash is always right unless a test
 * changes one on purpose.
 */
public final class DocumentationFixtures {
    /** A minimal object-rooted JSON Schema. */
    public static final String OBJECT_SCHEMA = "{\"type\":\"object\"}";

    private DocumentationFixtures() {
    }

    /** Hash helper: lower-case hex SHA-256 of the bytes, by {@link ArtifactRef#sha256Of}, the one hash rule. */
    public static String sha256(byte[] bytes) {
        return ArtifactRef.sha256Of(bytes);
    }

    /** Builds a JSON Schema reference whose hash matches {@code content}. */
    public static ArtifactRef schemaRef(String path, String content) {
        return new ArtifactRef(path, ArtifactMediaType.SCHEMA_JSON, sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Builds an OpenAPI YAML reference whose hash matches {@code content}. */
    public static ArtifactRef openApiRef(String path, String content) {
        return new ArtifactRef(path, ArtifactMediaType.OPENAPI_YAML, sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** One documented operation whose request and response schemas are the given files. */
    public static OperationDocumentation operation(String name, ArtifactRef request, ArtifactRef response) {
        return new OperationDocumentation(name, "Operation " + name, request, response);
    }

    /** One documented operation using {@link #OBJECT_SCHEMA} for both payloads, at conventional paths. */
    public static OperationDocumentation operation(String capability, String name) {
        return operation(name,
                schemaRef("dcp/schemas/" + capability + "." + name + ".request.schema.json", OBJECT_SCHEMA),
                schemaRef("dcp/schemas/" + capability + "." + name + ".response.schema.json", OBJECT_SCHEMA));
    }

    /** Returns {@code offer} with documentation for the given operations. */
    public static Offer documented(Offer offer, OperationDocumentation... operations) {
        return new Offer(offer.capability(), offer.description(), offer.consumerAccess(), offer.offerInterface(),
                offer.stability(), offer.version(), offer.metered(), offer.costImplications(),
                new OfferDocumentation("Capability " + offer.capability(), List.of(operations)));
    }

    /** Returns {@code claim} with each offer documented with one operation named after its capability. */
    public static Claim documentedClaim(Claim claim) {
        List<Offer> offers = claim.offers().stream()
                .map(offer -> documented(offer, operation(offer.capability(), offer.capability())))
                .toList();
        return withOffers(claim, offers);
    }

    /** Returns {@code claim} with its offers replaced. */
    public static Claim withOffers(Claim claim, List<Offer> offers) {
        return new Claim(claim.identity(), claim.domain(), claim.refusals(), claim.dependencies(), offers,
                claim.conflictResolution(), claim.negotiationSurface(), claim.integrationPorts(), claim.faults(),
                claim.metadata(), claim.apiDocuments());
    }

    /** Returns {@code claim} with its API documents replaced. */
    public static Claim withApiDocuments(Claim claim, List<ApiDocument> documents) {
        return new Claim(claim.identity(), claim.domain(), claim.refusals(), claim.dependencies(), claim.offers(),
                claim.conflictResolution(), claim.negotiationSurface(), claim.integrationPorts(), claim.faults(),
                claim.metadata(), documents);
    }

    /** Returns {@code claim} under another identity URI and version, keeping metadata consistent. */
    public static Claim withIdentity(Claim claim, String uri, String version) {
        Identity id = claim.identity();
        ClaimMetadata md = claim.metadata();
        return new Claim(new Identity(URI.create(uri), id.name(), id.kind(), version, id.publisher(), id.publisherUri()),
                claim.domain(), claim.refusals(), claim.dependencies(), claim.offers(), claim.conflictResolution(),
                claim.negotiationSurface(), claim.integrationPorts(), claim.faults(),
                new ClaimMetadata(md.dcpVersion(), version, md.createdAt(), md.extensions()), claim.apiDocuments());
    }

    /**
     * Builds a child contract binding {@code capability} from {@code provider@providerVersion} to
     * {@code consumer@consumerVersion}, with the given metadata extensions (for example {@code claimHash} or
     * {@code neededBy}).
     */
    public static com.unfurl.dcp.contract.CompositionContract contract(String id, String capability,
                                                                      String provider, String providerVersion,
                                                                      String consumer, String consumerVersion,
                                                                      Map<String, Object> extensions) {
        com.unfurl.dcp.contract.CompositionContract base = Fixtures.validContract();
        return new com.unfurl.dcp.contract.CompositionContract(URI.create(id), "1.0.0",
                new com.unfurl.dcp.contract.Parties(new com.unfurl.dcp.contract.Party(URI.create(consumer), consumerVersion),
                        new com.unfurl.dcp.contract.Party(URI.create(provider), providerVersion)),
                new com.unfurl.dcp.contract.Binding(capability, capability, providerVersion),
                base.dataMapping(), base.transport(), base.expectations(), base.provenance(), base.trust(),
                base.invalidation(), new com.unfurl.dcp.contract.CompositionContractMetadata(extensions));
    }

    /** The file bytes every ref of {@link #documentedClaim} names, keyed by path. */
    public static Map<String, byte[]> objectSchemaFiles(Claim documented) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        documented.offers().forEach(offer -> {
            if (offer.documentation() == null) return;
            offer.documentation().operations().forEach(op -> {
                files.put(op.requestSchema().path(), OBJECT_SCHEMA.getBytes(StandardCharsets.UTF_8));
                files.put(op.responseSchema().path(), OBJECT_SCHEMA.getBytes(StandardCharsets.UTF_8));
            });
        });
        return files;
    }
}
