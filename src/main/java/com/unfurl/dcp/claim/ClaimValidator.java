package com.unfurl.dcp.claim;

import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;
import com.unfurl.dcp.versioning.SemverHelpers;
import com.unfurl.dcp.fault.FaultDeclaration;
import com.unfurl.dcp.fault.ParentImpact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validator: enforces DCP claim invariants across domain boundaries, offers,
 * metadata, and declared fault vocabulary using structured diagnostics.
 */
public final class ClaimValidator {
    private final SemverHelpers semver = new SemverHelpers();

    /**
     * Validate one claim and return deterministic diagnostics for expected
     * schema failures instead of throwing.
     */
    public SchemaValidationReport validate(Claim claim) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (claim == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "claim is required", "$"));
            return new SchemaValidationReport(diagnostics);
        }
        if (claim.identity() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "identity is required", "identity"));
        }
        if (claim.domain() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "domain is required", "domain"));
        } else {
            if (claim.domain().boundaryPrinciples().isEmpty()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "boundary principles are required", "domain.boundary_principles"));
            }
            HashSet<String> concerns = new HashSet<>();
            for (Concern concern : claim.domain().concerns()) {
                if (concern.concern() != null && !concerns.add(concern.concern())) {
                    diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "concern identifiers must be unique", "domain.concerns"));
                }
            }
        }
        if (claim.refusals().isEmpty()) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "refusals are required", "refusals"));
        }
        if (claim.identity() != null
                && claim.identity().kind() == ComponentKind.INTELLIGENT_COMPONENT
                && claim.negotiationSurface() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "intelligent components require a negotiation surface", "negotiation_surface"));
        }
        if (claim.metadata() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "metadata is required", "metadata"));
        } else {
            if (claim.metadata().dcpVersion() == null || claim.metadata().dcpVersion().isBlank()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "metadata.dcp_version is required", "metadata.dcp_version"));
            } else if (!semver.satisfies(claim.metadata().dcpVersion(), ">=0.2.0")) {
                diagnostics.add(Diagnostic.error(ErrorCode.DCP_VERSION_UNSUPPORTED, "metadata.dcp_version must be >= 0.2.0", "metadata.dcp_version"));
            }
            if (claim.identity() != null && !Objects.equals(claim.identity().version(), claim.metadata().claimVersion())) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "metadata.claim_version must match identity.version", "metadata.claim_version"));
            }
        }
        for (Offer offer : claim.offers()) {
            validateOffer(offer, diagnostics);
        }
        validateApiDocuments(claim.apiDocuments(), diagnostics);
        validateDependencies(claim.dependencies(), diagnostics);
        validateIntegrationPorts(claim.integrationPorts(), diagnostics);
        validateFaults(claim, diagnostics);
        for (Refusal refusal : claim.refusals()) {
            if ("everything-else".equalsIgnoreCase(refusal.concern()) || refusal.rationale() == null || refusal.rationale().length() < 12) {
                diagnostics.add(Diagnostic.warning(ErrorCode.CLAIM_MALFORMED, "refusal specificity is weak", "refusals"));
            }
        }
        return new SchemaValidationReport(diagnostics);
    }

    /**
     * Helper: validates the typed integration ports against their HLD-C2 A.9 rules. The wiring map ({@code ports}) is
     * opaque to DCP and is not checked, and an absent typed port is simply not declared. Enum values are checked by the
     * codec when the claim is read.
     * <ul>
     *   <li>a declared port is complete: each member written without {@code ?} in HLD-C2 A.9 is present, never
     *       defaulted ("A declared port is complete");</li>
     *   <li>each {@code monitoring.health_endpoints} entry is an absolute path (starts with {@code /}, no scheme, host
     *       or query): the host and port are deployment facts (§C);</li>
     *   <li>no list repeats an entry.</li>
     * </ul>
     */
    private void validateIntegrationPorts(IntegrationPorts ports, List<Diagnostic> diagnostics) {
        if (ports == null) {
            return;
        }
        String base = "integration_ports.";
        if (ports.authentication() != null) {
            AuthenticationPort port = ports.authentication();
            requirePresent(port.required(), base + "authentication.required", diagnostics);
            requirePresent(port.acceptedModes(), base + "authentication.accepted_modes", diagnostics);
            requireDistinct(port.acceptedModes(), base + "authentication.accepted_modes", diagnostics);
        }
        if (ports.authorization() != null) {
            AuthorizationPort port = ports.authorization();
            requirePresent(port.required(), base + "authorization.required", diagnostics);
            requirePresent(port.decisionOwner(), base + "authorization.decision_owner", diagnostics);
            requireDistinct(port.permissionPrefixes(), base + "authorization.permission_prefixes", diagnostics);
        }
        if (ports.telemetry() != null) {
            TelemetryPort port = ports.telemetry();
            requirePresent(port.required(), base + "telemetry.required", diagnostics);
            requirePresent(port.signals(), base + "telemetry.signals", diagnostics);
            requirePresent(port.correlationIdRequired(), base + "telemetry.correlation_id_required", diagnostics);
            requireDistinct(port.signals(), base + "telemetry.signals", diagnostics);
        }
        if (ports.monitoring() != null) {
            MonitoringPort port = ports.monitoring();
            requirePresent(port.required(), base + "monitoring.required", diagnostics);
            requirePresent(port.readinessRequired(), base + "monitoring.readiness_required", diagnostics);
            requirePresent(port.livenessRequired(), base + "monitoring.liveness_required", diagnostics);
            if (port.healthEndpoints() != null) {
                for (String endpoint : port.healthEndpoints()) {
                    if (endpoint == null || endpoint.isBlank() || !endpoint.startsWith("/") || endpoint.startsWith("//")
                            || endpoint.contains("?") || endpoint.contains("#")) {
                        diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_INTEGRATION_PORT_INVALID, "health endpoint '"
                                + endpoint + "' must be an absolute path such as /health, never a URL",
                                base + "monitoring.health_endpoints"));
                    }
                }
            }
            requireDistinct(port.healthEndpoints(), base + "monitoring.health_endpoints", diagnostics);
        }
        if (ports.ai() != null) {
            AiPort port = ports.ai();
            requirePresent(port.required(), base + "ai.required", diagnostics);
            requireDistinct(port.intents(), base + "ai.intents", diagnostics);
        }
    }

    /** Helper: reports a required member of a declared port that the claim did not write (HLD-C2 A.9). */
    private static void requirePresent(Object value, String path, List<Diagnostic> diagnostics) {
        if (value == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_INTEGRATION_PORT_INVALID,
                    path + " is required in a declared port; it is never defaulted", path));
        }
    }

    /** Helper: reports a present list that repeats an entry. */
    private static void requireDistinct(List<?> values, String path, List<Diagnostic> diagnostics) {
        if (values != null && new HashSet<>(values).size() != values.size()) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_INTEGRATION_PORT_INVALID, "entries must not repeat", path));
        }
    }

    /**
     * Helper: validates versioning and cost invariants for one provider offer.
     */
    private void validateOffer(Offer offer, List<Diagnostic> diagnostics) {
        if (offer == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "offer is required", "offers"));
            return;
        }
        if (!semver.satisfies(offer.version(), "*")) {
            diagnostics.add(Diagnostic.error(ErrorCode.OFFER_VERSION_UNSATISFIED, "offer version must be SemVer", "offers.version"));
        }
        // The report and the invoke paths share /dcp/, so a capability named after the report would shadow it (G.3).
        if (RESERVED_CAPABILITIES.contains(offer.capability())) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_CAPABILITY_RESERVED, "capability " + offer.capability()
                    + " is reserved by the DCP HTTP binding (GET <service_root>/dcp/registrations)", "offers.capability"));
        }
        boolean negotiation = offer.offerInterface() != null && offer.offerInterface().interfaceKind() == InterfaceKind.NEGOTIATION;
        if ((offer.metered() || negotiation) && (offer.costImplications() == null || offer.costImplications().isBlank())) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "cost_implications are required for metered or negotiation offers", "offers.cost_implications"));
        }
        validateDocumentation(offer, diagnostics);
        validateExternalProtocol(offer, diagnostics);
    }

    /**
     * Helper: validates an {@code external_protocol} offer (DCP HLD-C2 A.6). It names its protocol in a non-blank string
     * {@code details.protocol}, and carries no {@code documentation}, because it is never invoked through the DCP
     * envelope and so has no DCP payload. Offers of every other kind are not affected.
     */
    private void validateExternalProtocol(Offer offer, List<Diagnostic> diagnostics) {
        if (offer.offerInterface() == null || offer.offerInterface().interfaceKind() != InterfaceKind.EXTERNAL_PROTOCOL) {
            return;
        }
        String at = "offers[" + offer.capability() + "]";
        Object protocol = offer.offerInterface().details().get("protocol");
        if (!(protocol instanceof String name) || name.isBlank()) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_EXTERNAL_PROTOCOL_INVALID,
                    "an external_protocol offer names its protocol in a non-blank interface.details.protocol",
                    at + ".interface.details.protocol"));
        }
        if (offer.documentation() != null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_EXTERNAL_PROTOCOL_INVALID,
                    "an external_protocol offer is never invoked through the DCP envelope, so it has no documentation",
                    at + ".documentation"));
        }
    }

    /**
     * Capability names the DCP HTTP binding reserves (HLD-C2 G.3): {@code registrations} is the path segment of
     * {@code GET <service_root>/dcp/registrations}, which shares the {@code /dcp/} namespace with invocation.
     */
    public static final Set<String> RESERVED_CAPABILITIES = Set.of("registrations");
    /** Detail keys that earlier claims used as unenforced payload-schema hints (DCP HLD-C2 A.6). */
    private static final List<String> SCHEMA_HINT_KEYS = List.of("input_schema", "output_schema");
    /** Kebab-case identifiers, as A.13 requires for api ids and surfaces. */
    private static final Pattern KEBAB = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * Helper: validates an offer's documentation shape (DCP HLD-C2 A.6, A.12). Operations are non-empty with non-blank,
     * unique names, and every operation has two well-formed JSON Schema references. The schemas' contents (root object,
     * local references) are checked by ClaimArtifactValidator, which holds the bytes. Schema hints left in
     * {@code interface.details} without documentation produce a warning: they keep loading, but they never count as
     * documentation.
     */
    private void validateDocumentation(Offer offer, List<Diagnostic> diagnostics) {
        String at = "offers[" + offer.capability() + "].documentation";
        OfferDocumentation documentation = offer.documentation();
        if (documentation == null) {
            if (offer.offerInterface() != null
                    && offer.offerInterface().details().keySet().stream().anyMatch(SCHEMA_HINT_KEYS::contains)) {
                diagnostics.add(Diagnostic.warning(ErrorCode.CLAIM_DOCUMENTATION_HINT_ONLY,
                        "interface.details carries input_schema/output_schema hints but the offer has no documentation;"
                                + " state payload schemas through documentation.operations (DCP HLD-C2 A.6)",
                        "offers[" + offer.capability() + "].interface.details"));
            }
            return;
        }
        if (documentation.operations().isEmpty()) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_DOCUMENTATION_INCOMPLETE,
                    "documentation.operations must not be empty", at + ".operations"));
            return;
        }
        HashSet<String> names = new HashSet<>();
        for (OperationDocumentation operation : documentation.operations()) {
            if (operation == null || operation.operation() == null || operation.operation().isBlank()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_DOCUMENTATION_INCOMPLETE,
                        "every documented operation needs a non-blank operation name", at + ".operations"));
                continue;
            }
            String opAt = at + ".operations[" + operation.operation() + "]";
            if (!names.add(operation.operation())) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_DOCUMENTATION_OPERATION_DUPLICATE,
                        "operation " + operation.operation() + " is documented twice", opAt));
            }
            validateSchemaRef(operation.requestSchema(), opAt + ".request_schema", diagnostics);
            validateSchemaRef(operation.responseSchema(), opAt + ".response_schema", diagnostics);
        }
    }

    /** Helper: one payload schema reference must be present, well formed and a JSON Schema. */
    private void validateSchemaRef(ArtifactRef ref, String at, List<Diagnostic> diagnostics) {
        if (ref == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_DOCUMENTATION_INCOMPLETE, "schema reference is required", at));
            return;
        }
        ref.shapeViolation().ifPresent(problem -> diagnostics.add(Diagnostic.error(ErrorCode.ARTIFACT_REF_INVALID, problem, at)));
        if (ref.mediaType() != null && ref.mediaType() != ArtifactMediaType.SCHEMA_JSON) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_DOCUMENTATION_MEDIA_TYPE, "payload schemas must be "
                    + ArtifactMediaType.SCHEMA_JSON.wireValue() + ", not " + ref.mediaType().wireValue(), at));
        }
    }

    /**
     * Helper: validates the claim-level API documents (DCP HLD-C2 A.13): kebab-case, unique ids, a description, a
     * non-empty list of kebab-case surfaces, and a well-formed OpenAPI reference. The documents' own rules are checked by
     * ClaimArtifactValidator.
     */
    private void validateApiDocuments(List<ApiDocument> documents, List<Diagnostic> diagnostics) {
        HashSet<String> ids = new HashSet<>();
        for (ApiDocument document : documents) {
            if (document == null) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_API_DOCUMENT_INVALID, "api document is required", "api_documents"));
                continue;
            }
            String at = "api_documents[" + document.apiId() + "]";
            if (document.apiId() == null || !KEBAB.matcher(document.apiId()).matches()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_API_DOCUMENT_INVALID, "api_id must be kebab-case", at + ".api_id"));
            } else if (!ids.add(document.apiId())) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_API_DOCUMENT_INVALID,
                        "api_id " + document.apiId() + " is declared twice", at + ".api_id"));
            }
            if (document.description() == null || document.description().isBlank()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_API_DOCUMENT_INVALID, "description is required", at + ".description"));
            }
            if (document.surfaces().isEmpty()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_API_DOCUMENT_INVALID, "surfaces must not be empty", at + ".surfaces"));
            }
            for (String surface : document.surfaces()) {
                if (surface == null || !KEBAB.matcher(surface).matches()) {
                    diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_API_DOCUMENT_INVALID,
                            "surface must be kebab-case: " + surface, at + ".surfaces"));
                }
            }
            if (document.openapi() == null) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_API_DOCUMENT_INVALID, "openapi reference is required", at + ".openapi"));
                continue;
            }
            document.openapi().shapeViolation()
                    .ifPresent(problem -> diagnostics.add(Diagnostic.error(ErrorCode.ARTIFACT_REF_INVALID, problem, at + ".openapi")));
            if (document.openapi().mediaType() != null && !document.openapi().mediaType().isOpenApi()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_API_DOCUMENT_INVALID,
                        "openapi must use an OpenAPI media type", at + ".openapi"));
            }
        }
    }

    /**
     * Helper: rejects a capability declared both as a need and as an optional dependency (HLD-C v0.2 §3.4), because
     * whether the component may run without it would then be ambiguous. Names are compared by
     * {@link Dependencies#capabilityOf}, so differing versions or qualifiers don't hide the overlap.
     */
    private void validateDependencies(Dependencies dependencies, List<Diagnostic> diagnostics) {
        if (dependencies == null) {
            return;
        }
        HashSet<String> required = new HashSet<>();
        for (String need : dependencies.needs()) {
            String capability = Dependencies.capabilityOf(need);
            if (capability != null) {
                required.add(capability);
            }
        }
        java.util.TreeSet<String> both = new java.util.TreeSet<>();
        for (String optional : dependencies.optional()) {
            String capability = Dependencies.capabilityOf(optional);
            if (capability != null && required.contains(capability)) {
                both.add(capability);
            }
        }
        for (String capability : both) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED,
                    "dependency " + capability + " is listed in both needs and optional; list it in one",
                    "dependencies.optional"));
        }
    }

    /**
     * Helper: validates the first-class DCP fault vocabulary attached to a claim.
     */
    private void validateFaults(Claim claim, List<Diagnostic> diagnostics) {
        if (claim.faults() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "faults section is required", "faults"));
            return;
        }
        HashSet<String> codes = new HashSet<>();
        for (FaultDeclaration fault : claim.faults().emitted()) {
            if (fault == null) {
                diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "fault declaration is required", "faults.emitted"));
                continue;
            }
            if (fault.code().isBlank()) {
                diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "fault code is required", "faults.emitted.code"));
            } else if (!codes.add(fault.code())) {
                diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "fault codes must be unique", "faults.emitted.code"));
            }
            if (fault.category() == null) {
                diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "fault category is required", "faults.emitted.category"));
            }
            if (fault.severity() == null) {
                diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "fault severity is required", "faults.emitted.severity"));
            }
            if (fault.description().isBlank()) {
                diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "fault description is required", "faults.emitted.description"));
            }
            if (fault.affects().emptyAffectedSurface()) {
                diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "fault must affect at least one need, offer, or constraint", "faults.emitted.affects"));
            }
            if (fault.propagation().parentImpact() != ParentImpact.NONE && fault.propagation().propagatesWhen().isBlank()) {
                diagnostics.add(Diagnostic.error(ErrorCode.FAULT_MALFORMED, "fault propagation requires propagates_when when parent impact is not NONE", "faults.emitted.propagation.propagates_when"));
            }
        }
    }
}
