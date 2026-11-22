package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.Party;
import com.unfurl.dcp.documentation.DocumentationGap.DocumentationGapException;
import com.unfurl.dcp.documentation.ServiceRootResolver.ResolvedEndpoint;
import com.unfurl.dcp.host.ProviderArtifactPin;
import com.unfurl.dcp.host.VerifiedContract;
import com.unfurl.dcp.runtimebinding.IdentityBinding;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.TargetEnvironment;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builder (static half): turns a verified composition and its static inputs into a {@link DocumentationPlan} (DCP
 * HLD-C2 G.1). Everything that can be decided without the network is decided here, once:
 * <ol>
 *   <li>each child's provider claim is found and checked against the child's pinned claim hash;</li>
 *   <li>every claim's shipped artifacts are verified ({@link ClaimArtifactValidator}) and captured in memory;</li>
 *   <li>the gap gate runs ({@link CapabilityDocumentationValidator#gaps});</li>
 *   <li>each entry gets its surface, visibility and endpoint, and either a static reason or a service root to observe;</li>
 *   <li>every service root gets exactly one expected host, and identity is read from the verified set.</li>
 * </ol>
 * Any inconsistency refuses the whole projection with a {@link DocumentationException}; per-entry outcomes are report
 * reasons. The resulting plan names exactly the roots to observe, and projects observations without I/O.
 */
public final class DocumentationProjector {
    private final ServiceRootResolver resolver = new ServiceRootResolver();

    /**
     * Plans a projection.
     *
     * @throws DocumentationException with codes {@code CLAIM_MISSING}, {@code CLAIM_PIN_MISSING},
     *                                {@code CLAIM_PIN_MISMATCH}, {@code CLAIM_OFFER_MISSING}, {@code ARTIFACT_INVALID},
     *                                {@code SERVICE_ROOT_CONFLICT}, {@code BINDING_DUPLICATE}, {@code TARGET_ENVIRONMENT_INCONSISTENT},
     *                                {@code IDENTITY_BINDING_INCOMPLETE} or {@code DOCUMENTATION_GAP}
     */
    public DocumentationPlan plan(DocumentationInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        List<VerifiedContract> children = inputs.composition().children().all().stream()
                .sorted(Comparator.comparing(child -> child.contract().contractId().toString())).toList();
        Map<URI, RuntimeBinding> bindings = bindingsByContract(inputs.runtimeBindings());
        TargetEnvironment environment = environment(inputs.runtimeBindings());

        // 1. Claims, checked against the pins.
        Map<URI, Claim> claimByContract = new HashMap<>();
        Map<URI, Pin> pinByContract = new HashMap<>();
        Map<ClaimKey, Claim> used = new TreeMap<>(Comparator.comparing(ClaimKey::toString));
        for (VerifiedContract child : children) {
            CompositionContract contract = child.contract();
            Party provider = contract.parties().provider();
            ClaimKey key = new ClaimKey(provider.claimUri(), provider.claimVersion());
            Claim claim = inputs.providerClaims().get(key);
            if (claim == null) {
                throw new DocumentationException(DocumentationException.CLAIM_MISSING,
                        "the bundle carries no claim " + key + " for contract " + contract.contractId());
            }
            ProviderArtifactPin pin = child.pin().orElseThrow(() -> new DocumentationException(DocumentationException.CLAIM_PIN_MISSING,
                    "contract " + contract.contractId() + " pins no provider claim hash"));
            if (!ClaimHash.canonical(claim).equals(pin.claimHash())) {
                throw new DocumentationException(DocumentationException.CLAIM_PIN_MISMATCH,
                        "the bundled claim " + key + " does not match the hash contract " + contract.contractId() + " pins");
            }
            CapabilityDocumentationValidator.requireOffer(claim, contract);
            claimByContract.put(contract.contractId(), claim);
            pinByContract.put(contract.contractId(), new Pin(provider.claimUri(), provider.claimVersion(), pin.claimHash()));
            used.put(key, claim);
        }

        // 2. Shipped artifacts, verified and captured.
        ClaimArtifactValidator artifactValidator = new ClaimArtifactValidator();
        for (Claim claim : used.values()) {
            SchemaValidationReport report = artifactValidator.validate(claim, inputs.artifacts());
            if (!report.valid()) {
                throw new DocumentationException(DocumentationException.ARTIFACT_INVALID, "the shipped artifacts of "
                        + ClaimKey.of(claim) + " are invalid: " + report.diagnostics());
            }
        }
        VerifiedArtifacts artifacts = VerifiedArtifacts.capture(used.values(), inputs.artifacts());

        // 3. The gap gate.
        List<DocumentationGap> gaps = new CapabilityDocumentationValidator()
                .gaps(inputs.composition(), inputs.providerClaims(), inputs.runtimeBindings(), inputs.visibility());
        if (!gaps.isEmpty()) {
            throw new DocumentationGapException(gaps);
        }

        // 4. Entries: surface, visibility, endpoint.
        List<DocumentationPlan.Planned> planned = new ArrayList<>();
        Map<URI, Pin> hostByRoot = new TreeMap<>(Comparator.comparing(URI::toString));
        Map<String, URI> rootByHost = new TreeMap<>();
        for (VerifiedContract child : children) {
            CompositionContract contract = child.contract();
            RuntimeBinding binding = bindings.get(contract.contractId());
            EntrySurface surface = Entries.surfaceOf(contract, binding);
            String capability = contract.binding().providerCapability();
            Claim claim = claimByContract.get(contract.contractId());
            Offer offer = CapabilityDocumentationValidator.requireOffer(claim, contract);
            Pin pin = pinByContract.get(contract.contractId());
            DocumentationPlan.Planned entry = new DocumentationPlan.Planned(contract, binding, surface,
                    inputs.visibility().ofCapability(capability), claim, offer, pin);
            if (surface == EntrySurface.HTTP && binding != null) {
                ResolvedEndpoint endpoint = resolver.resolve(binding, contract, inputs.configResolver());
                if (endpoint instanceof ResolvedEndpoint.ServiceRoot root) {
                    entry.endpoint = root.endpoint();
                    entry.root = root.root();
                    Pin previous = hostByRoot.putIfAbsent(root.root(), pin);
                    if (previous != null && !previous.equals(pin)) {
                        throw new DocumentationException(DocumentationException.SERVICE_ROOT_CONFLICT, "service root "
                                + root.root() + " is pinned to both " + previous + " and " + pin);
                    }
                    URI otherRoot = rootByHost.putIfAbsent(pin.hostKey(), root.root());
                    if (otherRoot != null && !otherRoot.equals(root.root())) {
                        throw new DocumentationException(DocumentationException.SERVICE_ROOT_CONFLICT, "component "
                                + pin.hostKey() + " is bound at two service roots, " + otherRoot + " and " + root.root());
                    }
                } else if (endpoint instanceof ResolvedEndpoint.Mismatch mismatch) {
                    entry.endpoint = mismatch.endpoint();
                    entry.staticReason = ReasonCode.ENDPOINT_PATH_MISMATCH;
                    entry.detail = "the endpoint is not <service_root>/dcp/" + capability;
                } else if (endpoint instanceof ResolvedEndpoint.Missing missing) {
                    entry.staticReason = ReasonCode.BINDING_ENDPOINT_MISSING;
                    entry.detail = missing.detail();
                }
            }
            planned.add(entry);
        }

        // 5. Static reasons, in a fixed precedence; hosted entries find their host's root.
        for (DocumentationPlan.Planned entry : planned) {
            ReasonCode endpointReason = entry.staticReason;
            if (entry.binding == null) {
                entry.staticReason = ReasonCode.BINDING_MISSING;
                entry.detail = "no runtime binding names this contract";
            } else if (entry.binding.runtimePolicy() == null || !entry.binding.runtimePolicy().enabled()) {
                entry.staticReason = ReasonCode.BINDING_DISABLED;
                entry.detail = null;
            } else if (entry.surface == EntrySurface.EXTERNAL) {
                entry.staticReason = ReasonCode.EXTERNAL_SURFACE;
                entry.detail = null;
            } else if (entry.visibility == Visibility.PRIVATE) {
                entry.staticReason = ReasonCode.POLICY_PRIVATE;
                entry.detail = null;
            } else if (entry.surface == EntrySurface.HOSTED_IN_PROCESS) {
                Party host = entry.contract.parties().consumer();
                URI root = host == null ? null : rootByHost.get(host.claimUri() + "@" + host.claimVersion());
                if (root == null) {
                    entry.staticReason = ReasonCode.HOST_UNKNOWN;
                    entry.detail = "the host " + (host == null ? "?" : host.claimUri() + "@" + host.claimVersion())
                            + " has no service root in this composition";
                } else {
                    entry.root = root;
                    entry.staticReason = null;
                }
            } else {
                entry.staticReason = endpointReason;
            }
        }

        // 6. Identity, from the verified set only.
        Optional<IdentityBinding> identity;
        try {
            identity = IdentityBinding.from(children.stream().map(VerifiedContract::contract).toList(), bindings);
        } catch (IdentityBinding.IdentityBindingException incomplete) {
            throw new DocumentationException(DocumentationException.IDENTITY_BINDING_INCOMPLETE, incomplete.getMessage(), incomplete);
        }

        Map<String, Claim> hostClaims = new LinkedHashMap<>();
        hostByRoot.values().forEach(pin -> hostClaims.put(pin.hostKey(), used.get(new ClaimKey(pin.uri(), pin.version()))));
        return new DocumentationPlan(inputs.documentId(), inputs.generatedBy(), environment,
                inputs.composition().root().contract().contractId(), planned, hostByRoot, hostClaims,
                identity.map(IdentityBinding::protectedComponents).orElse(Set.of()),
                identity.isPresent() ? IdentityMode.IN_CONTRACT : IdentityMode.ABSENT,
                inputs.visibility(), artifacts);
    }

    /** One binding per contract. */
    private static Map<URI, RuntimeBinding> bindingsByContract(List<RuntimeBinding> bindings) {
        Map<URI, RuntimeBinding> byContract = new HashMap<>();
        for (RuntimeBinding binding : bindings) {
            if (byContract.putIfAbsent(binding.contractId(), binding) != null) {
                throw new DocumentationException(DocumentationException.BINDING_DUPLICATE,
                        "contract " + binding.contractId() + " has more than one runtime binding");
            }
        }
        return byContract;
    }

    /** The one target environment all bindings share. */
    private static TargetEnvironment environment(List<RuntimeBinding> bindings) {
        List<TargetEnvironment> environments = bindings.stream().map(RuntimeBinding::targetEnvironment)
                .filter(Objects::nonNull).distinct().toList();
        if (environments.size() != 1) {
            throw new DocumentationException(DocumentationException.TARGET_ENVIRONMENT_INCONSISTENT,
                    "a projection covers exactly one target environment, but the bindings name " + environments.size());
        }
        return environments.get(0);
    }

    /** Value Object: a pinned provider identity, which for an HTTP root is also its expected host. */
    record Pin(URI uri, String version, String hash) {
        /** The {@code <uri>@<version>} key a host is matched by. */
        String hostKey() {
            return uri + "@" + version;
        }

        @Override
        public String toString() {
            return hostKey() + " (" + hash + ")";
        }
    }
}
