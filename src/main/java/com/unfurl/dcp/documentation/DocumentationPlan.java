package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.unfurl.dcp.claim.ApiDocument;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.documentation.DocumentationProjector.Pin;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.TargetEnvironment;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builder (dynamic half): the immutable result of {@link DocumentationProjector#plan}. It names exactly the service roots
 * to observe ({@link #serviceRoots()}), and {@link #project} turns one observation per root into a
 * {@link CapabilityDocumentation} without I/O, so it can be called after every observation (DCP HLD-C2 G.1, Freshness).
 *
 * <p>For each entry that can still be documented, {@code project} applies two checks against its root's report:
 * <ul>
 *   <li><b>Host check:</b> the report's own identity and claim hash equal the host pinned for the root; otherwise every
 *       entry on the root is {@code HOST_CLAIM_MISMATCH}.</li>
 *   <li><b>Provider check:</b> the report lists the entry's contract, and that registration's provider evidence equals the
 *       entry's own pin; otherwise {@code NOT_REGISTERED} or {@code CLAIM_MISMATCH}. For a hosted entry the host check
 *       uses the host's pin and the provider check the hosted component's, so a correctly admitted component is active.</li>
 * </ul>
 * A root never observed is {@code PROVIDER_UNREACHABLE: not yet observed}; it is never active.
 *
 * <p>Thread-safety: immutable after construction; safe to share across observer threads.
 */
public final class DocumentationPlan {
    /** Detail for a root that has not been observed yet. */
    public static final String NOT_YET_OBSERVED = "not yet observed";

    private final URI documentId;
    private final String generatedBy;
    private final TargetEnvironment environment;
    private final URI rootContract;
    private final List<Planned> entries;
    private final Map<URI, Pin> hostByRoot;
    private final Map<String, Claim> hostClaims;
    private final Set<URI> protectedComponents;
    private final IdentityMode identity;
    private final VisibilityPolicy visibility;
    private final VerifiedArtifacts artifacts;
    private final Map<String, List<ApiOperations.Operation>> apiOperations;

    /** Constructor: called by the projector only. */
    DocumentationPlan(URI documentId, String generatedBy, TargetEnvironment environment, URI rootContract,
                      List<Planned> entries, Map<URI, Pin> hostByRoot, Map<String, Claim> hostClaims,
                      Set<URI> protectedComponents, IdentityMode identity, VisibilityPolicy visibility,
                      VerifiedArtifacts artifacts) {
        this.documentId = documentId;
        this.generatedBy = generatedBy;
        this.environment = environment;
        this.rootContract = rootContract;
        this.entries = List.copyOf(entries);
        this.hostByRoot = Collections.unmodifiableMap(new TreeMap<>(hostByRoot));
        this.hostClaims = Collections.unmodifiableMap(new LinkedHashMap<>(hostClaims));
        this.protectedComponents = Set.copyOf(protectedComponents);
        this.identity = identity;
        this.visibility = visibility;
        this.artifacts = artifacts;
        Map<String, List<ApiOperations.Operation>> operations = new LinkedHashMap<>();
        hostClaims.values().forEach(claim -> claim.apiDocuments().forEach(document -> {
            JsonNode root = ApiOperations.parse(document, artifacts.read(document.openapi()));
            operations.put(document.openapi().sha256(), ApiOperations.of(root));
        }));
        this.apiOperations = Collections.unmodifiableMap(operations);
    }

    /** Exactly the service roots to observe: those of entries that can still be documented. Nothing else may be probed. */
    public Set<URI> serviceRoots() {
        Set<URI> roots = new TreeSet<>();
        entries.stream().filter(entry -> entry.staticReason == null).forEach(entry -> roots.add(entry.root));
        return Collections.unmodifiableSet(roots);
    }

    /** The verified bytes of every artifact the plan uses, for renderers; reading them does no I/O. */
    public DocumentationArtifactStore artifacts() {
        return artifacts;
    }

    /** The expected host of a service root, as {@code <uri>@<version>}, for observers that authorize per host. */
    public Map<URI, URI> hostComponentByRoot() {
        Map<URI, URI> result = new TreeMap<>();
        hostByRoot.forEach((root, pin) -> result.put(root, pin.uri()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Projects observations.
     *
     * @param observations one observation per service root; roots not present count as not yet observed
     * @param generatedAt  the projection time
     */
    public CapabilityDocumentation project(Map<URI, RegistrationObservation> observations, Instant generatedAt) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(generatedAt, "generatedAt");
        List<ReportEntry> report = new ArrayList<>();
        Map<URI, List<DocumentedCapability>> activeByRoot = new TreeMap<>();
        for (Planned entry : entries) {
            Outcome outcome = entry.staticReason != null
                    ? new Outcome(entry.staticReason, entry.detail, null)
                    : observe(entry, observations.get(entry.root));
            report.add(ReportEntry.of(entry.contract.contractId(), entry.binding == null ? null : entry.binding.bindingId(),
                    entry.contract.binding().providerCapability(), entry.contract.parties().provider().claimUri(),
                    entry.surface, outcome.reason, outcome.detail, entry.endpoint, outcome.observedAt));
            if (outcome.reason == ReasonCode.ACTIVE) {
                activeByRoot.computeIfAbsent(entry.root, root -> new ArrayList<>()).add(documented(entry));
            }
        }
        List<DocumentedComponent> components = new ArrayList<>();
        List<ApiOperationExclusion> exclusions = new ArrayList<>();
        activeByRoot.forEach((root, capabilities) -> {
            Pin host = hostByRoot.get(root);
            Claim claim = hostClaims.get(host.hostKey());
            RegistrationReport reported = ((RegistrationObservation.Reported) observations.get(root)).report();
            components.add(new DocumentedComponent(host.uri(), host.version(), claim.identity().name(), host.hash(), root,
                    identity == IdentityMode.IN_CONTRACT && protectedComponents.contains(host.uri()), capabilities,
                    apis(claim, host.uri(), Set.copyOf(reported.servedSurfaces()), exclusions)));
        });
        Set<String> registered = new TreeSet<>();
        observations.forEach((root, observation) -> {
            if (serviceRoots().contains(root) && observation instanceof RegistrationObservation.Reported reported) {
                reported.report().registrations().forEach(registration -> registered.add(registration.capability()));
            }
        });
        return new CapabilityDocumentation(documentId, environment,
                new DocumentationSource(rootContract, entries.stream().map(e -> e.contract.contractId()).toList(),
                        entries.stream().filter(e -> e.binding != null).map(e -> e.binding.bindingId()).toList(),
                        List.copyOf(registered)),
                identity, surfaces(components, report), components, new DocumentationReport(report, exclusions),
                new DocumentationLifecycle(generatedBy, generatedAt));
    }

    /** The host check, then the provider check, for one entry that can still be documented. */
    private Outcome observe(Planned entry, RegistrationObservation observation) {
        if (observation == null) {
            return new Outcome(ReasonCode.PROVIDER_UNREACHABLE, NOT_YET_OBSERVED, null);
        }
        if (observation instanceof RegistrationObservation.Unreachable unreachable) {
            return new Outcome(ReasonCode.PROVIDER_UNREACHABLE, unreachable.detail(), unreachable.observedAt());
        }
        if (observation instanceof RegistrationObservation.Unauthorized unauthorized) {
            return new Outcome(ReasonCode.REGISTRATION_UNAUTHORIZED, "the host answered " + unauthorized.status(),
                    unauthorized.observedAt());
        }
        RegistrationObservation.Reported reported = (RegistrationObservation.Reported) observation;
        RegistrationReport report = reported.report();
        Pin host = hostByRoot.get(entry.root);
        if (!report.componentUri().equals(host.uri())) {
            return new Outcome(ReasonCode.HOST_CLAIM_MISMATCH, "the root reports component " + report.componentUri()
                    + " but " + host.uri() + " is pinned", reported.observedAt());
        }
        if (!report.componentVersion().equals(host.version())) {
            return new Outcome(ReasonCode.HOST_CLAIM_MISMATCH, "the root reports version " + report.componentVersion()
                    + " but " + host.version() + " is pinned", reported.observedAt());
        }
        if (!report.claimHash().equals(host.hash())) {
            return new Outcome(ReasonCode.HOST_CLAIM_MISMATCH, "the host's claim hash differs from the pinned one",
                    reported.observedAt());
        }
        return report.registration(entry.contract.contractId(), entry.contract.contractVersion())
                .map(registration -> {
                    if (!registration.providerClaimUri().equals(entry.pin.uri())
                            || !registration.providerClaimVersion().equals(entry.pin.version())
                            || !registration.providerClaimHash().equals(entry.pin.hash())) {
                        return new Outcome(ReasonCode.CLAIM_MISMATCH, "the registered provider " + registration.providerClaimUri()
                                + "@" + registration.providerClaimVersion() + " does not match the pinned " + entry.pin,
                                reported.observedAt());
                    }
                    return new Outcome(ReasonCode.ACTIVE, null, reported.observedAt());
                })
                .orElseGet(() -> new Outcome(ReasonCode.NOT_REGISTERED, "the host does not list this contract",
                        reported.observedAt()));
    }

    /** The documented form of an active entry. */
    private DocumentedCapability documented(Planned entry) {
        String capability = entry.contract.binding().providerCapability();
        boolean hosted = entry.surface == EntrySurface.HOSTED_IN_PROCESS;
        return new DocumentedCapability(capability, entry.offer.version(), entry.offer.description(),
                entry.contract.contractId(), entry.binding.bindingId(), entry.surface, entry.visibility,
                hosted ? null : "/dcp/" + capability, hosted ? hostByRoot.get(entry.root).uri() : null,
                entry.pin.uri(), entry.pin.version(),
                entry.offer.documentation().operations().stream().map(DocumentedOperation::of).toList(),
                Entries.faultsOf(entry.claim, capability));
    }

    /** A host's projected API documents; excluded operations are added to {@code exclusions}. */
    private List<DocumentedApi> apis(Claim claim, URI component, Set<String> served, List<ApiOperationExclusion> exclusions) {
        List<DocumentedApi> documents = new ArrayList<>();
        for (ApiDocument document : claim.apiDocuments()) {
            Visibility visibility = this.visibility.ofApiDocument(document.apiId());
            List<ApiOperations.Operation> operations = apiOperations.getOrDefault(document.openapi().sha256(), List.of());
            if (visibility == Visibility.PRIVATE) {
                operations.forEach(op -> exclusions.add(new ApiOperationExclusion(component, document.apiId(), op.label(),
                        ReasonCode.POLICY_PRIVATE)));
                continue;
            }
            List<String> servedHere = document.surfaces().stream().filter(served::contains).toList();
            operations.stream().filter(op -> !served.contains(op.surface())).forEach(op -> exclusions.add(
                    new ApiOperationExclusion(component, document.apiId(), op.label(), ReasonCode.SURFACE_NOT_SERVED)));
            documents.add(new DocumentedApi(document.apiId(), document.openapi(), visibility, servedHere));
        }
        return documents;
    }

    /** The output surfaces: per kind and visibility, what is included. */
    private static List<DocumentationSurface> surfaces(List<DocumentedComponent> components, List<ReportEntry> report) {
        Map<Visibility, Set<String>> openapi = new TreeMap<>();
        for (DocumentedComponent component : components) {
            component.capabilities().forEach(c -> openapi.computeIfAbsent(c.visibility(), v -> new LinkedHashSet<>()).add(c.capability()));
            component.apiDocuments().forEach(d -> openapi.computeIfAbsent(d.visibility(), v -> new LinkedHashSet<>()).add(d.apiId()));
        }
        List<DocumentationSurface> surfaces = new ArrayList<>();
        openapi.forEach((visibility, includes) -> surfaces.add(new DocumentationSurface(SurfaceKind.OPENAPI, visibility, null,
                List.copyOf(includes))));
        Set<String> summary = new TreeSet<>();
        report.stream().filter(e -> e.reason() != ReasonCode.POLICY_PRIVATE).forEach(e -> summary.add(e.capability()));
        if (!summary.isEmpty()) {
            surfaces.add(new DocumentationSurface(SurfaceKind.HUMAN_SUMMARY, Visibility.INTERNAL, null, List.copyOf(summary)));
        }
        return surfaces;
    }

    /** One entry's outcome. */
    private record Outcome(ReasonCode reason, String detail, Instant observedAt) { }

    /** Mutable planning state of one entry; frozen once the plan is constructed. */
    static final class Planned {
        final CompositionContract contract;
        final RuntimeBinding binding;
        final EntrySurface surface;
        final Visibility visibility;
        final Claim claim;
        final Offer offer;
        final Pin pin;
        URI endpoint;
        URI root;
        ReasonCode staticReason;
        String detail;

        Planned(CompositionContract contract, RuntimeBinding binding, EntrySurface surface, Visibility visibility,
                Claim claim, Offer offer, Pin pin) {
            this.contract = contract;
            this.binding = binding;
            this.surface = surface;
            this.visibility = visibility;
            this.claim = claim;
            this.offer = offer;
            this.pin = pin;
        }
    }
}
