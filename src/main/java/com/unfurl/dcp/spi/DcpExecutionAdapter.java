package com.unfurl.dcp.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata annotation: identifies a host-runtime adapter that maps an accepted DCP capability onto
 * a native execution surface. It documents and validates adapter intent while keeping the accepted
 * DCP contract and the {@link ContractInvocableFactory}/{@link CapabilityRegistrar} SPI as the
 * execution source of truth.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DcpExecutionAdapter {
    /**
     * Canonical DCP capability adapted by this type, for example {@code agent.run}.
     */
    String capability();

    /**
     * Host-native surface produced by this adapter, for example {@code substrate.node-executor}.
     */
    String surface();
}
