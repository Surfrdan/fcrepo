/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.api.responses;

import static com.google.common.collect.ImmutableMap.builder;
import static java.util.stream.Stream.of;
import static jakarta.ws.rs.core.MediaType.TEXT_HTML_TYPE;
import static org.apache.jena.graph.Node.ANY;
import static org.apache.jena.sparql.util.graph.GraphUtils.multiValueURI;
import static org.apache.jena.vocabulary.RDF.type;
import static org.fcrepo.http.commons.domain.RDFMediaType.TEXT_HTML_WITH_CHARSET;
import static org.fcrepo.http.commons.session.TransactionConstants.ATOMIC_ID_HEADER;
import static org.fcrepo.kernel.api.RdfCollectors.toModel;
import static org.fcrepo.kernel.api.RdfLexicon.ARCHIVAL_GROUP;
import static org.fcrepo.kernel.api.RdfLexicon.NON_RDF_SOURCE;
import static org.fcrepo.kernel.api.RdfLexicon.RDF_SOURCE;
import static org.fcrepo.kernel.api.RdfLexicon.REPOSITORY_ROOT;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import org.fcrepo.config.FedoraPropsConfig;
import org.fcrepo.config.OcflPropsConfig;
import org.fcrepo.config.SystemInfoConfig;
import org.fcrepo.http.api.FedoraLdp;
import org.fcrepo.http.commons.api.rdf.HttpIdentifierConverter;
import org.fcrepo.http.commons.responses.HtmlTemplate;
import org.fcrepo.http.commons.responses.RdfNamespacedStream;
import org.fcrepo.http.commons.responses.ViewHelpers;
import org.fcrepo.kernel.api.RdfLexicon;
import org.fcrepo.kernel.api.ReadOnlyTransaction;
import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.TransactionManager;
import org.fcrepo.kernel.api.exception.PathNotFoundException;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.Binary;
import org.fcrepo.kernel.api.models.FedoraResource;
import org.fcrepo.kernel.api.models.ResourceFactory;

import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.apache.velocity.tools.generic.EscapeTool;
import org.apache.velocity.tools.generic.FieldTool;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableMap;

/**
 * Simple HTML provider for RdfNamespacedStreams
 *
 * @author ajs6f
 * @author whikloj
 * @since Nov 19, 2013
 */
@Provider
@Produces({TEXT_HTML_WITH_CHARSET})
public class StreamingBaseHtmlProvider implements MessageBodyWriter<RdfNamespacedStream> {


    @Inject
    UriInfo uriInfo;

    @Inject
    TransactionManager transactionManager;

    @Inject
    HttpServletRequest request;

    @Inject
    private ResourceFactory resourceFactory;

    @Inject
    private OcflPropsConfig ocflPropsConfig;

    @Inject
    private FedoraPropsConfig fedoraPropsConfig;

    @Inject
    private SystemInfoConfig systemInfoConfig;

    private boolean autoVersioningEnabled;

    private Map<URI, HttpIdentifierConverter> identifierConverters = new HashMap<>();

    private Transaction readOnlyTx;

    private Transaction transaction() {
        if (request.getHeader(ATOMIC_ID_HEADER) != null) {
            return transactionManager.get(request.getHeader(ATOMIC_ID_HEADER));
        }
        return readOnlyTx;
    }

    private HttpIdentifierConverter identifierConverter() {
        return identifierConverters.computeIfAbsent(uriInfo.getBaseUri(),
                i -> new HttpIdentifierConverter(uriInfo.getBaseUriBuilder().clone().path(FedoraLdp.class)));
    }

    private static final EscapeTool escapeTool = new EscapeTool();

    private final VelocityEngine velocity = new VelocityEngine();

    /**
     * Location in the classpath where Velocity templates are to be found.
     */
    private static final String templatesLocation = "/views";

    /**
     * A map from String names for primary node types to the Velocity templates
     * that should be used for those node types.
     */
    private Map<String, Template> templatesMap;

    private static final String templateFilenameExtension = ".vsl";

    private static final String velocityPropertiesLocation =
            "/velocity.properties";

    private static final ViewHelpers VIEW_HELPERS = ViewHelpers.getInstance();

    private static final Logger LOGGER =
        getLogger(StreamingBaseHtmlProvider.class);

    @PostConstruct
    void init() throws IOException {
        LOGGER.trace("Velocity engine initializing...");
        final Properties properties = new Properties();
        final var velocityLog = fedoraPropsConfig.getVelocityLog().toString();
        autoVersioningEnabled = ocflPropsConfig.isAutoVersioningEnabled();
        LOGGER.debug("Setting Velocity runtime log: {}", velocityLog);
        properties.setProperty("runtime.log", velocityLog);

        final URL propertiesUrl =
                getClass().getResource(velocityPropertiesLocation);
        LOGGER.debug("Using Velocity configuration from {}", propertiesUrl);
        try (final InputStream propertiesStream = propertiesUrl.openStream()) {
            properties.load(propertiesStream);
        }
        velocity.init(properties);
        LOGGER.trace("Velocity engine initialized.");

        LOGGER.trace("Assembling a map of node primary types -> templates...");
        final ImmutableMap.Builder<String, Template> templatesMapBuilder = builder();

        of("fcr:versions", "fcr:fixity", "default")
            .forEach(key -> templatesMapBuilder.put(key, velocity.getTemplate(getTemplateLocation(key))));

        templatesMap = templatesMapBuilder
            .put(REPOSITORY_ROOT.toString(), velocity.getTemplate(getTemplateLocation("root")))
            .put(NON_RDF_SOURCE.toString(), velocity.getTemplate(getTemplateLocation("binary")))
            .put(RDF_SOURCE.toString(), velocity.getTemplate(getTemplateLocation("resource"))).build();

        LOGGER.trace("Assembled template map.");

        readOnlyTx = ReadOnlyTransaction.INSTANCE;

        LOGGER.trace("HtmlProvider initialization complete.");
    }

    @Override
    public void writeTo(final RdfNamespacedStream nsStream, final Class<?> type,
                        final Type genericType, final Annotation[] annotations,
                        final MediaType mediaType,
                        final MultivaluedMap<String, Object> httpHeaders,
                        final OutputStream entityStream) throws IOException {

        final Node subject = ViewHelpers.getContentNode(nsStream.stream.topic());

        final Model model = nsStream.stream.collect(toModel());
        model.setNsPrefixes(nsStream.namespaces);

        final Template nodeTypeTemplate = getTemplate(model, subject, Arrays.asList(annotations));

        final Context context = getContext(model, subject);

        final FedoraId fedoraID = FedoraId.create(identifierConverter().toInternalId(subject.toString()));
        try {
            final FedoraResource resource = getResourceFromSubject(fedoraID);
            context.put("isOriginalResource", (resource != null && resource.isOriginalResource()));
            context.put("isArchivalGroup", (resource != null && resource.getSystemTypes(false)
                    .contains(URI.create(ARCHIVAL_GROUP.getURI()))));
            context.put("isVersion", (resource != null && resource.isMemento()));
            context.put("isLDPNR", (resource != null && (resource instanceof Binary || !resource
                    .getDescribedResource().equals(resource))));
        } catch (final PathNotFoundException e) {
            final var baseId = FedoraId.create(fedoraID.getBaseId());
            if (fedoraID.isRepositoryRoot() || baseId.isRepositoryRoot()) {
                // We have requested the root resource or default ACL.
                context.put("isOriginalResource", true);
                context.put("isArchivalGroup", false);
                context.put("isVersion", false);
                context.put("isLDPNR", false);
            } else {
                throw new RepositoryRuntimeException(e.getMessage(), e);
            }
        }
        context.put("autoVersioningEnabled", autoVersioningEnabled);

        // the contract of MessageBodyWriter<T> is _not_ to close the stream
        // after writing to it
        final Writer outWriter = new OutputStreamWriter(entityStream, StandardCharsets.UTF_8);
        nodeTypeTemplate.merge(context, outWriter);
        outWriter.flush();
    }

    /**
     * Get a FedoraResource for the subject of the graph, if it exists.
     *
     * @param resourceId the FedoraId of the subject
     * @return FedoraResource if exists or null
     * @throws PathNotFoundException if the OCFL mapping is not found.
     */
    private FedoraResource getResourceFromSubject(final FedoraId resourceId) throws PathNotFoundException {
        return resourceFactory.getResource(transaction(), resourceId);
    }

    private Context getContext(final Model model, final Node subject) {
        final FieldTool fieldTool = new FieldTool();

        final Context context = new VelocityContext();
        final String[] baseUrl = uriInfo.getBaseUri().getPath().split("/");
        if (baseUrl.length > 0) {
            final String staticBaseUrl = String.join("/", Arrays.copyOf(baseUrl, baseUrl.length - 1));
            context.put("staticBaseUrl", staticBaseUrl);
        } else {
            context.put("staticBaseUrl", "/");
        }
        context.put("rdfLexicon", fieldTool.in(RdfLexicon.class));
        context.put("helpers", VIEW_HELPERS);
        context.put("esc", escapeTool);
        context.put("rdf", model.getGraph());

        context.put("fedoraProps", fedoraPropsConfig);
        context.put("model", model);
        context.put("subjects", model.listSubjects());
        context.put("nodeany", ANY);
        context.put("topic", subject);
        context.put("originalResource", VIEW_HELPERS.getOriginalResource(subject));
        context.put("uriInfo", uriInfo);
        context.put("fedoraCommit", systemInfoConfig.getGitCommit());
        context.put("fedoraVersion", systemInfoConfig.getImplementationVersion());
        return context;
    }

    private Template getTemplate(final Model rdf, final Node subject,
                                 final List<Annotation> annotations) {

        final String tplName = annotations.stream().filter(x -> x instanceof HtmlTemplate)
            .map(x -> ((HtmlTemplate) x).value()).filter(templatesMap::containsKey).findFirst()
            .orElseGet(() -> {
                final List<String> types = multiValueURI(rdf.getResource(subject.getURI()), type);
                if (types.contains(REPOSITORY_ROOT.toString())) {
                    return REPOSITORY_ROOT.toString();
                }
                return types.stream().filter(templatesMap::containsKey).findFirst().orElse("default");
            });
        LOGGER.debug("Using template: {}", tplName);
        return templatesMap.get(tplName);
    }

    @Override
    public boolean isWriteable(final Class<?> type, final Type genericType,
                               final Annotation[] annotations, final MediaType mediaType) {
        LOGGER.debug(
                "Checking to see if type: {} is serializable to mimeType: {}",
                type.getName(), mediaType);
        return isTextHtml(mediaType) && RdfNamespacedStream.class.isAssignableFrom(type);
    }

    @Override
    public long getSize(final RdfNamespacedStream t, final Class<?> type,
                        final Type genericType, final Annotation[] annotations,
                        final MediaType mediaType) {
        // we don't know in advance how large the result might be
        return -1;
    }

    private static String getTemplateLocation(final String nodeTypeName) {
        return templatesLocation + "/" +
            nodeTypeName.replace(':', '-') + templateFilenameExtension;
    }

    private static boolean isTextHtml(final MediaType mediaType) {
        return mediaType.getType().equals(TEXT_HTML_TYPE.getType()) &&
            mediaType.getSubtype().equals(TEXT_HTML_TYPE.getSubtype());
    }

}
