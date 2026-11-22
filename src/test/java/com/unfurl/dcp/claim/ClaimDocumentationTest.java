package com.unfurl.dcp.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.testing.DocumentationFixtures;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.unfurl.dcp.testing.DocumentationFixtures.OBJECT_SCHEMA;
import static com.unfurl.dcp.testing.DocumentationFixtures.documented;
import static com.unfurl.dcp.testing.DocumentationFixtures.openApiRef;
import static com.unfurl.dcp.testing.DocumentationFixtures.operation;
import static com.unfurl.dcp.testing.DocumentationFixtures.schemaRef;
import static com.unfurl.dcp.testing.DocumentationFixtures.withApiDocuments;
import static com.unfurl.dcp.testing.DocumentationFixtures.withOffers;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the claim's documentation fields (DCP HLD-C2 A.6, A.12, A.13): they round-trip on the snake_case wire, leave
 * existing claim hashes untouched when absent, keep pre-existing constructors working, accept the specification's
 * {@code interface}/{@code kind} spelling, and are validated by {@link ClaimValidator} with stable codes.
 */
class ClaimDocumentationTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ClaimValidator validator = new ClaimValidator();

    /** The compatibility constructors produce exactly the canonical records with absent documentation. */
    @Test
    void compatibilityConstructorsMeanAbsentDocumentation() {
        Claim claim = Fixtures.validProviderClaim();

        assertThat(claim.apiDocuments()).isEmpty();
        assertThat(claim.offers()).allSatisfy(offer -> assertThat(offer.documentation()).isNull());
        assertThat(withApiDocuments(claim, List.of())).isEqualTo(claim);
    }

    /** Documenting an offer changes the claim hash, so a pinned claim also pins its schemas. */
    @Test
    void documentationIsCoveredByTheClaimHash() {
        Claim plain = Fixtures.validProviderClaim();
        Claim documentedClaim = DocumentationFixtures.documentedClaim(plain);

        assertThat(ClaimHash.canonical(documentedClaim)).isNotEqualTo(ClaimHash.canonical(plain));
        assertThat(ClaimHash.canonical(documentedClaim)).isEqualTo(ClaimHash.canonical(DocumentationFixtures.documentedClaim(plain)));
    }

    /** A documented claim with API documents round-trips through the packaged-manifest reader on the snake_case wire. */
    @Test
    void documentedClaimRoundTripsThroughThePackagedReader() throws Exception {
        Claim claim = withApiDocuments(DocumentationFixtures.documentedClaim(Fixtures.validProviderClaim()), List.of(
                new ApiDocument("ops-api", "Operations routes", openApiRef("dcp/openapi/ops.openapi.yaml", "openapi: 3.1.0"), List.of("health"))));

        byte[] manifest = manifest(claim);
        String yaml = new String(manifest);

        assertThat(yaml).contains("api_documents:", "request_schema:", "media_type: \"application/schema+json\"",
                "media_type: \"application/vnd.oai.openapi+yaml\"");
        assertThat(PackagedClaim.parse(manifest)).isEqualTo(claim);
    }

    /** The specification's {@code interface}/{@code kind} spelling is read, so a spec-shaped interface is never dropped. */
    @Test
    void specSpellingOfTheOfferInterfaceIsAccepted() throws Exception {
        Claim claim = Fixtures.validProviderClaim();
        ObjectNode root = YAML.valueToTree(Map.of("claim", claim));
        ObjectNode offer = (ObjectNode) root.path("claim").path("offers").get(0);
        ObjectNode iface = (ObjectNode) offer.remove("offer_interface");
        iface.set("kind", iface.remove("interface_kind"));
        offer.set("interface", iface);

        Claim parsed = PackagedClaim.parse(YAML.writeValueAsBytes(root));

        assertThat(parsed.offers().get(0).offerInterface()).isEqualTo(claim.offers().get(0).offerInterface());
    }

    /** A fully documented claim is valid. */
    @Test
    void documentedClaimIsValid() {
        Claim claim = withApiDocuments(DocumentationFixtures.documentedClaim(Fixtures.validProviderClaim()), List.of(
                new ApiDocument("ops-api", "Operations routes", openApiRef("dcp/openapi/ops.openapi.yaml", "x"), List.of("health", "ops-admin"))));

        assertThat(validator.validate(claim).valid()).isTrue();
    }

    /** {@code registrations} is reserved by the DCP HTTP binding (HLD-C2 G.3), so no offer may use it. */
    @Test
    void theRegistrationsCapabilityNameIsReserved() {
        Offer offer = Fixtures.validProviderClaim().offers().get(0);
        Offer reserved = new Offer("registrations", offer.description(), offer.consumerAccess(), offer.offerInterface(),
                offer.stability(), offer.version(), offer.metered(), offer.costImplications(), offer.documentation());

        assertThat(codes(validator.validate(withOffers(Fixtures.validProviderClaim(), List.of(reserved)))))
                .contains(ErrorCode.CLAIM_CAPABILITY_RESERVED);
        assertThat(codes(validator.validate(Fixtures.validProviderClaim()))).doesNotContain(ErrorCode.CLAIM_CAPABILITY_RESERVED);
    }

    /**
     * An external_protocol offer (HLD-C2 A.6) names its protocol and carries no documentation: a missing or blank
     * protocol and any documentation are each {@code CLAIM_EXTERNAL_PROTOCOL_INVALID}; a protocol without documentation is
     * valid; other kinds are not affected by the rule.
     */
    @Test
    void externalProtocolOffersNameTheirProtocolAndCarryNoDocumentation() {
        Offer base = Fixtures.validProviderClaim().offers().get(0);
        java.util.function.BiFunction<Map<String, Object>, OfferDocumentation, Claim> claimWith = (details, documentation) ->
                withOffers(Fixtures.validProviderClaim(), List.of(new Offer(base.capability(), base.description(),
                        base.consumerAccess(), new OfferInterface(InterfaceKind.EXTERNAL_PROTOCOL, details), base.stability(),
                        base.version(), false, null, documentation)));

        assertThat(validator.validate(claimWith.apply(Map.of("protocol", "oauth2"), null)).valid()).isTrue();
        assertThat(codes(validator.validate(claimWith.apply(Map.of(), null))))
                .containsExactly(ErrorCode.CLAIM_EXTERNAL_PROTOCOL_INVALID);
        assertThat(codes(validator.validate(claimWith.apply(Map.of("protocol", " "), null))))
                .containsExactly(ErrorCode.CLAIM_EXTERNAL_PROTOCOL_INVALID);
        assertThat(codes(validator.validate(claimWith.apply(Map.of("protocol", "oauth2"),
                DocumentationFixtures.documentedClaim(Fixtures.validProviderClaim()).offers().get(0).documentation()))))
                .containsExactly(ErrorCode.CLAIM_EXTERNAL_PROTOCOL_INVALID);
        assertThat(codes(validator.validate(Fixtures.validProviderClaim()))).doesNotContain(ErrorCode.CLAIM_EXTERNAL_PROTOCOL_INVALID);
    }

    /** Documentation with no operations is incomplete. */
    @Test
    void emptyOperationsAreIncomplete() {
        Offer offer = Fixtures.validProviderClaim().offers().get(0);
        Claim claim = withOffers(Fixtures.validProviderClaim(), List.of(documented(offer)));

        assertThat(codes(validator.validate(claim))).contains(ErrorCode.CLAIM_DOCUMENTATION_INCOMPLETE);
    }

    /** Operation names are unique within an offer, and a missing schema is incomplete. */
    @Test
    void duplicateOperationsAndMissingSchemasAreRejected() {
        Offer offer = Fixtures.validProviderClaim().offers().get(0);
        OperationDocumentation one = operation("answer.search", "run");
        OperationDocumentation missing = new OperationDocumentation("other", null, one.requestSchema(), null);
        Claim claim = withOffers(Fixtures.validProviderClaim(), List.of(documented(offer, one, one, missing)));

        assertThat(codes(validator.validate(claim)))
                .contains(ErrorCode.CLAIM_DOCUMENTATION_OPERATION_DUPLICATE, ErrorCode.CLAIM_DOCUMENTATION_INCOMPLETE);
    }

    /** Payload schemas must be JSON Schemas. */
    @Test
    void payloadSchemasMustBeJsonSchemas() {
        Offer offer = Fixtures.validProviderClaim().offers().get(0);
        OperationDocumentation wrong = operation("run", openApiRef("dcp/a.yaml", "x"), schemaRef("dcp/b.json", OBJECT_SCHEMA));
        Claim claim = withOffers(Fixtures.validProviderClaim(), List.of(documented(offer, wrong)));

        assertThat(codes(validator.validate(claim))).containsExactly(ErrorCode.CLAIM_DOCUMENTATION_MEDIA_TYPE);
    }

    /** Unsafe paths and malformed hashes are {@code ARTIFACT_REF_INVALID} (A.12). */
    @ParameterizedTest
    @ValueSource(strings = {"/abs/schema.json", "../up.json", "dcp/../up.json", "dcp//x.json", "dcp\\x.json",
            "https://example.com/s.json", "./x.json", ""})
    void unsafePathsAreRejected(String path) {
        assertThat(new ArtifactRef(path, ArtifactMediaType.SCHEMA_JSON, DocumentationFixtures.sha256(new byte[0])).shapeViolation()).isPresent();
    }

    /** Only 64 lower-case hex characters are a valid hash. */
    @Test
    void malformedHashesAreRejected() {
        String good = DocumentationFixtures.sha256(new byte[0]);
        assertThat(new ArtifactRef("dcp/s.json", ArtifactMediaType.SCHEMA_JSON, good).shapeViolation()).isEmpty();
        assertThat(new ArtifactRef("dcp/s.json", ArtifactMediaType.SCHEMA_JSON, good.toUpperCase()).shapeViolation()).isPresent();
        assertThat(new ArtifactRef("dcp/s.json", ArtifactMediaType.SCHEMA_JSON, good.substring(1)).shapeViolation()).isPresent();
        assertThat(new ArtifactRef("dcp/s.json", null, good).shapeViolation()).isPresent();
    }

    /** A malformed ref inside a documented offer surfaces as a claim diagnostic, not a parse failure. */
    @Test
    void malformedRefIsAClaimDiagnostic() {
        Offer offer = Fixtures.validProviderClaim().offers().get(0);
        OperationDocumentation bad = operation("run", new ArtifactRef("../x.json", ArtifactMediaType.SCHEMA_JSON, "nope"),
                schemaRef("dcp/b.json", OBJECT_SCHEMA));
        Claim claim = withOffers(Fixtures.validProviderClaim(), List.of(documented(offer, bad)));

        assertThat(codes(validator.validate(claim))).containsExactly(ErrorCode.ARTIFACT_REF_INVALID);
    }

    /** Schema hints left in interface details only warn: the claim stays valid, and the hint is not documentation. */
    @Test
    void schemaHintsWithoutDocumentationOnlyWarn() {
        Offer plain = Fixtures.validProviderClaim().offers().get(0);
        Offer hinted = new Offer(plain.capability(), plain.description(), plain.consumerAccess(),
                new OfferInterface(InterfaceKind.IN_PROCESS, Map.of("input_schema", "schemas/x.json")), plain.stability(),
                plain.version(), false, null);
        SchemaValidationReport report = validator.validate(withOffers(Fixtures.validProviderClaim(), List.of(hinted)));

        assertThat(report.valid()).isTrue();
        assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.code()).isEqualTo(ErrorCode.CLAIM_DOCUMENTATION_HINT_ONLY));
    }

    /** API documents need a kebab-case unique id, a description, kebab-case surfaces and an OpenAPI reference. */
    @Test
    void apiDocumentRulesAreEnforced() {
        ArtifactRef openApi = openApiRef("dcp/openapi/a.yaml", "x");
        Claim claim = withApiDocuments(Fixtures.validProviderClaim(), List.of(
                new ApiDocument("Ops_API", "d", openApi, List.of("health")),
                new ApiDocument("ops", "d", openApi, List.of()),
                new ApiDocument("ops", "", schemaRef("dcp/s.json", OBJECT_SCHEMA), List.of("Not Kebab"))));

        List<Diagnostic> errors = validator.validate(claim).diagnostics();

        assertThat(errors).extracting(Diagnostic::code).containsOnly(ErrorCode.CLAIM_API_DOCUMENT_INVALID);
        assertThat(errors).hasSize(6);
    }

    /** Helper: the error codes of a report. */
    private static List<ErrorCode> codes(SchemaValidationReport report) {
        return report.diagnostics().stream().filter(d -> d.severity() == com.unfurl.dcp.validation.Severity.ERROR).map(Diagnostic::code).toList();
    }

    /** Helper: a packaged manifest holding the claim, as a component's build writes it. */
    private static byte[] manifest(Claim claim) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("claim", claim);
        manifest.put("catalog", Map.of("lifecycle", Map.of("status", "ACTIVE")));
        return YAML.writeValueAsBytes(manifest);
    }
}
