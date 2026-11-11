package com.unfurl.dcp.host;

import com.unfurl.dcp.contract.Party;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test registrar the synthetic component hands its host. It keeps registrations in a map and can be told, through
 * {@link HostedSwitches}, to fail the way a faulty registrar would: register and then throw, or refuse to unregister.
 */
public final class RecordingToolRegistrar implements TestToolRegistrar {
    private final Map<String, ContractInvocable> tools = new ConcurrentHashMap<>();
    private final Party consumer;

    /** @param consumer the host identity the component was activated for */
    public RecordingToolRegistrar(Party consumer) {
        this.consumer = consumer;
        HostedSwitches.registrars.add(this);
    }

    /** Stores the registration; throws afterwards when the capability is switched to fail. */
    @Override public void register(String capabilityName, ContractInvocable invocable, ExecutionContext context) {
        tools.put(capabilityName, invocable);
        if (capabilityName.equals(HostedSwitches.failRegisterFor)) HostedSwitches.fail("register boom");
    }

    /** Removes the registration (idempotent); throws without removing when the capability is switched to fail. */
    @Override public void unregister(String capabilityName, ExecutionContext context) {
        if (capabilityName.equals(HostedSwitches.failUnregisterFor)) HostedSwitches.fail("unregister boom");
        tools.remove(capabilityName);
    }

    @Override public Map<String, ContractInvocable> tools() {
        return Map.copyOf(tools);
    }

    /** Returns the host identity this registrar was created for. */
    public Party consumer() {
        return consumer;
    }
}
