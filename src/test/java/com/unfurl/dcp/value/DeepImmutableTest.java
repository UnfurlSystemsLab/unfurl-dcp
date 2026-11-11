package com.unfurl.dcp.value;

import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.claim.IntegrationPorts;
import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.dcp.claim.OfferInterface;
import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.dcp.runtimebinding.DeploymentControls;
import com.unfurl.dcp.runtimebinding.RuntimeBindingMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the deep-copy rule and that every claim and runtime-binding record with an open-ended map applies it, so a
 * packaged claim or trusted binding a host holds cannot change after it was checked. (The contract records are covered
 * by {@code ContractImmutabilityTest}.)
 */
class DeepImmutableTest {

    /** Nested collections are copied and frozen at every depth; scalars and nested nulls are kept. */
    @Test
    void freezesEveryNestedCollectionAndDetachesFromTheSource() {
        List<Object> inner = new ArrayList<>(List.of("a"));
        Map<String, Object> nested = new HashMap<>();
        nested.put("list", inner);
        nested.put("absent", null);
        Map<String, Object> source = new HashMap<>(Map.of("nested", nested, "n", 7));

        Map<String, Object> frozen = DeepImmutable.map(source);
        inner.add("b");
        nested.put("later", true);

        assertThat(frozen).containsEntry("n", 7);
        Map<?, ?> frozenNested = (Map<?, ?>) frozen.get("nested");
        assertThat(frozenNested.keySet()).map(Object::toString).containsExactlyInAnyOrder("list", "absent");
        assertThat(frozenNested.get("list")).isEqualTo(List.of("a"));
        assertImmutable(frozen);
        assertThat(DeepImmutable.map(null)).isEmpty();
    }

    /** Each claim and runtime-binding record freezes its open map deeply. */
    @Test
    void claimAndBindingRecordsAreDeeplyImmutable() {
        List<Function<Map<String, Object>, Map<String, Object>>> records = List.of(
                map -> new ClaimMetadata(null, null, null, map).extensions(),
                map -> new IntegrationPorts(map).ports(),
                map -> new OfferInterface(InterfaceKind.IN_PROCESS, map).details(),
                map -> new Configuration(map).values(),
                map -> new DeploymentControls(map).values(),
                map -> new RuntimeBindingMetadata(map).extensions());
        for (Function<Map<String, Object>, Map<String, Object>> record : records) {
            List<Object> list = new ArrayList<>(List.of(new HashMap<>(Map.of("k", "v"))));
            Map<String, Object> source = new HashMap<>(Map.of("items", list));

            Map<String, Object> held = record.apply(source);
            list.add("later");

            assertThat((List<?>) held.get("items")).hasSize(1);
            assertImmutable(held);
        }
    }

    /** Walks a frozen value and requires every map, list and set in it to refuse modification. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertImmutable(Object value) {
        if (value instanceof Map map) {
            assertThatThrownBy(() -> map.put("__probe", 1)).isInstanceOf(UnsupportedOperationException.class);
            map.values().forEach(DeepImmutableTest::assertImmutable);
        } else if (value instanceof List list) {
            assertThatThrownBy(() -> list.add("__probe")).isInstanceOf(UnsupportedOperationException.class);
            list.forEach(DeepImmutableTest::assertImmutable);
        } else if (value instanceof Set set) {
            assertThatThrownBy(() -> set.add("__probe")).isInstanceOf(UnsupportedOperationException.class);
            set.forEach(DeepImmutableTest::assertImmutable);
        }
    }
}
