package com.unfurl.dcp.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata annotation: marks a Java type as an implementation of a DCP capability without making
 * the annotation the protocol source of truth. DCP claims, composition contracts, runtime bindings,
 * catalog metadata, and the SPI interfaces remain authoritative; scanners may use this annotation
 * only to validate or discover implementation classes.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DcpCapability {
    /**
     * Canonical DCP capability name, such as {@code agent.run} or {@code workflow.execute}.
     */
    String name();

    /**
     * Optional semantic version of the capability implementation declared by the annotated type.
     */
    String version() default "";

    /**
     * Neutral runtime surfaces exposed by the annotated type; the default is the DCP invocable SPI.
     */
    String[] surfaces() default {"contract.invocable"};
}
