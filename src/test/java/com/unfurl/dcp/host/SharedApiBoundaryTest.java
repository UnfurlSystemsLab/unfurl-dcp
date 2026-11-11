package com.unfurl.dcp.host;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the shared boundary explicit (LLD, <em>In-Process Component Host</em>, the shared boundary). A component may
 * exchange only shared or JDK types with its host, yet some public signatures in the shared packages expose a library
 * type that is not shared (for example Jackson's {@code ObjectMapper}). Those members are host-side only: a component
 * that needs the library brings its own copy as a pinned companion and never passes its objects across.
 *
 * <p>This test enumerates every such type, so adding one to a shared signature is a deliberate, reviewed change rather
 * than an accident that surfaces as a {@code ClassCastException} inside a hosted component.
 */
class SharedApiBoundaryTest {
    /** The non-shared, non-JDK types that public signatures of the shared packages expose today. Host-side only. */
    private static final Set<String> HOST_SIDE_ONLY = Set.of(
            // ContractCodec.canonicalMapper(): the canonical JSON mapper, used by hosts and composers only.
            "com.fasterxml.jackson.databind.ObjectMapper");

    @Test
    void sharedPackagesExposeOnlyTheListedNonSharedTypes() {
        SharedApi shared = SharedApi.of(getClass().getClassLoader(), Set.of());
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.unfurl.dcp", "com.unfurl.substrate");
        assertThat(classes.size()).as("shared packages were imported").isGreaterThan(100);

        Set<String> exposed = new TreeSet<>();
        for (JavaClass type : classes) {
            if (!shared.sharesClass(type.getName()) || !type.getModifiers().contains(JavaModifier.PUBLIC)) continue;
            type.getRawSuperclass().ifPresent(parent -> collect(parent, shared, exposed));
            type.getRawInterfaces().forEach(parent -> collect(parent, shared, exposed));
            for (JavaField field : type.getFields()) {
                if (visible(field.getModifiers())) field.getType().getAllInvolvedRawTypes().forEach(raw -> collect(raw, shared, exposed));
            }
            for (JavaCodeUnit unit : type.getCodeUnits()) {
                if (!visible(unit.getModifiers())) continue;
                unit.getReturnType().getAllInvolvedRawTypes().forEach(raw -> collect(raw, shared, exposed));
                for (JavaType parameter : unit.getParameterTypes()) {
                    parameter.getAllInvolvedRawTypes().forEach(raw -> collect(raw, shared, exposed));
                }
                unit.getExceptionTypes().forEach(raw -> collect(raw, shared, exposed));
            }
        }
        assertThat(exposed).containsExactlyInAnyOrderElementsOf(HOST_SIDE_ONLY);
    }

    /** A member counts when code outside its package can use it. */
    private static boolean visible(Set<JavaModifier> modifiers) {
        return modifiers.contains(JavaModifier.PUBLIC) || modifiers.contains(JavaModifier.PROTECTED);
    }

    /** Records a type that is neither shared nor provided by the JDK's platform loader. */
    private static void collect(JavaClass raw, SharedApi shared, Set<String> exposed) {
        JavaClass base = raw.getBaseComponentType();
        String name = base.getName();
        if (base.isPrimitive() || shared.sharesClass(name)) return;
        try {
            Class.forName(name, false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError notPlatform) {
            exposed.add(name);
        }
    }
}
