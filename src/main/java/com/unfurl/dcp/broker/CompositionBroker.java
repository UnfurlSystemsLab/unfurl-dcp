package com.unfurl.dcp.broker;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.dcp.spi.ContractInvocableFactory;
import com.unfurl.substrate.policy.ExecutionContext;

public interface CompositionBroker {
    Disposition present(Claim claim, ExecutionContext context);

    RegistrationHandle accept(
            Disposition disposition,
            CapabilityRegistrar registrar,
            ContractInvocableFactory invocableFactory,
            ExecutionContext context
    );

    void revoke(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context);

    void invalidate(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context);
}
