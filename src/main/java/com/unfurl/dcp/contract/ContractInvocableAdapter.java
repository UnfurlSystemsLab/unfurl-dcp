package com.unfurl.dcp.contract;

import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ContractInvocableAdapter implements ContractInvocable {
    public static final String CONTRACT_VERSION_KEY = "dcp.contractVersion";
    public static final String TRUST_TIER_KEY = "dcp.trustTier";
    public static final String REGISTRATION_HANDLE_KEY = "dcp.registrationHandle";

    private static final Set<String> RESERVED_KEYS = Set.of(CONTRACT_VERSION_KEY, TRUST_TIER_KEY, REGISTRATION_HANDLE_KEY);

    private final FrozenContract frozenContract;
    private final String registrationHandle;
    private final ContractInvocable delegate;

    public ContractInvocableAdapter(FrozenContract frozenContract, String registrationHandle, ContractInvocable delegate) {
        this.frozenContract = frozenContract;
        this.registrationHandle = registrationHandle;
        this.delegate = delegate;
    }

    @Override
    public String contractId() {
        return frozenContract.contract().contractId().toString();
    }

    @Override
    public String contractVersion() {
        return frozenContract.contract().contractVersion();
    }

    @Override
    public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>(invocation.metadata());
        if (metadata.keySet().stream().anyMatch(RESERVED_KEYS::contains)) {
            return ContractInvocationResult.failure("DCP_METADATA_OVERRIDE", "reserved DCP invocation metadata cannot be overridden");
        }
        metadata.putAll(reservedMetadata());
        ContractInvocation tagged = new ContractInvocation(
                contractId(),
                invocation.operation(),
                invocation.consumerComponent(),
                invocation.providerComponent(),
                invocation.input(),
                invocation.correlationId() == null ? correlationId(context) : invocation.correlationId(),
                invocation.traceContext(),
                invocation.integrityHash(),
                metadata);
        ContractInvocationResult result = delegate.invoke(tagged, context);
        Map<String, Object> resultMetadata = new LinkedHashMap<>(result.metadata());
        for (String key : RESERVED_KEYS) {
            if (resultMetadata.containsKey(key)) {
                return ContractInvocationResult.failure("DCP_METADATA_OVERRIDE", "reserved DCP result metadata cannot be overridden");
            }
        }
        resultMetadata.putAll(reservedMetadata());
        return new ContractInvocationResult(result.success(), result.output(), result.errorCode(), result.errorMessage(), resultMetadata);
    }

    private Map<String, Object> reservedMetadata() {
        return Map.of(
                CONTRACT_VERSION_KEY, contractVersion(),
                TRUST_TIER_KEY, frozenContract.contract().trust().tier().name(),
                REGISTRATION_HANDLE_KEY, registrationHandle
        );
    }

    private String correlationId(ExecutionContext context) {
        return context == null ? null : context.correlationId();
    }
}
