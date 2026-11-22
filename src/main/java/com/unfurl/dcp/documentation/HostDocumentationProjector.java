package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.documentation.DocumentationGap.DocumentationGapException;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builder: a host's documentation of itself and of the components it runs in process (DCP HLD-C2 G.2 host view), from
 * the same model and renderers as the full projection.
 *
 * <p>Its acceptance proof is the host's own registrar: a host registers only contracts its broker accepted against
 * enabled bindings. Only <b>currently registered</b> capabilities are documented, and matching runs one way:
 * <ul>
 *   <li>every hosted registration must have exactly one admitted component whose claim identity and canonical hash equal
 *       both the admission pin and the registration's provider evidence; otherwise {@code HOST_VIEW_INPUT_MISMATCH};</li>
 *   <li>an admitted component without a current registration is retained admission evidence after revocation: it is
 *       ignored for rendering, validation and gaps, and never an error.</li>
 * </ul>
 * Hosted capabilities are documented from the hosted component's own claim and artifacts, never from the registration,
 * which carries only identity and hashes.
 */
public final class HostDocumentationProjector {

    /**
     * Projects a host view.
     *
     * @param generatedAt the projection time
     * @return the projection and the verified bytes its renderers need
     * @throws DocumentationException {@code HOST_VIEW_INPUT_MISMATCH}, {@code ARTIFACT_INVALID},
     *                                {@code CLAIM_OFFER_MISSING} or {@code DOCUMENTATION_GAP}
     */
    public ProjectedDocumentation project(HostDocumentationInputs inputs, Instant generatedAt) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(generatedAt, "generatedAt");
        RegistrationReport local = inputs.local();
        Claim own = inputs.ownClaim();
        if (!local.componentUri().equals(own.identity().uri()) || !local.componentVersion().equals(own.identity().version())
                || !local.claimHash().equals(ClaimHash.canonical(own))) {
            throw new DocumentationException(DocumentationException.HOST_VIEW_INPUT_MISMATCH,
                    "the local registration report does not describe the host's own claim");
        }
        Map<ClaimKey, HostedComponentDocumentation> admitted = new HashMap<>();
        for (HostedComponentDocumentation component : inputs.hosted()) {
            if (admitted.putIfAbsent(ClaimKey.of(component.claim()), component) != null) {
                throw new DocumentationException(DocumentationException.HOST_VIEW_INPUT_MISMATCH,
                        "component " + ClaimKey.of(component.claim()) + " is admitted twice");
            }
        }

        // One-way matching: each hosted registration needs its admitted, hash-consistent evidence.
        Map<ClaimKey, HostedComponentDocumentation> rendered = new TreeMap<>(java.util.Comparator.comparing(ClaimKey::toString));
        for (Registration registration : local.registrations()) {
            if (registration.surface() != EntrySurface.HOSTED_IN_PROCESS) continue;
            ClaimKey key = new ClaimKey(registration.providerClaimUri(), registration.providerClaimVersion());
            HostedComponentDocumentation evidence = admitted.get(key);
            if (evidence == null) {
                throw new DocumentationException(DocumentationException.HOST_VIEW_INPUT_MISMATCH,
                        "hosted registration " + registration.contractId() + " has no admission evidence for " + key);
            }
            String hash = ClaimHash.canonical(evidence.claim());
            if (!hash.equals(evidence.pin().claimHash()) || !hash.equals(registration.providerClaimHash())) {
                throw new DocumentationException(DocumentationException.HOST_VIEW_INPUT_MISMATCH,
                        "the claim hash of " + key + " disagrees between its claim, its admission pin and its registration");
            }
            rendered.put(key, evidence);
        }

        // Validate only what is rendered: the host's own claim and the matched hosted claims.
        validateArtifacts(own, inputs.ownArtifacts());
        rendered.values().forEach(component -> validateArtifacts(component.claim(), component.artifacts()));
        List<VerifiedArtifacts> captured = new ArrayList<>();
        captured.add(VerifiedArtifacts.capture(List.of(own), inputs.ownArtifacts()));
        rendered.values().forEach(component -> captured.add(VerifiedArtifacts.capture(List.of(component.claim()), component.artifacts())));

        List<DocumentationGap> gaps = new ArrayList<>();
        List<ReportEntry> report = new ArrayList<>();
        List<DocumentedCapability> capabilities = new ArrayList<>();
        for (Registration registration : local.registrations()) {
            boolean hosted = registration.surface() == EntrySurface.HOSTED_IN_PROCESS;
            Claim claim = hosted ? rendered.get(new ClaimKey(registration.providerClaimUri(), registration.providerClaimVersion())).claim() : own;
            Offer offer = Entries.offer(claim, registration.capability()).orElseThrow(() -> new DocumentationException(
                    DocumentationException.CLAIM_OFFER_MISSING, "the claim " + ClaimKey.of(claim) + " offers no "
                    + registration.capability() + " but the host registered it"));
            Visibility visibility = inputs.policy().ofCapability(registration.capability());
            if (visibility == Visibility.PRIVATE) {
                report.add(ReportEntry.of(registration.contractId(), null, registration.capability(), registration.providerClaimUri(),
                        registration.surface(), ReasonCode.POLICY_PRIVATE, null, null, local.observedAt()));
                continue;
            }
            if (!Entries.documented(offer)) {
                gaps.add(new DocumentationGap(registration.contractId(), registration.capability(), registration.providerClaimUri()));
                continue;
            }
            report.add(ReportEntry.of(registration.contractId(), null, registration.capability(), registration.providerClaimUri(),
                    registration.surface(), ReasonCode.ACTIVE, null, hosted ? null : endpoint(inputs.serviceRoot(), registration),
                    local.observedAt()));
            capabilities.add(new DocumentedCapability(registration.capability(), offer.version(), offer.description(),
                    registration.contractId(), null, registration.surface(), visibility, hosted ? null : registration.path(),
                    hosted ? own.identity().uri() : null, registration.providerClaimUri(), registration.providerClaimVersion(),
                    offer.documentation().operations().stream().map(DocumentedOperation::of).toList(),
                    Entries.faultsOf(claim, registration.capability())));
        }
        if (!gaps.isEmpty()) {
            throw new DocumentationGapException(gaps);
        }

        VerifiedArtifacts artifacts = VerifiedArtifacts.union(captured);
        List<ApiOperationExclusion> exclusions = new ArrayList<>();
        List<DocumentedApi> apis = new ArrayList<>();
        Set<String> served = Set.copyOf(local.servedSurfaces());
        own.apiDocuments().forEach(document -> {
            List<ApiOperations.Operation> operations = ApiOperations.of(ApiOperations.parse(document, artifacts.read(document.openapi())));
            Visibility visibility = inputs.policy().ofApiDocument(document.apiId());
            if (visibility == Visibility.PRIVATE) {
                operations.forEach(op -> exclusions.add(new ApiOperationExclusion(own.identity().uri(), document.apiId(),
                        op.label(), ReasonCode.POLICY_PRIVATE)));
                return;
            }
            operations.stream().filter(op -> !served.contains(op.surface())).forEach(op -> exclusions.add(
                    new ApiOperationExclusion(own.identity().uri(), document.apiId(), op.label(), ReasonCode.SURFACE_NOT_SERVED)));
            apis.add(new DocumentedApi(document.apiId(), document.openapi(), visibility,
                    document.surfaces().stream().filter(served::contains).toList()));
        });

        List<DocumentedComponent> components = capabilities.isEmpty() && apis.isEmpty() ? List.of() : List.of(
                new DocumentedComponent(own.identity().uri(), own.identity().version(), own.identity().name(), local.claimHash(),
                        inputs.serviceRoot(), inputs.identity() == IdentityMode.IN_CONTRACT
                        && inputs.protectedComponents().contains(own.identity().uri()), capabilities, apis));
        Set<String> includes = new LinkedHashSet<>();
        capabilities.forEach(c -> includes.add(c.capability()));
        apis.forEach(a -> includes.add(a.apiId()));
        List<DocumentationSurface> surfaces = includes.isEmpty() ? List.of()
                : List.of(new DocumentationSurface(SurfaceKind.OPENAPI, Visibility.INTERNAL, null, List.copyOf(includes)));
        CapabilityDocumentation documentation = new CapabilityDocumentation(inputs.documentId(), inputs.environment(),
                new DocumentationSource(null, local.registrations().stream().map(Registration::contractId).toList(), List.of(),
                        local.registrations().stream().map(Registration::capability).toList()),
                inputs.identity(), surfaces, components, new DocumentationReport(report, exclusions),
                new DocumentationLifecycle(inputs.generatedBy(), generatedAt));
        return new ProjectedDocumentation(documentation, artifacts);
    }

    /** The host's endpoint for one of its HTTP registrations. */
    private static URI endpoint(URI root, Registration registration) {
        return URI.create(root.toString().replaceAll("/+$", "") + registration.path());
    }

    /** Refuses a claim whose shipped artifacts are invalid. */
    private static void validateArtifacts(Claim claim, DocumentationArtifactStore store) {
        SchemaValidationReport report = new ClaimArtifactValidator().validate(claim, store);
        if (!report.valid()) {
            throw new DocumentationException(DocumentationException.ARTIFACT_INVALID,
                    "the shipped artifacts of " + ClaimKey.of(claim) + " are invalid: " + report.diagnostics());
        }
    }
}
