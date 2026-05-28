package com.unfurl.dcp.testing;

import com.unfurl.dcp.contract.Binding;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.spi.ContractInvocableFactory;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;

public final class EchoContractInvocableFactory implements ContractInvocableFactory {
    @Override
    public ContractInvocable create(CompositionContract contract, Binding binding, ExecutionContext context) {
        return new ContractInvocable() {
            @Override
            public String contractId() {
                return contract.contractId().toString();
            }

            @Override
            public String contractVersion() {
                return contract.contractVersion();
            }

            @Override
            public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                return ContractInvocationResult.success(invocation.input());
            }
        };
    }
}
