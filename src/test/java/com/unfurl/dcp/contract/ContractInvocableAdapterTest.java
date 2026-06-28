package com.unfurl.dcp.contract;

import com.unfurl.dcp.testing.CryptoFixtures;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ContractInvocableAdapterTest {
    @Test
    void tagsInvocationAndResultWithReservedMetadata() {
        AtomicReference<ContractInvocation> observed = new AtomicReference<>();
        ContractInvocable delegate = delegate((invocation, context) -> {
            observed.set(invocation);
            return ContractInvocationResult.success(Map.of("ok", true));
        });
        ContractInvocableAdapter adapter = new ContractInvocableAdapter(Fixtures.frozenContract(CryptoFixtures.keyPair()), "urn:contract@1.0.0", delegate);

        ContractInvocationResult result = adapter.invoke(invocation(Map.of()), ExecutionContext.empty());

        assertThat(observed.get().contractId()).isEqualTo("urn:contract");
        assertThat(observed.get().metadata()).containsEntry(ContractInvocableAdapter.CONTRACT_VERSION_KEY, "1.0.0");
        assertThat(observed.get().metadata()).containsEntry(ContractInvocableAdapter.TRUST_TIER_KEY, "NEUTRAL");
        assertThat(result.metadata()).containsEntry(ContractInvocableAdapter.REGISTRATION_HANDLE_KEY, "urn:contract@1.0.0");
    }

    @Test
    void usesExecutionContextCorrelationIdWhenInvocationDoesNotProvideOne() {
        AtomicReference<ContractInvocation> observed = new AtomicReference<>();
        ContractInvocableAdapter adapter = new ContractInvocableAdapter(Fixtures.frozenContract(CryptoFixtures.keyPair()), "handle",
                delegate((invocation, context) -> {
                    observed.set(invocation);
                    return ContractInvocationResult.success(Map.of());
                }));
        ExecutionContext context = new ExecutionContext("tenant", "user", java.util.List.of(), java.util.List.of(), "ctx-corr", "request", Map.of(), Map.of());

        adapter.invoke(new ContractInvocation("ignored", "op", "consumer", "provider", Map.of(), null, Map.of(), "hash", Map.of()), context);

        assertThat(observed.get().correlationId()).isEqualTo("ctx-corr");
    }

    @Test
    void rejectsInvocationReservedMetadataOverrides() {
        ContractInvocableAdapter adapter = new ContractInvocableAdapter(Fixtures.frozenContract(CryptoFixtures.keyPair()), "handle",
                delegate((invocation, context) -> ContractInvocationResult.success(Map.of())));

        ContractInvocationResult result = adapter.invoke(invocation(Map.of(ContractInvocableAdapter.CONTRACT_VERSION_KEY, "evil")), ExecutionContext.empty());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DCP_METADATA_OVERRIDE");
    }

    @Test
    void rejectsResultReservedMetadataOverrides() {
        ContractInvocableAdapter adapter = new ContractInvocableAdapter(Fixtures.frozenContract(CryptoFixtures.keyPair()), "handle",
                delegate((invocation, context) -> new ContractInvocationResult(true, Map.of(), null, null, Map.of(ContractInvocableAdapter.TRUST_TIER_KEY, "SELF"))));

        ContractInvocationResult result = adapter.invoke(invocation(Map.of()), ExecutionContext.empty());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DCP_METADATA_OVERRIDE");
    }

    private ContractInvocation invocation(Map<String, Object> metadata) {
        return new ContractInvocation("ignored", "op", "consumer", "provider", Map.of(), "corr", Map.of(), "hash", metadata);
    }

    private ContractInvocable delegate(Invoker invoker) {
        return new ContractInvocable() {
            @Override
            public String contractId() {
                return "delegate";
            }

            @Override
            public String contractVersion() {
                return "delegate-version";
            }

            @Override
            public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                return invoker.invoke(invocation, context);
            }
        };
    }

    private interface Invoker {
        ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context);
    }
}
