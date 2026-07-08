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
    @ArchTest
    static final ArchRule broker_does_not_import_design_time_packages =
            noClasses().that().resideInAPackage("..broker..")
                    .should().dependOnClassesThat().resideInAnyPackage("..questions..", "..manifest..", "..description..");

    @ArchTest
    static final ArchRule broker_uses_registrar_not_substrate_registry =
            noClasses().that().resideInAPackage("..broker..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("com.unfurl.substrate.ports.CapabilityRegistry");

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
                            "..claim..",
                            "..fault..",
                            "..manifest..",
                            "..runtimebinding..",
                            "..questions..",
                            "..resolver..",
                            "..versioning..",
                            "..validation..")
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
