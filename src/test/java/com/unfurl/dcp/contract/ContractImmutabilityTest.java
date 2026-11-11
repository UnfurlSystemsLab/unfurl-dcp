package com.unfurl.dcp.contract;

import com.unfurl.dcp.value.DeepImmutable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the contract records freeze their open-ended maps deeply ({@link DeepImmutable}): nested maps, lists
 * and sets are immutable and detached from the caller's objects, so a contract cannot change after it is built.
 */
class ContractImmutabilityTest {

    /** Nested values of contract extensions cannot be edited, and later edits to the source do not show through. */
    @Test
    void metadataExtensionsAreDeeplyImmutableAndDetached() {
        Map<String, Object> companion = new HashMap<>(Map.of("coordinates", "g:a:1", "sha256", "a".repeat(64)));
        List<Object> companions = new ArrayList<>(List.of(companion));
        Map<String, Object> source = new HashMap<>(Map.of("companionArtifacts", companions, "claimHash", "b".repeat(64)));

        CompositionContractMetadata metadata = new CompositionContractMetadata(source);
        companion.put("sha256", "tampered");
        companions.add("extra");
        source.put("added", true);

        assertThat(metadata.extensions()).containsOnlyKeys("companionArtifacts", "claimHash");
        List<?> frozen = (List<?>) metadata.extensions().get("companionArtifacts");
        assertThat(frozen).hasSize(1);
        Map<?, ?> frozenCompanion = (Map<?, ?>) frozen.get(0);
        assertThat(frozenCompanion.get("sha256")).isEqualTo("a".repeat(64));
        assertImmutable(metadata.extensions());
    }

    /** Transport details follow the same rule, including sets and nested lists of maps. */
    @Test
    @SuppressWarnings("unchecked")
    void transportDetailsAreDeeplyImmutable() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("headers", new LinkedHashMap<>(Map.of("accept", new ArrayList<>(List.of("a", "b")))));
        details.put("modes", new LinkedHashSet<>(List.of("x", "y")));
        Transport transport = new Transport(TransportKind.IN_PROCESS, details);

        assertThat((Set<Object>) transport.details().get("modes")).containsExactly("x", "y");
        assertImmutable(transport.details());
    }

    /** Nested nulls survive as decoded JSON may hold them; a null top-level value is still rejected; null means empty. */
    @Test
    @SuppressWarnings("unchecked")
    void keepsExistingNullSemantics() {
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        Map<String, Object> nested = new HashMap<>();
        nested.put("absent", null);
        CompositionContractMetadata metadata = new CompositionContractMetadata(Map.of("list", withNull, "map", nested));
        assertThat((List<Object>) metadata.extensions().get("list")).containsExactly((Object) null);
        assertThat((Map<Object, Object>) metadata.extensions().get("map")).containsEntry("absent", null);

        Map<String, Object> topLevelNull = new HashMap<>();
        topLevelNull.put("x", null);
        assertThatNullPointerException().isThrownBy(() -> new CompositionContractMetadata(topLevelNull));
        assertThat(new CompositionContractMetadata(null).extensions()).isEmpty();
        assertThat(new Transport(TransportKind.IN_PROCESS, null).details()).isEmpty();
    }

    /** Walks a frozen value and requires every map, list and set in it to refuse modification. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void assertImmutable(Object value) {
        if (value instanceof Map map) {
            assertThatThrownBy(() -> map.put("__probe", 1)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(map::clear).isInstanceOf(UnsupportedOperationException.class);
            map.values().forEach(ContractImmutabilityTest::assertImmutable);
        } else if (value instanceof List list) {
            assertThatThrownBy(() -> list.add("__probe")).isInstanceOf(UnsupportedOperationException.class);
            list.forEach(ContractImmutabilityTest::assertImmutable);
        } else if (value instanceof Set set) {
            assertThatThrownBy(() -> set.add("__probe")).isInstanceOf(UnsupportedOperationException.class);
            set.forEach(ContractImmutabilityTest::assertImmutable);
        }
    }
}
