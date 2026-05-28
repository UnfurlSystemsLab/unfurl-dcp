package com.unfurl.dcp.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
}
