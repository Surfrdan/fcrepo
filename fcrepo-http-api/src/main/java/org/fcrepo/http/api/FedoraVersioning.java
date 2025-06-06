/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.api;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.annotation.Timed;

import org.fcrepo.http.commons.responses.HtmlTemplate;
import org.fcrepo.http.commons.responses.LinkFormatStream;
import org.fcrepo.kernel.api.exception.CannotCreateMementoException;
import org.fcrepo.kernel.api.exception.InvalidChecksumException;
import org.fcrepo.kernel.api.exception.MementoDatetimeFormatException;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.exception.TombstoneException;
import org.fcrepo.kernel.api.models.FedoraResource;
import org.slf4j.Logger;
import org.springframework.context.annotation.Scope;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.Link.Builder;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static jakarta.ws.rs.core.Response.Status.METHOD_NOT_ALLOWED;
import static jakarta.ws.rs.core.Response.ok;
import static jakarta.ws.rs.core.Response.status;

import static org.fcrepo.http.commons.domain.RDFMediaType.APPLICATION_LINK_FORMAT;
import static org.fcrepo.http.commons.domain.RDFMediaType.JSON_LD;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3_ALT2_WITH_CHARSET;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3_WITH_CHARSET;
import static org.fcrepo.http.commons.domain.RDFMediaType.NTRIPLES;
import static org.fcrepo.http.commons.domain.RDFMediaType.RDF_XML;
import static org.fcrepo.http.commons.domain.RDFMediaType.TEXT_HTML_WITH_CHARSET;
import static org.fcrepo.http.commons.domain.RDFMediaType.TEXT_PLAIN_WITH_CHARSET;
import static org.fcrepo.http.commons.domain.RDFMediaType.TURTLE_WITH_CHARSET;
import static org.fcrepo.kernel.api.FedoraTypes.FCR_VERSIONS;
import static org.fcrepo.kernel.api.services.VersionService.MEMENTO_RFC_1123_FORMATTER;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * @author cabeer
 * @since 9/25/14
 */
@Timed
@Scope("request")
@Path("/{path: .*}/fcr:versions")
public class FedoraVersioning extends ContentExposingResource {

    private static final Logger LOGGER = getLogger(FedoraVersioning.class);

    @VisibleForTesting
    public static final String MEMENTO_DATETIME_HEADER = "Memento-Datetime";

    @Context protected Request request;
    @Context protected HttpServletResponse servletResponse;
    @Context protected UriInfo uriInfo;

    @PathParam("path") protected String externalPath;


    /**
     * Default JAX-RS entry point
     */
    public FedoraVersioning() {
        super();
    }

    /**
     * Create a new FedoraNodes instance for a given path
     * @param externalPath the external path
     */
    @VisibleForTesting
    public FedoraVersioning(final String externalPath) {
        this.externalPath = externalPath;
    }

    /**
     * Create a new version of a resource. If a memento-datetime header is provided, then the new version will be
     * based off the provided body using that datetime. If one was not provided, then a version is created based off
     * the current version of the resource.
     *
     * @return response
     * @throws InvalidChecksumException thrown if one of the provided digests does not match the content
     * @throws MementoDatetimeFormatException if the header value of memento-datetime is not RFC-1123 format
     */
    @POST
    public Response addVersion() {

        if (headers.getHeaderString("Slug") != null) {
            throw new BadRequestException("Slug header is no longer supported for versioning label.");
        }

        if (headers.getHeaderString(MEMENTO_DATETIME_HEADER) != null) {
            throw new CannotCreateMementoException(MEMENTO_DATETIME_HEADER +
                    " header is no longer supported on versioning.");
        }

        final var transaction = transaction();

        if (!transaction.isShortLived()) {
            throw new BadRequestException("Version creation is not allowed within transactions.");
        }

        final var resource = resource();

        try {
            LOGGER.debug("Request to create version for <{}>", externalPath);

            doInDbTxWithRetry(() -> {
                versionService.createVersion(transaction, resource.getFedoraId(), getUserPrincipal());

                // need to commit the transaction before loading the memento otherwise it won't exist
                transaction.commitIfShortLived();
            });

            final var versions = reloadResource().getTimeMap().getChildren().collect(Collectors.toList());

            if (versions.isEmpty()) {
                throw new RepositoryRuntimeException(String.format("Failed to create a version for %s", externalPath));
            }

            final var memento = versions.get(versions.size() - 1);

            return createUpdateResponse(memento, true);
        } catch (final Exception e) {
            checkForInsufficientStorageException(e, e);
            return null; // not reachable
        } finally {
            transaction.releaseResourceLocksIfShortLived();
        }
    }

    /**
     * Get the list of versions for the object
     *
     * @param acceptValue the rdf media-type
     * @return List of versions for the object as RDF
     * @throws IOException in case of error extracting content
     */
    @GET
    @HtmlTemplate(value = "fcr:versions")
    @Produces({ TURTLE_WITH_CHARSET + ";qs=1.0", JSON_LD + ";qs=0.8",
        N3_WITH_CHARSET, N3_ALT2_WITH_CHARSET, RDF_XML, NTRIPLES, TEXT_PLAIN_WITH_CHARSET,
        TEXT_HTML_WITH_CHARSET, APPLICATION_LINK_FORMAT })
    public Response getVersionList(@HeaderParam("Accept") final String acceptValue) throws IOException {

        FedoraResource theTimeMap;
        try {
            theTimeMap = resource().getTimeMap();
        } catch (TombstoneException e) {
            // We return timemaps for deleted resources, so get the original resource from the Tombstone exception.
            theTimeMap = e.getFedoraResource().getOriginalResource().getTimeMap();
        }
        checkCacheControlHeaders(request, servletResponse, theTimeMap, transaction());

        LOGGER.debug("GET resource '{}'", externalPath());

        addResourceHttpHeaders(theTimeMap);

        if (acceptValue != null && acceptValue.equalsIgnoreCase(APPLICATION_LINK_FORMAT)) {
            final String extUrl = identifierConverter().toDomain(externalPath());

            final URI parentUri = URI.create(extUrl);
            final Set<Link> versionLinks = new HashSet<>();
            versionLinks.add(Link.fromUri(parentUri).rel("original").build());
            versionLinks.add(Link.fromUri(parentUri).rel("timegate").build());
            // So we don't collect the children twice, store them in an array.
            final FedoraResource[] children = theTimeMap.getChildren()
                                                        .filter(FedoraResource::isMemento)
                                                        .toArray(FedoraResource[]::new);

            Arrays.stream(children).forEach(t -> {
                final URI childUri = getUri(t);
                versionLinks.add(Link.fromUri(childUri).rel("memento")
                                     .param("datetime", MEMENTO_RFC_1123_FORMATTER.format(t.getMementoDatetime()))
                                     .build());
            });
            // Based on the dates of the above mementos, add the range to the below link.
            final Instant[] mementos = Arrays.stream(children).map(FedoraResource::getMementoDatetime)
                .sorted(Comparator.naturalOrder())
                .toArray(Instant[]::new);
            final Builder linkBuilder =
                Link.fromUri(parentUri + "/" + FCR_VERSIONS).rel("self").type(APPLICATION_LINK_FORMAT);
            if (mementos.length >= 2) {
                // There are 2 or more Mementos so make a range.
                linkBuilder.param("from", MEMENTO_RFC_1123_FORMATTER.format(mementos[0].atZone(ZoneId.of("UTC"))));
                linkBuilder.param("until",
                    MEMENTO_RFC_1123_FORMATTER.format(mementos[mementos.length - 1].atZone(ZoneId.of("UTC"))));
            }
            versionLinks.add(linkBuilder.build());
            return ok(new LinkFormatStream(versionLinks.stream())).build();
        } else {
            return getContent(getChildrenLimit(), theTimeMap);
        }
    }

    /**
     * Outputs information about the supported HTTP methods, etc.
     *
     * @return the information about the supported HTTP methods, etc.
     */
    @OPTIONS
    public Response options() {
        final FedoraResource theTimeMap = resource().getTimeMap();
        LOGGER.info("OPTIONS for '{}'", externalPath);
        addResourceHttpHeaders(theTimeMap);
        return ok().build();
    }

    /**
     * Can't delete TimeMaps
     *
     * @return the response to a delete request.
     */
    @DELETE
    @Produces({TEXT_PLAIN_WITH_CHARSET})
    public Response delete() {
        final FedoraResource theTimeMap = resource().getTimeMap();
        addResourceHttpHeaders(theTimeMap);
        final String message = "Timemaps are deleted with their associated resource.";
        return status(METHOD_NOT_ALLOWED).entity(message).type(TEXT_PLAIN_WITH_CHARSET).build();
    }

    @Override
    protected String externalPath() {
        return externalPath;
    }
}
