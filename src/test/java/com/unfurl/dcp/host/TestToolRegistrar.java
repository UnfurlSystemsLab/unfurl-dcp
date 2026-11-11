package com.unfurl.dcp.host;

import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.substrate.composition.ContractInvocable;

import java.util.Map;

/**
 * Test registrar shape: what a host product would define for its callers (Foundry's is a tool registrar). It lives in
 * a shared API package, so the host test and the synthetic component compiled at test time see one copy of it.
 */
public interface TestToolRegistrar extends CapabilityRegistrar {
    /** Returns the currently registered operations by capability name. */
    Map<String, ContractInvocable> tools();
}
