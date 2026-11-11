package com.unfurl.dcp.host;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.PackagedClaim;
import com.unfurl.dcp.componentconfig.ComponentConfigurationSchema;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.TransportKind;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Repository of the pinned component jars, in two stages so that no component code runs before every check that needs
 * none has passed (LLD, <em>In-Process Component Host</em>; HLD-C §6.1 rule 2).
 *
 * <p><b>One snapshot.</b> Each delivered file is read into memory exactly once. Those bytes are hashed against the
 * pins, parsed for the stage-1 rules and later used to define classes. No file is opened again after {@link #inspect}
 * returns, so the bytes that were verified are the bytes that are inspected and loaded, whatever happens on disk.
 *
 * <p><b>Stage 1, {@link #inspect}: data only.</b> It hashes the delivered jars, takes pins only from verified contracts
 * whose signed transport is in-process, and for each component (one provider identity) requires every pinned artifact
 * to be present with its SHA-256. It then reads the archive entries as data to enforce the artifact rules: no manifest
 * {@code Class-Path}, no class or resource under a shared API prefix, no class in two artifacts, no versioned copy of
 * the packaged metadata, and exactly one activation declaration whose class is in the declaring artifact. It keeps the
 * packaged claim and the packaged configuration bytes for the host's own checks. Jars no hosted contract pins are
 * ignored and reported; their bytes are dropped as soon as they are hashed.
 *
 * <p><b>Stage 2, {@link #activate}: code.</b> Only now is a component's {@link PinnedArtifactClassLoader} built. The
 * declared activation class is loaded without initialization and must come from that loader and the declaring jar;
 * every class in the component's effective class sets is then loaded the same way, so a missing superclass or
 * interface fails here rather than mid-call; only then is the activation constructed.
 *
 * <p>All failures are {@link ComponentHostException}s naming the artifact or contract. {@link #close()} closes every
 * class loader.
 */
public final class PinnedJarArtifacts implements AutoCloseable {
    /** Base path of the single-line declaration naming a component's {@link ComponentActivation} class. */
    public static final String ACTIVATION_DECLARATION = "META-INF/services/com.unfurl.dcp.host.ComponentActivation";
    /** Size bound for each packaged metadata entry read as data. */
    static final int MAX_METADATA_BYTES = 1 << 20;

    /** The bytes of one delivered file as read once, with its name for diagnostics. */
    private record Snapshot(String fileName, byte[] bytes) { }

    /** One inspected component: its artifacts and packaged metadata, plus its stage-2 loader once activated. */
    private static final class Component {
        private final List<PinnedArtifact> artifacts;
        private final Claim claim;
        private final byte[] configuration;
        private final String activationClass;
        private final PinnedArtifact activationArtifact;
        private PinnedArtifactClassLoader loader;
        private ComponentActivation activation;

        private Component(List<PinnedArtifact> artifacts, Claim claim, byte[] configuration,
                          String activationClass, PinnedArtifact activationArtifact) {
            this.artifacts = artifacts;
            this.claim = claim;
            this.configuration = configuration;
            this.activationClass = activationClass;
            this.activationArtifact = activationArtifact;
        }
    }

    private final SharedApi shared;
    private final Map<ProviderArtifactPin, Component> components;
    private final List<String> ignored;
    private boolean closed;

    private PinnedJarArtifacts(SharedApi shared, Map<ProviderArtifactPin, Component> components, List<String> ignored) {
        this.shared = shared;
        this.components = components;
        this.ignored = ignored;
    }

    /**
     * Stage 1: inspects the delivered jars against the verified contracts' pins, without running any component code.
     *
     * @param jars      the delivered jar files; extras are ignored and reported
     * @param contracts the verified contract set; pins are read from it alone
     * @param shared    the shared API boundary
     * @throws ComponentHostException on the first broken rule, in deterministic order (components by provider identity,
     *                                artifacts in pin order)
     */
    public static PinnedJarArtifacts inspect(Collection<Path> jars, VerifiedContracts contracts, SharedApi shared) {
        Objects.requireNonNull(jars, "jars");
        Objects.requireNonNull(contracts, "contracts");
        Objects.requireNonNull(shared, "shared");
        Map<String, ProviderArtifactPin> hostedPins = hostedPins(contracts);
        TreeSet<String> pinned = new TreeSet<>();
        hostedPins.values().forEach(pin -> pin.all().forEach(artifact -> pinned.add(artifact.sha256())));

        // The single read of each delivered file: hash it, and keep the bytes only when some hosted contract pins them.
        Map<String, Snapshot> bySha = new TreeMap<>();
        List<String> ignored = new ArrayList<>();
        for (Path jar : jars.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            String fileName = String.valueOf(jar.getFileName());
            byte[] bytes = read(jar, fileName);
            String sha = sha256(bytes);
            if (pinned.contains(sha)) bySha.putIfAbsent(sha, new Snapshot(fileName, bytes));
            else ignored.add(fileName);
        }

        Map<ProviderArtifactPin, Component> components = new LinkedHashMap<>();
        int feature = Runtime.version().feature();
        for (Map.Entry<String, ProviderArtifactPin> hosted : hostedPins.entrySet()) {
            ProviderArtifactPin pin = hosted.getValue();
            if (components.containsKey(pin)) continue;
            components.put(pin, component(hosted.getKey(), pin, bySha, shared, feature));
        }
        return new PinnedJarArtifacts(shared, components, ignored.stream().sorted().toList());
    }

    /** Returns the claim packaged in the pin's claim-bearing artifact; empty when the pin was not inspected or has none. */
    public Optional<Claim> packagedClaim(ProviderArtifactPin pin) {
        return Optional.ofNullable(components.get(pin)).map(component -> component.claim);
    }

    /**
     * Returns the bytes of {@code META-INF/unfurl-config.yaml} from the pin's claim-bearing artifact, for the host to
     * parse with {@link ComponentConfigurationSchema#parse}; empty when the component packages no configuration.
     */
    public Optional<byte[]> packagedConfiguration(ProviderArtifactPin pin) {
        return Optional.ofNullable(components.get(pin)).map(component -> component.configuration).map(byte[]::clone);
    }

    /** Returns the file names of delivered jars that no hosted contract pins, sorted; they are never loaded. */
    public List<String> ignoredArtifacts() {
        return ignored;
    }

    /**
     * Stage 2: builds the component's class loader and constructs its activation, once per component.
     *
     * @param pin a pin of an inspected component
     * @return the component's activation; the same instance on every call
     * @throws ComponentHostException when the pin was not inspected, the activation does not originate from its
     *                                declaring artifact, a pinned class cannot be linked, or construction fails
     */
    public synchronized ComponentActivation activate(ProviderArtifactPin pin) {
        if (closed) throw new IllegalStateException("pinned artifacts are closed");
        Component component = components.get(pin);
        String subject = pin.claimArtifact().coordinates();
        if (component == null) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_ARTIFACT_MISSING, subject,
                    "no inspected component has this pin");
        }
        if (component.activation != null) return component.activation;

        // Built from the snapshots stage 1 hashed and inspected; no file is opened here or later.
        PinnedArtifactClassLoader loader = new PinnedArtifactClassLoader("dcp-component:" + subject, component.artifacts, shared);
        try {
            ComponentActivation activation = construct(component, loader, subject);
            component.loader = loader;
            component.activation = activation;
            return activation;
        } catch (RuntimeException | Error failure) {
            loader.close();
            throw failure;
        }
    }

    /** Closes every component class loader; idempotent. Classes already defined remain usable until unreferenced. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Component component : components.values()) {
            if (component.loader != null) component.loader.close();
        }
    }

    /** Stage-2 checks and construction for one component; see the class comment. */
    private static ComponentActivation construct(Component component, PinnedArtifactClassLoader loader, String subject) {
        Class<?> declared = link(component.activationClass, loader, subject);
        if (!loader.definedFrom(declared, component.activationArtifact)) {
            throw new ComponentHostException(HostFailureCode.ACTIVATION_ORIGIN_MISMATCH, subject,
                    "activation class " + component.activationClass + " did not load from its declaring artifact");
        }
        // Eager linking: a superclass or interface missing from the pinned set fails startup, not a later call.
        for (PinnedArtifact artifact : component.artifacts) {
            for (String name : artifact.classNames()) link(name, loader, artifact.pin().coordinates());
        }
        if (!ComponentActivation.class.isAssignableFrom(declared) || !Modifier.isPublic(declared.getModifiers())) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_ACTIVATION_FAILED, subject,
                    component.activationClass + " is not a public ComponentActivation");
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        // Component code runs with its own loader as context loader, so libraries that use it stay in the pinned set.
        thread.setContextClassLoader(loader);
        try {
            Constructor<?> constructor = declared.getConstructor();
            return (ComponentActivation) constructor.newInstance();
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException notConstructible) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_ACTIVATION_FAILED, subject,
                    component.activationClass + " has no public no-argument constructor", notConstructible);
        } catch (InvocationTargetException threw) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_ACTIVATION_FAILED, subject,
                    "constructing " + component.activationClass + " threw " + threw.getCause().getClass().getName(),
                    threw.getCause());
        } catch (ExceptionInInitializerError initializer) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_ACTIVATION_FAILED, subject,
                    "initializing " + component.activationClass + " threw", initializer);
        } catch (LinkageError unresolved) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_DEPENDENCY_UNRESOLVED, subject,
                    "constructing " + component.activationClass + " needs a type outside the pinned set: " + unresolved,
                    unresolved);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Loads one pinned class without initializing it; a type it needs that is not loadable is a dependency failure. */
    private static Class<?> link(String name, PinnedArtifactClassLoader loader, String subject) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError unresolved) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_DEPENDENCY_UNRESOLVED, subject,
                    "class " + name + " cannot be linked from the pinned set and shared API: " + unresolved, unresolved);
        }
    }

    /**
     * Collects each hosted component's pin: one per provider identity among the verified contracts whose signed
     * transport is in-process. A hosted contract without a pin is left for admission to refuse.
     *
     * @return provider identity ({@code uri@version}) to pin, sorted by identity
     */
    private static Map<String, ProviderArtifactPin> hostedPins(VerifiedContracts contracts) {
        Map<String, ProviderArtifactPin> pins = new TreeMap<>();
        for (VerifiedContract verified : contracts.all()) {
            CompositionContract contract = verified.contract();
            if (contract.transport() == null || contract.transport().kind() != TransportKind.IN_PROCESS) continue;
            Optional<ProviderArtifactPin> pin = verified.pin();
            if (pin.isEmpty()) continue;
            String provider = contract.parties().provider().claimUri() + "@" + contract.parties().provider().claimVersion();
            ProviderArtifactPin earlier = pins.putIfAbsent(provider, pin.get());
            if (earlier != null && !earlier.equals(pin.get())) {
                throw new ComponentHostException(HostFailureCode.PROVIDER_PIN_INVALID, verified.id(),
                        "provider " + provider + " is pinned to different artifact sets by its hosted contracts");
            }
        }
        return pins;
    }

    /** Stage-1 rules for one component; see the class comment. */
    private static Component component(String provider, ProviderArtifactPin pin, Map<String, Snapshot> bySha,
                                       SharedApi shared, int feature) {
        List<PinnedArtifact> artifacts = new ArrayList<>();
        Map<String, String> classOwner = new TreeMap<>();
        List<String> declarations = new ArrayList<>();
        PinnedArtifact declaring = null;
        for (ArtifactPin artifactPin : pin.all()) {
            String subject = artifactPin.coordinates();
            Snapshot snapshot = bySha.get(artifactPin.sha256());
            if (snapshot == null) {
                throw new ComponentHostException(HostFailureCode.COMPONENT_DEPENDENCY_MISSING, subject,
                        "no delivered jar has the pinned SHA-256 (provider " + provider + ")");
            }
            PinnedArtifact artifact;
            try {
                artifact = PinnedArtifact.read(artifactPin, snapshot.fileName(), snapshot.bytes(), feature);
            } catch (IOException unreadable) {
                throw new ComponentHostException(HostFailureCode.ARTIFACT_METADATA_INVALID, subject,
                        "the pinned file is not a readable jar: " + unreadable.getMessage(), unreadable);
            }
            if (artifact.classPathDeclared()) {
                throw new ComponentHostException(HostFailureCode.ARTIFACT_CLASS_PATH_FORBIDDEN, subject,
                        "the manifest declares Class-Path or Launcher-Agent-Class, which could add unpinned code");
            }
            Optional<String> shadow = artifact.shadowOf(shared);
            if (shadow.isPresent()) {
                throw new ComponentHostException(HostFailureCode.ARTIFACT_SHADOWS_SHARED_API, subject,
                        "entry " + shadow.get() + " lies in a shared API package");
            }
            for (String metadata : List.of(PackagedClaim.RESOURCE, ComponentConfigurationSchema.RESOURCE, ACTIVATION_DECLARATION)) {
                if (artifact.hasVersionedCopy(metadata)) {
                    throw new ComponentHostException(HostFailureCode.ARTIFACT_VERSIONED_METADATA, subject,
                            metadata + " also appears under META-INF/versions/");
                }
            }
            for (String name : artifact.classNames()) {
                String earlier = classOwner.putIfAbsent(name, subject);
                if (earlier != null) {
                    throw new ComponentHostException(HostFailureCode.ARTIFACT_CLASS_DUPLICATE, subject,
                            "class " + name + " is also in " + earlier);
                }
            }
            for (String name : declaredActivations(artifact, subject)) {
                declarations.add(name);
                declaring = artifact;
            }
            artifacts.add(artifact);
        }

        String subject = pin.claimArtifact().coordinates();
        if (declarations.isEmpty()) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_ACTIVATION_MISSING, subject,
                    "no pinned artifact declares " + ACTIVATION_DECLARATION);
        }
        if (declarations.size() > 1) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_ACTIVATION_DUPLICATE, subject,
                    "the component declares " + declarations.size() + " activations; exactly one is allowed");
        }
        if (!declaring.hasClass(declarations.get(0))) {
            throw new ComponentHostException(HostFailureCode.ACTIVATION_ORIGIN_MISMATCH, declaring.pin().coordinates(),
                    "declared activation class " + declarations.get(0) + " is not in the declaring artifact");
        }
        PinnedArtifact claimArtifact = artifacts.get(0);
        Claim claim = metadata(claimArtifact, PackagedClaim.RESOURCE).map(bytes -> {
            try {
                return PackagedClaim.parse(bytes);
            } catch (PackagedClaim.PackagedClaimException invalid) {
                throw new ComponentHostException(HostFailureCode.ARTIFACT_METADATA_INVALID, subject,
                        "the packaged claim is invalid: " + invalid.getMessage(), invalid);
            }
        }).orElse(null);
        byte[] configuration = metadata(claimArtifact, ComponentConfigurationSchema.RESOURCE).orElse(null);
        return new Component(List.copyOf(artifacts), claim, configuration, declarations.get(0), declaring);
    }

    /** Parses an artifact's activation declaration: blank lines and {@code #} comments are ignored; each name counts. */
    private static List<String> declaredActivations(PinnedArtifact artifact, String subject) {
        Optional<byte[]> declaration = metadata(artifact, ACTIVATION_DECLARATION);
        if (declaration.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (String line : new String(declaration.get(), StandardCharsets.UTF_8).split("\\R")) {
            int comment = line.indexOf('#');
            String name = (comment < 0 ? line : line.substring(0, comment)).trim();
            if (name.isEmpty()) continue;
            if (!name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*")) {
                throw new ComponentHostException(HostFailureCode.ARTIFACT_METADATA_INVALID, subject,
                        "the activation declaration does not name a class");
            }
            names.add(name);
        }
        if (names.isEmpty()) {
            throw new ComponentHostException(HostFailureCode.ARTIFACT_METADATA_INVALID, subject,
                    "the activation declaration names no class");
        }
        return names;
    }

    /** Bounded metadata read; an oversized entry is {@code ARTIFACT_METADATA_INVALID}. */
    private static Optional<byte[]> metadata(PinnedArtifact artifact, String basePath) {
        try {
            return artifact.metadata(basePath, MAX_METADATA_BYTES);
        } catch (IOException unreadable) {
            throw new ComponentHostException(HostFailureCode.ARTIFACT_METADATA_INVALID, artifact.pin().coordinates(),
                    basePath + " is over " + MAX_METADATA_BYTES + " bytes", unreadable);
        }
    }

    /** The one read of a delivered file; an unreadable file cannot satisfy any pin. */
    private static byte[] read(Path jar, String fileName) {
        try {
            return Files.readAllBytes(jar);
        } catch (IOException unreadable) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_DEPENDENCY_MISSING, fileName,
                    "the delivered file cannot be read", unreadable);
        }
    }

    /** Hashes a snapshot's bytes, the same bytes that are later parsed and loaded. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
