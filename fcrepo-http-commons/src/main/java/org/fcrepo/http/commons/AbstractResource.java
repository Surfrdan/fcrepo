/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.commons;

import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;

import org.fcrepo.config.FedoraPropsConfig;
import org.fcrepo.kernel.api.models.ResourceFactory;
import org.fcrepo.kernel.api.services.VersionService;
import org.fcrepo.kernel.api.services.functions.ConfigurableHierarchicalSupplier;
import org.fcrepo.kernel.api.services.functions.UniqueValueSupplier;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Superclass for Fedora JAX-RS Resources, providing convenience fields and methods.
 *
 * @author ajs6f
 */
public class AbstractResource {

    @Inject
    protected FedoraPropsConfig fedoraPropsConfig;

    /**
     * Useful for constructing URLs
     */
    @Context
    protected UriInfo uriInfo;

    /**
     * For getting user agent
     */
    @Context
    protected HttpHeaders headers;

    @Inject
    protected ResourceFactory resourceFactory;

    /**
     * The version service
     */
    @Inject
    protected VersionService versionService;

    /**
     * A resource that can mint new Fedora PIDs.
     */
    @Autowired(required = false)
    protected Supplier<String> pidMinter;

    protected UniqueValueSupplier defaultPidMinter = new ConfigurableHierarchicalSupplier();

}
