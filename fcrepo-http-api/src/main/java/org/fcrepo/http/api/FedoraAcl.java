/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.api;

import io.micrometer.core.annotation.Timed;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.shared.JenaException;

import org.fcrepo.config.AuthPropsConfig;
import org.fcrepo.http.commons.domain.PATCH;
import org.fcrepo.http.commons.domain.RDFMediaType;
import org.fcrepo.http.commons.responses.RdfNamespacedStream;
import org.fcrepo.kernel.api.RdfStream;
import org.fcrepo.kernel.api.exception.AccessDeniedException;
import org.fcrepo.kernel.api.exception.ItemNotFoundException;
import org.fcrepo.kernel.api.exception.PathNotFoundException;
import org.fcrepo.kernel.api.exception.PathNotFoundRuntimeException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.FedoraResource;
import org.fcrepo.kernel.api.models.WebacAcl;
import org.fcrepo.kernel.api.rdf.DefaultRdfStream;
import org.fcrepo.kernel.api.services.WebacAclService;
import org.slf4j.Logger;
import org.springframework.context.annotation.Scope;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;

import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.MediaType.valueOf;
import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.created;
import static jakarta.ws.rs.core.Response.noContent;
import static jakarta.ws.rs.core.Response.ok;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.jena.rdf.model.ModelFactory.createDefaultModel;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.apache.jena.riot.Lang.TTL;
import static org.apache.jena.riot.WebContent.contentTypeSPARQLUpdate;
import static org.fcrepo.http.commons.domain.RDFMediaType.JSON_LD;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3_ALT2_WITH_CHARSET;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3_WITH_CHARSET;
import static org.fcrepo.http.commons.domain.RDFMediaType.NTRIPLES;
import static org.fcrepo.http.commons.domain.RDFMediaType.RDF_XML;
import static org.fcrepo.http.commons.domain.RDFMediaType.TEXT_HTML_WITH_CHARSET;
import static org.fcrepo.http.commons.domain.RDFMediaType.TEXT_PLAIN_WITH_CHARSET;
import static org.fcrepo.http.commons.domain.RDFMediaType.TURTLE_WITH_CHARSET;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * @author lsitu
 * @author peichman
 * @since 4/20/18
 */
@Timed
@Scope("request")
@Path("/{path: (.+/)?}fcr:acl")
public class FedoraAcl extends ContentExposingResource {

    private static final Logger LOGGER = getLogger(FedoraAcl.class);

    private static final String ROOT_AUTHORIZATION_LOCATION = "/root-authorization.ttl";

    @Context protected Request request;
    @Context protected HttpServletResponse servletResponse;
    @Context protected UriInfo uriInfo;

    @PathParam("path") protected String externalPath;

    @Inject
    private AuthPropsConfig authPropsConfig;

    @Inject
    private WebacAclService webacAclService;

    /**
     * Default JAX-RS entry point
     */
    public FedoraAcl() {
        super();
    }

    /**
     * PUT to create FedoraWebacACL resource.
     * @param requestContentType The content type of the resource body
     * @param requestBodyStream  The request body as stream
     * @return the response for a request to create a Fedora WebAc acl
     */
    @PUT
    public Response createFedoraWebacAcl(@HeaderParam(CONTENT_TYPE) final MediaType requestContentType,
                                         final InputStream requestBodyStream) {

        if (resource().isAcl() || resource().isMemento()) {
            throw new BadRequestException("ACL resource creation is not allowed for resource " + resource().getId());
        }
        LOGGER.info("PUT acl resource '{}'", externalPath());

        final FedoraId aclId = identifierConverter().pathToInternalId(externalPath()).asAcl();
        final boolean exists = doesResourceExist(transaction(), aclId, false);

        try {
            final MediaType contentType =
                    requestContentType == null ?
                            RDFMediaType.TURTLE_TYPE : valueOf(getSimpleContentType(requestContentType));

            if (isRdfContentType(contentType.toString())) {
                final Model model = httpRdfService.bodyToInternalModel(aclId,
                        requestBodyStream, contentType, identifierConverter(), hasLenientPreferHeader());
                doInDbTxWithRetry(() -> {
                    if (exists) {
                        replacePropertiesService.perform(transaction(), getUserPrincipal(), aclId, model);
                    } else {
                        webacAclService.create(transaction(), aclId, getUserPrincipal(), model);
                    }
                    transaction().commitIfShortLived();
                });
            } else {
                throw new BadRequestException("Content-Type (" + requestContentType + ") is invalid." +
                        " Try text/turtle or other RDF compatible type.");
            }

            try {
                final var aclResource = getFedoraResource(transaction(), aclId);
                addCacheControlHeaders(servletResponse, aclResource, transaction());
                final URI location = getUri(aclResource);
                if (!exists) {
                    return created(location).build();
                } else {
                    return noContent().location(location).build();
                }
            } catch (final PathNotFoundException e) {
                throw new PathNotFoundRuntimeException(e.getMessage(), e);
            }
        } finally {
            transaction().releaseResourceLocksIfShortLived();
        }
    }

    /**
     * PATCH to update an FedoraWebacACL resource using SPARQL-UPDATE
     *
     * @param requestBodyStream the request body stream
     * @return 204
     * @throws IOException if IO exception occurred
     */
    @PATCH
    @Consumes({ contentTypeSPARQLUpdate })
    public Response updateSparql(final InputStream requestBodyStream)
        throws IOException, ItemNotFoundException {
        hasRestrictedPath(externalPath);

        if (null == requestBodyStream) {
            throw new BadRequestException("SPARQL-UPDATE requests must have content!");
        }

        final FedoraId originalId = identifierConverter().pathToInternalId(externalPath());
        final FedoraId aclId = originalId.asAcl();
        final FedoraResource aclResource;
        try {
             aclResource = getFedoraResource(transaction(), aclId);
        } catch (final PathNotFoundException exc) {
            if (originalId.isRepositoryRoot()) {
                throw new ClientErrorException("The default root ACL is system generated and cannot be modified. " +
                        "To override the default root ACL you must PUT a user-defined ACL to this endpoint.",
                        CONFLICT);
            }
            throw new ItemNotFoundException("not found");
        }

        try {
            //final String requestBody = IOUtils.toString(requestBodyStream, UTF_8);
            // TODO: use a streaming parser instead of reading the entire body into memory,
            // but IOUtils hangs since the Jersey change.
            final byte[] body = requestBodyStream.readAllBytes();
            final String requestBody = new String(body);
            if (isBlank(requestBody)) {
                throw new BadRequestException("SPARQL-UPDATE requests must have content!");
            }

            evaluateRequestPreconditions(request, servletResponse, aclResource, transaction());

            LOGGER.info("PATCH for '{}'", externalPath);
            final String newRequest = httpRdfService.patchRequestToInternalString(aclResource.getFedoraId(),
                    requestBody, identifierConverter());
            LOGGER.debug("PATCH request translated to '{}'", newRequest);

            doInDbTxWithRetry(() -> {
                patchResourcewithSparql(aclResource, newRequest);
                transaction().commitIfShortLived();
            });
            addCacheControlHeaders(servletResponse, aclResource, transaction());

            return noContent().build();
        } catch (final IllegalArgumentException iae) {
            throw new BadRequestException(iae.getMessage());
        } catch (final AccessDeniedException e) {
            throw e;
        } catch (final RuntimeException ex) {
            final Throwable cause = ex.getCause();
            if (cause instanceof PathNotFoundRuntimeException) {
                // the sparql update referred to a repository resource that doesn't exist
                throw new BadRequestException(cause.getMessage());
            }
            throw ex;
        } finally {
            transaction().releaseResourceLocksIfShortLived();
        }
    }

    @Override
    protected String externalPath() {
        return externalPath;
    }

    /**
     * GET to retrieve the ACL resource.
     *
     * @return a binary or the triples for the specified node
     * @throws IOException if IO exception occurred
     */
    @GET
    @Produces({ TURTLE_WITH_CHARSET + ";qs=1.0", JSON_LD + ";qs=0.8",
                N3_WITH_CHARSET, N3_ALT2_WITH_CHARSET, RDF_XML, NTRIPLES, TEXT_PLAIN_WITH_CHARSET,
                TEXT_HTML_WITH_CHARSET })
    public Response getResource()
            throws IOException, ItemNotFoundException {

        LOGGER.info("GET resource '{}'", externalPath());

        final FedoraId originalId = identifierConverter().pathToInternalId(externalPath());
        final FedoraId aclId = originalId.asAcl();
        final boolean exists = doesResourceExist(transaction(), aclId, false);

        if (!exists) {
            if (originalId.isRepositoryRoot()) {
                final String aclUri = identifierConverter().toExternalId(aclId.getFullId());

                final RdfStream defaultRdfStream = DefaultRdfStream.fromModel(createResource(aclUri).asNode(),
                    getDefaultAcl(aclUri, authPropsConfig.getRootAuthAclPath()));
                final RdfStream rdfStream = httpRdfService.bodyToExternalStream(aclUri,
                        defaultRdfStream, identifierConverter());
                final var output = new RdfNamespacedStream(
                        rdfStream, namespaceRegistry.getNamespaces());
                return ok(output).build();
            }

            throw new ItemNotFoundException(String.format("No ACL found at %s", externalPath));
        }

        final WebacAcl aclResource = webacAclService.find(transaction(), aclId);
        checkCacheControlHeaders(request, servletResponse, aclResource, transaction());

        LOGGER.info("GET resource '{}'", externalPath);
        addResourceHttpHeaders(aclResource);
        return getContent(getChildrenLimit(), aclResource);

    }

    /**
     * Deletes an object.
     *
     * @return response
     */
    @DELETE
    public Response deleteObject() throws ItemNotFoundException {

        hasRestrictedPath(externalPath);
        LOGGER.info("Delete resource '{}'", externalPath);

        final FedoraId originalId = identifierConverter().pathToInternalId(externalPath());
        final FedoraId aclId = originalId.asAcl();
        try {
            final var aclResource = getFedoraResource(transaction(), aclId);
            doInDbTxWithRetry(() -> {
                deleteResourceService.perform(transaction(), aclResource, getUserPrincipal());
                transaction().commitIfShortLived();
            });
        } catch (final PathNotFoundException exc) {
            if (originalId.isRepositoryRoot()) {
                throw new ClientErrorException("The default root ACL is system generated and cannot be deleted. " +
                        "To override the default root ACL you must PUT a user-defined ACL to this endpoint.",
                        CONFLICT);
            }
            throw new PathNotFoundRuntimeException(exc.getMessage(), exc);
        } finally {
            transaction().releaseResourceLocksIfShortLived();
        }
        return noContent().build();
    }

    /**
     * Retrieve the default root ACL from a user specified location if it exists,
     * otherwise the one provided by Fedora will be used.
     * @param baseUri the URI of the default ACL
     * @param customRootAcl the path to a custom root acl to use, optional
     * @return Model the rdf model of the default root ACL
     */
    public static Model getDefaultAcl(final String baseUri, final java.nio.file.Path customRootAcl) {
        final Model model = createDefaultModel();

        if (customRootAcl != null && Files.isRegularFile(customRootAcl)) {
            try {
                LOGGER.debug("Getting root authorization from file: {}", customRootAcl);

                try (final var stream = new BufferedInputStream(Files.newInputStream(customRootAcl))) {
                    RDFDataMgr.read(model, stream, baseUri, RDFLanguages.pathnameToLang(customRootAcl.toString()));
                }

                return model;
            } catch (final JenaException | IOException ex) {
                throw new RuntimeException("Error parsing the default root ACL " + customRootAcl + ".", ex);
            }
        }

        try (final InputStream is = FedoraAcl.class.getResourceAsStream(ROOT_AUTHORIZATION_LOCATION)) {
            LOGGER.debug("Getting root ACL from classpath: {}", ROOT_AUTHORIZATION_LOCATION);

            return model.read(is, baseUri, TTL.getName());
        } catch (final IOException ex) {
            throw new RuntimeException("Error reading the default root Acl " + ROOT_AUTHORIZATION_LOCATION + ".", ex);
        } catch (final JenaException ex) {
            throw new RuntimeException("Error parsing the default root ACL " + ROOT_AUTHORIZATION_LOCATION + ".", ex);
        }
    }
}
