package com.unfurl.dcp.spi;

import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.policy.ExecutionContext;

public interface CapabilityRegistrar {
    void register(String capabilityName, ContractInvocable invocable, ExecutionContext context);

    void unregister(String capabilityName, ExecutionContext context);
}
