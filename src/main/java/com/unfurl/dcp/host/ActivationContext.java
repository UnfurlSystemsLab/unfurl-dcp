package com.unfurl.dcp.host;

import com.unfurl.dcp.componentconfig.EffectiveConfiguration;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;

/**
 * What a host hands a component's activation for one admitted operation (LLD, <em>In-Process Component Host</em>).
 * Everything in it has passed the host's checks before any component code runs: the contract is verified, the runtime
 * binding is validated against it, and the configuration is the validated merge of packaged defaults and deployment
 * values. All of it is immutable and uses only shared or JDK types.
 */
public interface ActivationContext {
    /** Returns the verified contract for the operation. */
    CompositionContract contract();

    /** Returns the runtime binding, validated against the contract. */
    RuntimeBinding runtimeBinding();

    /** Returns the neutral host services the host has bound. */
    HostPorts host();

    /** Returns the effective configuration for this admission. */
    EffectiveConfiguration configuration();
}
