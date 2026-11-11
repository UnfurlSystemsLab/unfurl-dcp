package com.unfurl.dcp.host;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.dcp.contract.Transport;
import com.unfurl.dcp.contract.TransportKind;
import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the two-stage pinned-artifact repository (LLD, <em>In-Process Component Host</em>) with real jars compiled at
 * test time: stage 1 enforces every artifact rule from zip data and runs no component code; stage 2 loads a component
 * only from its pinned jars, isolated from the host classpath, and constructs its activation last.
 */
class PinnedJarArtifactsTest {
    private static final String CONSTRUCTED = "dcp.test.constructed";
    private static final String DECLARATION = PinnedJarArtifacts.ACTIVATION_DECLARATION;
    private static final String PKG = "example/component/";
    private static final Claim CLAIM = Fixtures.validProviderClaim();

    private static Path scratch;
    /** Entry path to class bytes for every fixture class. */
    private static Map<String, byte[]> classes;
    /** {@code example.component.Versioned} compiled a second time, returning {@code "v9"}. */
    private static byte[] versionedV9;

    @TempDir Path dir;

    @BeforeAll
    static void compileFixtures() throws IOException {
        scratch = Files.createTempDirectory("dcp-host-fixtures");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("example.component.BaseActivation", """
                package example.component;
                import com.unfurl.dcp.contract.Party;
                import com.unfurl.dcp.host.ActivationContext;
                import com.unfurl.dcp.host.ComponentActivation;
                import com.unfurl.dcp.spi.CapabilityRegistrar;
                import com.unfurl.dcp.spi.ContractInvocableFactory;
                import java.net.URI;
                import java.util.Map;
                import java.util.Optional;
                import java.util.Set;
                public abstract class BaseActivation implements ComponentActivation {
                    public URI claimUri() { return URI.create("urn:provider"); }
                    public String claimVersion() { return "1.0.0"; }
                    public Map<String, Set<Class<?>>> hostDependencies() { return Map.of(); }
                    public ContractInvocableFactory factory(ActivationContext context) { throw new UnsupportedOperationException(); }
                    public <R extends CapabilityRegistrar> Optional<R> registrar(Class<R> shape, Party consumer) { return Optional.empty(); }
                }
                """);
        sources.put("example.component.ExampleActivation", """
                package example.component;
                public class ExampleActivation extends BaseActivation {
                    public ExampleActivation() {
                        System.setProperty("dcp.test.constructed", String.valueOf(
                                Thread.currentThread().getContextClassLoader() == ExampleActivation.class.getClassLoader()));
                    }
                    @Override public String claimVersion() { return example.lib.Helper.version(); }
                }
                """);
        sources.put("example.component.ThrowingActivation", """
                package example.component;
                public class ThrowingActivation extends BaseActivation {
                    public ThrowingActivation() { throw new IllegalStateException("boom"); }
                }
                """);
        sources.put("example.component.NotAnActivation", """
                package example.component;
                public class NotAnActivation {
                    public NotAnActivation() { System.setProperty("dcp.test.constructed", "not-an-activation"); }
                }
                """);
        sources.put("example.component.Orphan", """
                package example.component;
                public class Orphan extends org.assertj.core.api.SoftAssertions { }
                """);
        sources.put("example.component.JacksonActivation", """
                package example.component;
                import com.fasterxml.jackson.databind.ObjectMapper;
                public class JacksonActivation extends BaseActivation {
                    private final String json;
                    public JacksonActivation() throws Exception {
                        json = new ObjectMapper().writeValueAsString(java.util.Map.of("ok", true));
                    }
                    @Override public String claimVersion() {
                        return json + (ObjectMapper.class.getClassLoader() == getClass().getClassLoader() ? " isolated" : " leaked");
                    }
                }
                """);
        sources.put("example.component.Versioned", """
                package example.component;
                public class Versioned { public static String which() { return "base"; } }
                """);
        sources.put("example.lib.Helper", """
                package example.lib;
                public class Helper { public static String version() { return "1.0.0"; } }
                """);
        classes = ComponentJars.compile(scratch, sources);
        versionedV9 = ComponentJars.compile(scratch, Map.of("example.component.Versioned", """
                package example.component;
                public class Versioned { public static String which() { return "v9"; } }
                """)).get(PKG + "Versioned.class");
    }

    @AfterAll
    static void deleteFixtures() throws IOException {
        try (Stream<Path> files = Files.walk(scratch)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    @BeforeEach
    void clearMarker() {
        System.clearProperty(CONSTRUCTED);
    }

    /** Stage 1 runs no component code; stage 2 activates once, isolated from the host classpath. */
    @Test
    void inspectsAsDataThenActivatesInIsolation() throws Exception {
        Path main = component("component.jar", "example.component.ExampleActivation", Map.of(
                "META-INF/unfurl-config.yaml", "config_version: 1\n".getBytes(StandardCharsets.UTF_8)), Map.of());
        Path lib = library("lib.jar");
        Path extra = ComponentJars.jar(dir.resolve("extra.jar"), Map.of("unrelated.txt", new byte[]{1}), Map.of());
        ProviderArtifactPin pin = pin(main, lib);

        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(extra, lib, main), contracts(pin), shared())) {
            assertThat(System.getProperty(CONSTRUCTED)).isNull();
            assertThat(artifacts.packagedClaim(pin)).contains(CLAIM);
            assertThat(artifacts.packagedConfiguration(pin)).hasValue("config_version: 1\n".getBytes(StandardCharsets.UTF_8));
            assertThat(artifacts.ignoredArtifacts()).containsExactly("extra.jar");
            assertThat(System.getProperty(CONSTRUCTED)).isNull();

            ComponentActivation activation = artifacts.activate(pin);

            assertThat(System.getProperty(CONSTRUCTED)).as("context class loader during construction").isEqualTo("true");
            assertThat(activation.claimVersion()).as("resolved through the pinned companion").isEqualTo("1.0.0");
            assertThat(artifacts.activate(pin)).isSameAs(activation);
            ClassLoader loader = activation.getClass().getClassLoader();
            assertThat(loader).isNotSameAs(getClass().getClassLoader());
            assertThat(loader.getName()).startsWith("dcp-component:");
            // Shared API: one copy, the host's. Host-only classes and resources: invisible.
            assertThat(loader.loadClass(ComponentActivation.class.getName())).isSameAs(ComponentActivation.class);
            assertThatThrownBy(() -> loader.loadClass("org.assertj.core.api.Assertions")).isInstanceOf(ClassNotFoundException.class);
            assertThat(loader.getResource("org/assertj/core/api/Assertions.class")).isNull();
            assertThat(loader.loadClass("java.util.List")).isSameAs(List.class);
            URL declaration = loader.getResource(DECLARATION);
            assertThat(declaration.toString()).startsWith("jar:").contains("component.jar").endsWith(DECLARATION);
            try (var in = declaration.openStream()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim()).isEqualTo("example.component.ExampleActivation");
            }
            assertThat(Collections.list(loader.getResources("META-INF/MANIFEST.MF"))).hasSize(2);
            try (var in = loader.getResourceAsStream("example/lib/Helper.class")) {
                assertThat(in).isNotNull();
            }

            artifacts.close();
            assertThatThrownBy(() -> artifacts.activate(pin)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(declaration::openStream).as("resource URLs read the closed pinned jar, not the path")
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> loader.loadClass("example.component.Versioned")).isInstanceOf(ClassNotFoundException.class);
        }
    }

    /** Every pinned artifact must be delivered with its exact SHA-256. */
    @Test
    void refusesAMissingOrDifferentPinnedArtifact() throws Exception {
        Path main = component("component.jar", "example.component.ExampleActivation", Map.of(), Map.of());
        Path lib = library("lib.jar");
        ProviderArtifactPin pin = pin(main, lib);
        Path otherLib = ComponentJars.jar(dir.resolve("other-lib.jar"), Map.of("example/lib/Helper.class", new byte[]{0}), Map.of());

        assertRefused(HostFailureCode.COMPONENT_DEPENDENCY_MISSING, "example:lib:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(main), contracts(pin), shared()));
        assertRefused(HostFailureCode.COMPONENT_DEPENDENCY_MISSING, "example:lib:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(main, otherLib), contracts(pin), shared()));
    }

    /** A manifest that could extend the class path, or code under a shared package, is refused from zip data. */
    @Test
    void refusesClassPathManifestsAndSharedApiShadows() throws Exception {
        Path lib = library("lib.jar");
        Path classPath = component("cp.jar", "example.component.ExampleActivation", Map.of(), Map.of("Class-Path", "evil.jar"));
        Path agent = component("agent.jar", "example.component.ExampleActivation", Map.of(), Map.of("Launcher-Agent-Class", "x.Agent"));
        Path shadow = component("shadow.jar", "example.component.ExampleActivation",
                Map.of("com/unfurl/dcp/host/Evil.class", new byte[]{1}), Map.of());
        // Not multi-release, and version 99 is beyond any runtime: still refused, so a newer JVM cannot reveal it.
        Path hiddenShadow = component("hidden-shadow.jar", "example.component.ExampleActivation",
                Map.of("META-INF/versions/99/com/unfurl/substrate/policy/Evil.class", new byte[]{1}), Map.of());

        for (Path main : List.of(classPath, agent)) {
            assertRefused(HostFailureCode.ARTIFACT_CLASS_PATH_FORBIDDEN, "example:component:1.0.0",
                    () -> PinnedJarArtifacts.inspect(List.of(main, lib), contracts(pin(main, lib)), shared()));
        }
        for (Path main : List.of(shadow, hiddenShadow)) {
            assertRefused(HostFailureCode.ARTIFACT_SHADOWS_SHARED_API, "example:component:1.0.0",
                    () -> PinnedJarArtifacts.inspect(List.of(main, lib), contracts(pin(main, lib)), shared()));
        }
    }

    /** One class in two artifacts is refused; module descriptors and package-info in each are not duplicates. */
    @Test
    void refusesDuplicateClassesButNotDescriptors() throws Exception {
        Path main = component("component.jar", "example.component.ExampleActivation",
                Map.of("module-info.class", new byte[]{1}, PKG + "package-info.class", new byte[]{1}), Map.of());
        Path duplicate = ComponentJars.jar(dir.resolve("dup.jar"), Map.of(
                "example/lib/Helper.class", classes.get("example/lib/Helper.class"),
                PKG + "ExampleActivation.class", classes.get(PKG + "ExampleActivation.class")), Map.of());
        Path modular = ComponentJars.jar(dir.resolve("modular.jar"), Map.of(
                "example/lib/Helper.class", classes.get("example/lib/Helper.class"),
                "module-info.class", new byte[]{2}, PKG + "package-info.class", new byte[]{2}), Map.of());

        assertRefused(HostFailureCode.ARTIFACT_CLASS_DUPLICATE, "example:lib:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(main, duplicate), contracts(pin(main, duplicate)), shared()));
        ProviderArtifactPin pin = pin(main, modular);
        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(main, modular), contracts(pin), shared())) {
            assertThat(artifacts.activate(pin).claimVersion()).isEqualTo("1.0.0");
        }
    }

    /** The packaged claim, configuration and declaration are read from base paths only; a versioned copy is refused. */
    @Test
    void refusesVersionedCopiesOfPackagedMetadata() throws Exception {
        Path lib = library("lib.jar");
        int n = 0;
        for (String metadata : List.of("META-INF/unfurl-catalog.yaml", "META-INF/unfurl-config.yaml", DECLARATION)) {
            Path main = component("versioned-" + n++ + ".jar", "example.component.ExampleActivation",
                    Map.of("META-INF/versions/11/" + metadata, new byte[]{1}), Map.of("Multi-Release", "true"));
            assertRefused(HostFailureCode.ARTIFACT_VERSIONED_METADATA, "example:component:1.0.0",
                    () -> PinnedJarArtifacts.inspect(List.of(main, lib), contracts(pin(main, lib)), shared()));
        }
    }

    /** Exactly one activation declaration, well formed, naming a class of its own artifact. */
    @Test
    void refusesMissingDuplicateForeignOrMalformedDeclarations() throws Exception {
        Path lib = library("lib.jar");
        Path none = component("none.jar", null, Map.of(), Map.of());
        Path two = component("two.jar", "example.component.ExampleActivation\nexample.component.ThrowingActivation", Map.of(), Map.of());
        Path commented = component("commented.jar", "# the activation\n\nexample.component.ExampleActivation  # one line\n", Map.of(), Map.of());
        Path malformed = component("malformed.jar", "not a class!", Map.of(), Map.of());
        Path empty = component("empty.jar", "# nothing\n", Map.of(), Map.of());
        Path declaringLib = ComponentJars.jar(dir.resolve("declaring-lib.jar"), Map.of(
                "example/lib/Helper.class", classes.get("example/lib/Helper.class"),
                DECLARATION, "example.component.ExampleActivation".getBytes(StandardCharsets.UTF_8)), Map.of());
        Path main = component("component.jar", "example.component.ExampleActivation", Map.of(), Map.of());

        assertRefused(HostFailureCode.COMPONENT_ACTIVATION_MISSING, "example:component:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(none, lib), contracts(pin(none, lib)), shared()));
        assertRefused(HostFailureCode.COMPONENT_ACTIVATION_DUPLICATE, "example:component:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(two, lib), contracts(pin(two, lib)), shared()));
        assertRefused(HostFailureCode.COMPONENT_ACTIVATION_DUPLICATE, "example:component:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(main, declaringLib), contracts(pin(main, declaringLib)), shared()));
        assertRefused(HostFailureCode.ACTIVATION_ORIGIN_MISMATCH, "example:lib:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(none, declaringLib), contracts(pin(none, declaringLib)), shared()));
        assertRefused(HostFailureCode.ARTIFACT_METADATA_INVALID, "example:component:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(malformed, lib), contracts(pin(malformed, lib)), shared()));
        assertRefused(HostFailureCode.ARTIFACT_METADATA_INVALID, "example:component:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(empty, lib), contracts(pin(empty, lib)), shared()));
        try (PinnedJarArtifacts ok = PinnedJarArtifacts.inspect(List.of(commented, lib), contracts(pin(commented, lib)), shared())) {
            assertThat(ok.ignoredArtifacts()).isEmpty();
        }
        assertThat(System.getProperty(CONSTRUCTED)).as("no refusal ran component code").isNull();
    }

    /** An invalid packaged claim is refused; a missing one is reported absent for the host to refuse. */
    @Test
    void readsThePackagedClaimStrictly() throws Exception {
        Path lib = library("lib.jar");
        Path invalid = component("invalid.jar", "example.component.ExampleActivation",
                Map.of("META-INF/unfurl-catalog.yaml", "claim: {}\n".getBytes(StandardCharsets.UTF_8)), Map.of());
        Map<String, byte[]> noClaim = componentEntries("example.component.ExampleActivation", Map.of());
        noClaim.remove("META-INF/unfurl-catalog.yaml");
        Path missing = ComponentJars.jar(dir.resolve("no-claim.jar"), noClaim, Map.of());

        assertRefused(HostFailureCode.ARTIFACT_METADATA_INVALID, "example:component:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(invalid, lib), contracts(pin(invalid, lib)), shared()));
        ProviderArtifactPin pin = pin(missing, lib);
        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(missing, lib), contracts(pin), shared())) {
            assertThat(artifacts.packagedClaim(pin)).isEmpty();
            assertThat(artifacts.packagedConfiguration(pin)).isEmpty();
        }
    }

    /** Only in-process contracts are hosted, and one provider has one pin. */
    @Test
    void takesPinsOnlyFromInProcessContractsAndRequiresOnePinPerProvider() throws Exception {
        Path main = component("component.jar", "example.component.ExampleActivation", Map.of(), Map.of());
        Path lib = library("lib.jar");
        ProviderArtifactPin pin = pin(main, lib);
        ProviderArtifactPin undelivered = new ProviderArtifactPin(new ArtifactPin("remote:service:1.0.0", "d".repeat(64)), "e".repeat(64), List.of());

        VerifiedContracts withRemote = VerifiedContracts.load(List.of(
                HostFixtures.source("hosted.json", HostFixtures.contract("urn:contract:hosted", "tool.a", extensions(pin))),
                HostFixtures.source("remote.json", HostFixtures.contract("urn:contract:remote", "tool.b", extensions(undelivered),
                        new Transport(TransportKind.HTTP_JSON, Map.of())))), HostFixtures.keys());
        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(main, lib), withRemote, shared())) {
            assertThat(artifacts.packagedClaim(pin)).isPresent();
            assertThat(artifacts.packagedClaim(undelivered)).isEmpty();
            assertRefused(HostFailureCode.COMPONENT_ARTIFACT_MISSING, "remote:service:1.0.0", () -> artifacts.activate(undelivered));
        }

        VerifiedContracts conflicting = VerifiedContracts.load(List.of(
                HostFixtures.source("a.json", HostFixtures.contract("urn:contract:a", "tool.a", extensions(pin))),
                HostFixtures.source("b.json", HostFixtures.contract("urn:contract:b", "tool.b", extensions(pin(main))))), HostFixtures.keys());
        assertRefused(HostFailureCode.PROVIDER_PIN_INVALID, "urn:contract:b@1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(main, lib), conflicting, shared()));
    }

    /** A pinned class whose superclass is outside the pinned set fails before the activation is constructed. */
    @Test
    void refusesUnresolvableClassesBeforeConstructing() throws Exception {
        Path main = component("component.jar", "example.component.ExampleActivation",
                Map.of(PKG + "Orphan.class", classes.get(PKG + "Orphan.class")), Map.of());
        Path lib = library("lib.jar");
        ProviderArtifactPin pin = pin(main, lib);
        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(main, lib), contracts(pin), shared())) {
            assertThatThrownBy(() -> artifacts.activate(pin)).isInstanceOfSatisfying(ComponentHostException.class, e -> {
                assertThat(e.code()).isEqualTo(HostFailureCode.COMPONENT_DEPENDENCY_UNRESOLVED);
                assertThat(e.getMessage()).contains("example.component.Orphan").contains("SoftAssertions");
            });
            assertThat(System.getProperty(CONSTRUCTED)).isNull();
        }
    }

    /** A declared class that is not a constructible activation, or whose constructor throws, is refused. */
    @Test
    void refusesActivationsThatCannotBeConstructed() throws Exception {
        Path lib = library("lib.jar");
        Path throwing = component("throwing.jar", "example.component.ThrowingActivation", Map.of(), Map.of());
        Path plain = component("plain.jar", "example.component.NotAnActivation", Map.of(), Map.of());
        Path abstractOne = component("abstract.jar", "example.component.BaseActivation", Map.of(), Map.of());

        ProviderArtifactPin throwingPin = pin(throwing, lib);
        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(throwing, lib), contracts(throwingPin), shared())) {
            assertThatThrownBy(() -> artifacts.activate(throwingPin)).isInstanceOfSatisfying(ComponentHostException.class, e -> {
                assertThat(e.code()).isEqualTo(HostFailureCode.COMPONENT_ACTIVATION_FAILED);
                assertThat(e.getCause()).isInstanceOf(IllegalStateException.class).hasMessage("boom");
            });
        }
        for (Path main : List.of(plain, abstractOne)) {
            ProviderArtifactPin pin = pin(main, lib);
            try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(main, lib), contracts(pin), shared())) {
                assertRefused(HostFailureCode.COMPONENT_ACTIVATION_FAILED, "example:component:1.0.0", () -> artifacts.activate(pin));
            }
        }
        assertThat(System.getProperty(CONSTRUCTED)).as("a class that is not an activation is never constructed").isNull();
    }

    /**
     * Regression: verification, inspection and loading use one in-memory snapshot. After inspection the files can be
     * replaced or deleted and it changes nothing: the component still loads, from exactly the bytes that were hashed.
     */
    @Test
    void loadsFromTheHashedSnapshotWhateverHappensOnDisk() throws Exception {
        Path main = component("component.jar", "example.component.ExampleActivation", Map.of(), Map.of());
        Path lib = library("lib.jar");
        ProviderArtifactPin pin = pin(main, lib);
        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(main, lib), contracts(pin), shared())) {
            Files.write(lib, new byte[]{'n', 'o', 't', ' ', 'a', ' ', 'j', 'a', 'r'});
            Files.delete(main);

            ComponentActivation activation = artifacts.activate(pin);

            assertThat(activation.claimVersion()).as("the companion class came from the snapshot").isEqualTo("1.0.0");
            assertThat(activation.getClass().getClassLoader().getResource(DECLARATION)).isNotNull();
        }
    }

    /** A pinned file that is not an archive, or that names one entry twice, is refused. */
    @Test
    void refusesFilesThatAreNotOneUnambiguousArchive() throws Exception {
        Path lib = library("lib.jar");
        Path garbage = Files.write(dir.resolve("garbage.jar"), "this is not a jar".getBytes(StandardCharsets.UTF_8));
        assertRefused(HostFailureCode.ARTIFACT_METADATA_INVALID, "example:component:1.0.0",
                () -> PinnedJarArtifacts.inspect(List.of(garbage, lib), contracts(pin(garbage, lib)), shared()));

        // Two entries of equal name length; renaming the second in the raw bytes yields an archive naming one entry twice.
        Path twice = component("twice.jar", "example.component.ExampleActivation",
                Map.of("dup/aaaa.txt", new byte[]{1}, "dup/bbbb.txt", new byte[]{2}), Map.of());
        String raw = new String(Files.readAllBytes(twice), StandardCharsets.ISO_8859_1).replace("dup/bbbb.txt", "dup/aaaa.txt");
        Files.write(twice, raw.getBytes(StandardCharsets.ISO_8859_1));
        assertThatThrownBy(() -> PinnedJarArtifacts.inspect(List.of(twice, lib), contracts(pin(twice, lib)), shared()))
                .isInstanceOfSatisfying(ComponentHostException.class, e -> {
                    assertThat(e.code()).isEqualTo(HostFailureCode.ARTIFACT_METADATA_INVALID);
                    assertThat(e.getMessage()).contains("names an entry twice");
                });
    }

    /** Multi-release selection matches the JDK: the highest version up to the running one, only when the manifest says so. */
    @Test
    void selectsVersionedClassesLikeTheJdk() throws Exception {
        Path lib = library("lib.jar");
        Map<String, byte[]> versioned = Map.of(
                PKG + "Versioned.class", classes.get(PKG + "Versioned.class"),
                "META-INF/versions/9/" + PKG + "Versioned.class", versionedV9,
                "META-INF/versions/999/" + PKG + "Versioned.class", new byte[]{1, 2, 3});
        Path multi = component("multi.jar", "example.component.ExampleActivation", versioned, Map.of("Multi-Release", "true"));
        Path plain = component("plain.jar", "example.component.ExampleActivation", Map.of(
                PKG + "Versioned.class", classes.get(PKG + "Versioned.class"),
                "META-INF/versions/9/" + PKG + "Versioned.class", versionedV9), Map.of());

        assertThat(which(multi, lib)).isEqualTo("v9");
        assertThat(which(plain, lib)).isEqualTo("base");
    }

    /** The real Jackson jars (multi-release and modular) load as pinned companions, in the component's own copy. */
    @Test
    void loadsRealJacksonJarsAsPinnedCompanions() throws Exception {
        Path main = component("component.jar", "example.component.JacksonActivation", Map.of(), Map.of());
        List<Path> jackson = new ArrayList<>();
        for (Class<?> type : List.of(ObjectMapper.class, JsonFactory.class, JsonInclude.class)) {
            jackson.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
        }
        List<ArtifactPin> companions = new ArrayList<>();
        for (Path jar : jackson) companions.add(ComponentJars.pin("jackson:" + jar.getFileName() + ":pinned", jar));
        ProviderArtifactPin pin = new ProviderArtifactPin(ComponentJars.pin("example:component:1.0.0", main),
                ClaimHash.canonical(CLAIM), companions);
        List<Path> delivered = new ArrayList<>(jackson);
        delivered.add(main);

        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(delivered, contracts(pin), shared())) {
            assertThat(artifacts.activate(pin).claimVersion()).isEqualTo("{\"ok\":true} isolated");
        }
    }

    /** Activates a component and asks its loader which Versioned class it selected. */
    private String which(Path main, Path lib) throws Exception {
        ProviderArtifactPin pin = pin(main, lib);
        try (PinnedJarArtifacts artifacts = PinnedJarArtifacts.inspect(List.of(main, lib), contracts(pin), shared())) {
            ClassLoader loader = artifacts.activate(pin).getClass().getClassLoader();
            return (String) loader.loadClass("example.component.Versioned").getMethod("which").invoke(null);
        }
    }

    private static void assertRefused(HostFailureCode code, String subject, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ComponentHostException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.subject()).isEqualTo(subject);
        });
    }

    private static SharedApi shared() {
        return SharedApi.of(PinnedJarArtifactsTest.class.getClassLoader(), java.util.Set.of());
    }

    /** One verified in-process contract carrying the pin. */
    private static VerifiedContracts contracts(ProviderArtifactPin pin) {
        return VerifiedContracts.load(List.of(HostFixtures.source("hosted.json",
                HostFixtures.contract("urn:contract:hosted", "tool.a", extensions(pin)))), HostFixtures.keys());
    }

    private static Map<String, Object> extensions(ProviderArtifactPin pin) {
        return HostFixtures.pinExtensions(pin.claimArtifact(), pin.claimHash(), pin.companions().toArray(ArtifactPin[]::new));
    }

    /** Pins a claim-bearing jar and its companion library jars under fixed coordinates. */
    private static ProviderArtifactPin pin(Path main, Path... libs) {
        List<ArtifactPin> companions = new ArrayList<>();
        for (Path lib : libs) companions.add(ComponentJars.pin("example:lib:1.0.0", lib));
        return new ProviderArtifactPin(ComponentJars.pin("example:component:1.0.0", main), ClaimHash.canonical(CLAIM), companions);
    }

    /** The default component entries: the activation classes, the packaged claim and (unless null) the declaration. */
    private static Map<String, byte[]> componentEntries(String declaration, Map<String, byte[]> extra) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : List.of("BaseActivation", "ExampleActivation", "ThrowingActivation", "NotAnActivation", "JacksonActivation")) {
            entries.put(PKG + name + ".class", classes.get(PKG + name + ".class"));
        }
        entries.put("META-INF/unfurl-catalog.yaml", HostFixtures.manifest(CLAIM));
        if (declaration != null) entries.put(DECLARATION, declaration.getBytes(StandardCharsets.UTF_8));
        entries.putAll(extra);
        return entries;
    }

    private Path component(String fileName, String declaration, Map<String, byte[]> extra, Map<String, String> manifest) throws IOException {
        return ComponentJars.jar(dir.resolve(fileName), componentEntries(declaration, extra), manifest);
    }

    private Path library(String fileName) throws IOException {
        return ComponentJars.jar(dir.resolve(fileName), Map.of("example/lib/Helper.class", classes.get("example/lib/Helper.class")), Map.of());
    }
}
