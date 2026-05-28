package com.unfurl.dcp.spi;

import com.unfurl.dcp.contract.Binding;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.policy.ExecutionContext;

public interface ContractInvocableFactory {
    ContractInvocable create(CompositionContract contract, Binding binding, ExecutionContext context);
}
