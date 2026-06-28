package com.unfurl.dcp.contract;

import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Adapter: wraps a host-provided ContractInvocable with DCP contract metadata and reserved-key
 * enforcement. It is the runtime bridge between a frozen contract registration and the substrate
 * invocation API, ensuring every call/result carries contract version, trust tier, and registration
 * handle without allowing delegates to forge those fields.
 */
public final class ContractInvocableAdapter implements ContractInvocable {
    public static final String CONTRACT_VERSION_KEY = "dcp.contractVersion";
    public static final String TRUST_TIER_KEY = "dcp.trustTier";
    public static final String REGISTRATION_HANDLE_KEY = "dcp.registrationHandle";

    private static final Set<String> RESERVED_KEYS = Set.of(CONTRACT_VERSION_KEY, TRUST_TIER_KEY, REGISTRATION_HANDLE_KEY);

    private final FrozenContract frozenContract;
    private final String registrationHandle;
    private final ContractInvocable delegate;

    /**
     * Construct an adapter for one accepted registration. The frozen contract supplies immutable
     * contract/trust metadata, the handle identifies the broker registration, and the delegate owns
     * the actual host capability execution.
     */
    public ContractInvocableAdapter(FrozenContract frozenContract, String registrationHandle, ContractInvocable delegate) {
        this.frozenContract = frozenContract;
        this.registrationHandle = registrationHandle;
        this.delegate = delegate;
    }

    /**
     * Return the frozen DCP contract id exposed through the substrate contract interface, not the
     * delegate's own id, so callers invoke against the accepted contract identity.
     */
    @Override
    public String contractId() {
        return frozenContract.contract().contractId().toString();
    }

    /**
     * Return the frozen DCP contract version so invocation dispatch and audit stay pinned to the
     * contract version accepted by the broker.
     */
    @Override
    public String contractVersion() {
        return frozenContract.contract().contractVersion();
    }

    /**
     * Invocation adapter: rejects reserved metadata overrides, injects DCP metadata, delegates the
     * call, and applies the same reserved-key protection to the result. If the invocation lacks a
     * correlation id, the execution context supplies the audit correlation path.
     */
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

    /**
     * Reserved metadata factory: centralizes DCP-owned keys so invocation and result tagging remain
     * identical and delegates cannot partially override the audit envelope.
     */
    private Map<String, Object> reservedMetadata() {
        return Map.of(
                CONTRACT_VERSION_KEY, contractVersion(),
                TRUST_TIER_KEY, frozenContract.contract().trust().tier().name(),
                REGISTRATION_HANDLE_KEY, registrationHandle
        );
    }

    /**
     * Context helper: extracts the host correlation id when the invocation payload did not carry one,
     * preserving end-to-end audit stitching without inventing ids inside DCP.
     */
    private String correlationId(ExecutionContext context) {
        return context == null ? null : context.correlationId();
    }
}
