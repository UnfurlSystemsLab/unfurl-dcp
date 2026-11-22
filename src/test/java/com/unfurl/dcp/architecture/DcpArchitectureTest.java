package com.unfurl.dcp.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture test suite: enforces DCP package boundaries and enterprise
 * guardrails that keep runtime code deterministic and adapter-neutral.
 */
@AnalyzeClasses(packages = "com.unfurl.dcp", importOptions = ImportOption.DoNotIncludeTests.class)
class DcpArchitectureTest {
    /** Ports and Adapters: the schema-validation vendor is confined to its offline adapter. */
    @ArchTest
    static final ArchRule schema_library_is_confined_to_adapter =
            noClasses().that().doNotHaveFullyQualifiedName("com.unfurl.dcp.documentation.HarrelSchemaSyntaxValidator")
                    .should().dependOnClassesThat().resideInAPackage("dev.harrel.jsonschema..");
    @ArchTest
    static final ArchRule broker_does_not_import_design_time_packages =
            noClasses().that().resideInAPackage("..broker..")
                    .should().dependOnClassesThat().resideInAnyPackage("..questions..", "..manifest..", "..description..");

    @ArchTest
    static final ArchRule broker_uses_registrar_not_substrate_registry =
            noClasses().that().resideInAPackage("..broker..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("com.unfurl.substrate.ports.CapabilityRegistry");

    /**
     * The in-process host sits on top of the broker; nothing else in DCP depends on it (LLD, In-Process Component Host),
     * except that documentation reads the host's verified-set and admission-evidence value types (LLD, Capability
     * Documentation Projection), and only those.
     */
    @ArchTest
    static final ArchRule only_the_host_depends_on_the_host =
            noClasses().that().resideOutsideOfPackages("com.unfurl.dcp.host..", "com.unfurl.dcp.documentation..")
                    .should().dependOnClassesThat().resideInAPackage("com.unfurl.dcp.host..");

    /** The host value types documentation may use. */
    private static final java.util.Set<String> DOCUMENTATION_HOST_TYPES = java.util.Set.of(
            "com.unfurl.dcp.host.VerifiedComposition",
            "com.unfurl.dcp.host.VerifiedContracts",
            "com.unfurl.dcp.host.VerifiedContract",
            "com.unfurl.dcp.host.ProviderArtifactPin",
            "com.unfurl.dcp.host.ArtifactPin",
            "com.unfurl.dcp.host.HostReport",
            "com.unfurl.dcp.host.HostReport$AdmittedComponent",
            "com.unfurl.dcp.host.PackagedResources");

    /** The documentation package's only host imports are value types; it never reaches the host itself. */
    @ArchTest
    static final ArchRule documentation_uses_only_host_value_types =
            noClasses().that().resideInAPackage("com.unfurl.dcp.documentation..")
                    .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                            "a host class other than its value types",
                            (com.tngtech.archunit.core.domain.JavaClass target) ->
                                    target.getPackageName().startsWith("com.unfurl.dcp.host")
                                            && !DOCUMENTATION_HOST_TYPES.contains(target.getName())));

    /** The host never depends on documentation. */
    @ArchTest
    static final ArchRule host_does_not_depend_on_documentation =
            noClasses().that().resideInAPackage("com.unfurl.dcp.host..")
                    .should().dependOnClassesThat().resideInAPackage("com.unfurl.dcp.documentation..");

    /** The deep-copy utility is a leaf: it uses only the JDK, so every DCP package may depend on it. */
    @ArchTest
    static final ArchRule value_is_a_jdk_only_leaf =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that().resideInAPackage("com.unfurl.dcp.value..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.unfurl.dcp.value..");

    @ArchTest
    static final ArchRule trust_is_leaf_inside_dcp =
            noClasses().that().resideInAPackage("..trust..")
                    .should().dependOnClassesThat().resideInAnyPackage("..contract..", "..broker..", "..spi..");

    @ArchTest
    static final ArchRule production_does_not_depend_on_testing =
            noClasses().that().resideOutsideOfPackage("..testing..")
                    .should().dependOnClassesThat().resideInAPackage("..testing..");

    @ArchTest
    static final ArchRule no_host_product_dependencies =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                    "com.unfurl.flow..",
                    "com.unfurl.foundry..",
                    "com.unfurl.fabric..");

    @ArchTest
    static final ArchRule design_time_and_schema_packages_do_not_import_broker_or_spi =
            noClasses().that().resideInAnyPackage(
                            "..description..",
                            "..componentconfig..",
                            "..value..",
                            "..claim..",
                            "..fault..",
                            "..manifest..",
                            "..runtimebinding..",
                            "..questions..",
                            "..resolver..",
                            "..versioning..",
                            "..validation..",
                            "..documentation..")
                    .should().dependOnClassesThat().resideInAnyPackage("..broker..", "..spi..");

    @ArchTest
    static final ArchRule no_forbidden_runtime_or_sdk_dependencies =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.ws.rs..",
                    "javax.ws.rs..",
                    "java.net.http..",
                    "okhttp3..",
                    "org.apache.http..",
                    "java.sql..",
                    "javax.sql..",
                    "jakarta.persistence..",
                    "software.amazon.awssdk..",
                    "com.amazonaws..",
                    "com.google.cloud..",
                    "com.azure..",
                    "com.openai..",
                    "ai.openai..",
                    "io.opentelemetry..",
                    "io.micrometer..",
                    "org.slf4j..");

    @ArchTest
    static final ArchRule no_substrate_engine_dependency =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                    "com.unfurl.substrate.engine..",
                    "com.unfurl.substrate.resolver..",
                    "com.unfurl.substrate.conditions..",
                    "com.unfurl.substrate.serialization..",
                    "com.unfurl.substrate.componentmodel..",
                    "com.unfurl.substrate.domain..",
                    "com.unfurl.substrate.events..");
}
