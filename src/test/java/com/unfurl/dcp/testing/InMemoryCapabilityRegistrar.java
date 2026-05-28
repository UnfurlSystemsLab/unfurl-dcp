package com.unfurl.dcp.testing;

import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryCapabilityRegistrar implements CapabilityRegistrar {
    private final Map<String, ContractInvocable> registrations = new LinkedHashMap<>();

    @Override
    public void register(String capabilityName, ContractInvocable invocable, ExecutionContext context) {
        registrations.put(capabilityName, invocable);
    }

    @Override
    public void unregister(String capabilityName, ExecutionContext context) {
        registrations.remove(capabilityName);
    }

    public Optional<ContractInvocable> registration(String capabilityName) {
        return Optional.ofNullable(registrations.get(capabilityName));
    }
}
