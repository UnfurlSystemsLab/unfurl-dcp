package com.unfurl.dcp.host;

import com.unfurl.dcp.contract.Party;
import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.dcp.spi.ContractInvocableFactory;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SPI (Abstract Factory): supplied by a component so a host can run it in-process, exactly one per component (HLD-C
 * §6.1 rule 3). It adapts the neutral host ports to the component's own ports; the host never imports component types.
 *
 * <p>A component declares its implementation by a single line naming the class in
 * {@code META-INF/services/com.unfurl.dcp.host.ComponentActivation} inside one of its pinned artifacts. The host reads
 * that file as data and never uses {@code java.util.ServiceLoader}. The class must be public with a public no-argument
 * constructor, and is instantiated only after every data check has passed for every component.
 */
public interface ComponentActivation {
    /** Returns the component's claim URI; must equal the packaged claim's identity. */
    URI claimUri();

    /** Returns the component's claim version; must equal the packaged claim's identity. */
    String claimVersion();

    /**
     * Returns each {@code owner=host} dependency capability this activation covers, with the host port types it needs
     * for it. An empty set means the activation provides the dependency itself from the resolved configuration. The
     * host refuses the component unless every {@code owner=host} need in its claim is listed and every listed type is
     * bound ({@code HOST_DEPENDENCY_UNMET}).
     */
    Map<String, Set<Class<?>>> hostDependencies();

    /**
     * Returns the factory the broker calls when it accepts one operation.
     *
     * @param context the verified contract, validated binding, bound host ports and effective configuration
     */
    ContractInvocableFactory factory(ActivationContext context);

    /**
     * Returns this component's registrar of a host's shape, for example a tool registrar for an agent host.
     *
     * @param shape    the registrar interface the host exposes operations through
     * @param consumer the host's own identity, which invocations carry as the consumer
     * @return the registrar, or empty when the component does not support that shape
     */
    <R extends CapabilityRegistrar> Optional<R> registrar(Class<R> shape, Party consumer);
}
