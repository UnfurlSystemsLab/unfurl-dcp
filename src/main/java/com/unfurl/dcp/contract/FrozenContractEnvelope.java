package com.unfurl.dcp.contract;

import com.unfurl.dcp.trust.SignedContract;

public record FrozenContractEnvelope(CompositionContract contract, SignedContract signedContract) {
}
