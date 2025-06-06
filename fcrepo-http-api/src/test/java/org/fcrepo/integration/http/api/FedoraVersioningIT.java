/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.integration.http.api;

import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.jena.graph.Node.ANY;
import static org.apache.jena.graph.NodeFactory.createLiteral;
import static org.apache.jena.graph.NodeFactory.createURI;
import static org.apache.jena.rdf.model.ModelFactory.createDefaultModel;
import static org.apache.jena.rdf.model.ResourceFactory.createProperty;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.apache.jena.vocabulary.DC_11.title;
import static org.apache.jena.vocabulary.RDF.type;
import static org.fcrepo.http.api.FedoraLdp.ACCEPT_DATETIME;
import static org.fcrepo.http.api.FedoraVersioning.MEMENTO_DATETIME_HEADER;
import static org.fcrepo.http.commons.domain.RDFMediaType.APPLICATION_LINK_FORMAT;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3;
import static org.fcrepo.http.commons.domain.RDFMediaType.POSSIBLE_RDF_RESPONSE_VARIANTS_STRING;
import static org.fcrepo.http.commons.domain.RDFMediaType.TURTLE;
import static org.fcrepo.kernel.api.FedoraTypes.FCR_ACL;
import static org.fcrepo.kernel.api.FedoraTypes.FCR_VERSIONS;
import static org.fcrepo.kernel.api.RdfLexicon.ARCHIVAL_GROUP;
import static org.fcrepo.kernel.api.RdfLexicon.BASIC_CONTAINER;
import static org.fcrepo.kernel.api.RdfLexicon.CONTAINER;
import static org.fcrepo.kernel.api.RdfLexicon.CONTAINS;
import static org.fcrepo.kernel.api.RdfLexicon.EMBED_CONTAINED;
import static org.fcrepo.kernel.api.RdfLexicon.FEDORA_BINARY;
import static org.fcrepo.kernel.api.RdfLexicon.MEMENTO_TYPE;
import static org.fcrepo.kernel.api.RdfLexicon.NON_RDF_SOURCE;
import static org.fcrepo.kernel.api.RdfLexicon.RDF_SOURCE;
import static org.fcrepo.kernel.api.RdfLexicon.RESOURCE;
import static org.fcrepo.kernel.api.RdfLexicon.VERSIONED_RESOURCE;
import static org.fcrepo.kernel.api.RdfLexicon.VERSIONING_TIMEMAP_TYPE;
import static org.fcrepo.kernel.api.services.VersionService.MEMENTO_LABEL_FORMATTER;
import static org.fcrepo.kernel.api.services.VersionService.MEMENTO_RFC_1123_FORMATTER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static jakarta.ws.rs.core.HttpHeaders.ACCEPT;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_LENGTH;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.HttpHeaders.LINK;
import static jakarta.ws.rs.core.HttpHeaders.LOCATION;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static jakarta.ws.rs.core.Response.Status.FOUND;
import static jakarta.ws.rs.core.Response.Status.GONE;
import static jakarta.ws.rs.core.Response.Status.METHOD_NOT_ALLOWED;
import static jakarta.ws.rs.core.Response.Status.NOT_ACCEPTABLE;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.NO_CONTENT;
import static jakarta.ws.rs.core.Response.Status.OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Arrays.asList;
import static java.util.Arrays.sort;

import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableList;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.DC;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.http.commons.api.rdf.HttpIdentifierConverter;
import org.fcrepo.http.commons.test.util.CloseableDataset;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.storage.ocfl.CommitType;
import org.fcrepo.storage.ocfl.DefaultOcflObjectSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.TestExecutionListeners;

/**
 * @author lsitu
 * @author bbpennel
 */
@TestExecutionListeners(
        listeners = { TestIsolationExecutionListener.class },
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public class FedoraVersioningIT extends AbstractResourceIT {

    private static final String BINARY_CONTENT = "binary content";
    private static final String BINARY_UPDATED = "updated content";

    private static final String OCTET_STREAM_TYPE = "application/octet-stream";

    private static final Node MEMENTO_TYPE_NODE = createURI(MEMENTO_TYPE);

    private static final Property TEST_PROPERTY = createProperty("info:test#label");

    private static final Resource TEST_TYPE_RESOURCE = createResource("http://example.com/custom_type");

    private final List<String> rdfTypes = new ArrayList<>(asList(POSSIBLE_RDF_RESPONSE_VARIANTS_STRING));

    private String subjectUri;
    private String id;

    private static final HttpIdentifierConverter identifierConverter =
            new HttpIdentifierConverter(UriBuilder.fromUri(serverAddress + "{path: .*}"));

    private static ExecutorService executorService;

    @BeforeAll
    public static void beforeClass() {
        executorService = Executors.newSingleThreadExecutor();
    }

    @AfterAll
    public static void afterClass() {
        executorService.shutdown();
    }

    @TempDir
    public Path tmpDir;

    private DefaultOcflObjectSessionFactory objectSessionFactory;

    @BeforeEach
    public void init() {
        id = getRandomUniqueId();
        subjectUri = serverAddress + id;
        objectSessionFactory = getBean(DefaultOcflObjectSessionFactory.class);
    }

    @AfterEach
    public void after() {
        objectSessionFactory.setDefaultCommitType(CommitType.NEW_VERSION);
    }

    @Test
    public void testDeleteTimeMapNotAllowed() throws Exception {
        createVersionedContainer(id);
        final String timeMapUri = subjectUri + "/" + FCR_VERSIONS;
        assertEquals(200, getStatus(new HttpGet(timeMapUri)));
        // disabled versioning to delete TimeMap
        assertEquals(METHOD_NOT_ALLOWED.getStatusCode(),
                     getStatus(new HttpDelete(serverAddress + id + "/" + FCR_VERSIONS)));
    }

    @Test
    public void createMementoOnResourceWithNoUnversionedChanges() throws Exception {
        final var v1 = now();
        createVersionedContainer(id);
        TimeUnit.SECONDS.sleep(1);

        final var v2 = now();
        createMemento(subjectUri);

        verifyTimemapResponse(subjectUri, new String[]{v1, v2}, v1, v2);
    }

    @Test
    public void getTimeMapForContainerWithSingleVersion() throws Exception {
        createVersionedContainer(id);
        verifyTimemapResponse(subjectUri, now());
    }

    @Test
    public void testResponseForMultipleMementosInASecond() throws Exception {
        final var v1 = now();
        createVersionedContainer(id);
        TimeUnit.SECONDS.sleep(1);

        final var v2 = now();
        final List<Future<Void>> tasks = executorService.invokeAll(List.of(
                () -> {
                    createMemento(subjectUri);
                    createMemento(subjectUri);
                    return null;
                }
        ));
        for (var task : tasks) {
            try {
                task.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        verifyTimemapResponse(subjectUri, new String[]{v1, v2}, v1, v2);
    }

    @Test
    public void getTimeMapFromBinaryWithMultipleVersions() throws Exception {
        final var v1 = now();
        createVersionedBinary(id, OCTET_STREAM_TYPE, "v1");
        TimeUnit.SECONDS.sleep(1);

        final var v2 = now();
        putVersionedBinary(id, OCTET_STREAM_TYPE, "v2", true);
        TimeUnit.SECONDS.sleep(1);

        final var v3 = now();
        putVersionedBinary(id, OCTET_STREAM_TYPE, "v3", true);
        TimeUnit.SECONDS.sleep(1);

        verifyTimemapResponse(subjectUri, new String[]{v1, v2, v3}, v1, v3);
    }

    @Test
    public void getTimeMapFromAgWithChildrenWithDifferentVersions() throws Exception {
        final var childId1 = id + "/child1";
        final var childId2 = id + "/child2";

        final var v1 = now();
        createVersionedArchivalGroup(id);
        TimeUnit.SECONDS.sleep(1);

        final var v2 = now();
        putVersionedBinary(childId1, OCTET_STREAM_TYPE, "v2", false);
        TimeUnit.SECONDS.sleep(1);

        final var v3 = now();
        putVersionedBinary(childId2, OCTET_STREAM_TYPE, "v3", false);
        TimeUnit.SECONDS.sleep(1);

        final var v4 = now();
        putVersionedBinary(childId1, OCTET_STREAM_TYPE, "v4", true);
        TimeUnit.SECONDS.sleep(1);

        verifyTimemapResponse(subjectUri, new String[]{v1, v2, v3, v4}, v1, v4);
        verifyTimemapResponse(subjectUri + "/child1", new String[]{v2, v4}, v2, v4);
        verifyTimemapResponse(subjectUri + "/child2", v3);
    }

    @Test
    public void getMementoFromAgChild() throws Exception {
        objectSessionFactory.setDefaultCommitType(CommitType.UNVERSIONED);
        final var childId1 = id + "/child1";

        createVersionedArchivalGroup(id);
        createMemento(subjectUri);
        TimeUnit.SECONDS.sleep(1);

        putVersionedBinary(childId1, OCTET_STREAM_TYPE, "v2", false);
        final var mementoUri = createMemento(subjectUri);
        final var mementoTime = mementoUri.substring(mementoUri.lastIndexOf("/"));

        final HttpGet httpGet = new HttpGet(subjectUri + "/child1/fcr:versions" + mementoTime);
        try (final CloseableHttpResponse response = execute(httpGet)) {
            assertMementoDatetimeHeaderMatches(response, now());
            assertEquals("v2", EntityUtils.toString(response.getEntity()),
                    "Binary content of memento must match original content");
        }
    }

    @Test
    public void testGetTimeMapResponseMultipleMementos() throws Exception {
        createVersionedContainer(id);
        final var v1 = now();
        TimeUnit.SECONDS.sleep(1);

        putVersionedContainer(id, "2", true);
        final var v2 = now();
        TimeUnit.SECONDS.sleep(1);

        putVersionedContainer(id, "3", true);
        final var v3 = now();

        verifyTimemapResponse(subjectUri, new String[] {v1, v2, v3}, v1, v3);
    }

    @Test
    public void testGetTimeMapRDFSubject() throws Exception {
        createVersionedContainer(id);

        final HttpGet httpGet = getObjMethod(id + "/" + FCR_VERSIONS);

        try (final CloseableDataset dataset = getDataset(httpGet)) {
            final DatasetGraph results = dataset.asDatasetGraph();
            final Node subject = createURI(subjectUri + "/" + FCR_VERSIONS);
            assertTrue(results.contains(ANY, subject, ANY, ANY), "Did not find correct subject");
        }
    }

    @Test
    public void testCreateVersion() throws Exception {
        createVersionedContainer(id);

        final String mementoUri = createMemento(subjectUri);
        assertMementoUri(mementoUri, subjectUri);

        try (final CloseableDataset dataset = getDataset(new HttpGet(mementoUri))) {
            final DatasetGraph results = dataset.asDatasetGraph();

            final Node mementoSubject = createURI(mementoUri);

            assertFalse(results.contains(ANY, mementoSubject, RDF.type.asNode(), MEMENTO_TYPE_NODE),
                    "Memento type should not be visible");

            assertMementoEqualsOriginal(mementoUri);
        }
    }

    @Test
    public void testCreateVersionWithAcceptDatetime() throws Exception {
        final String mementoDateTime =
                MEMENTO_RFC_1123_FORMATTER.format(ISO_INSTANT.parse("2017-06-10T11:41:00Z", Instant::from));
        createVersionedContainer(id);
        final var post = postObjMethod(id + "/fcr:versions");
        post.addHeader(MEMENTO_DATETIME_HEADER, mementoDateTime);
        try (final CloseableHttpResponse response = execute(post)) {
            assertEquals(SC_BAD_REQUEST, response.getStatusLine().getStatusCode(),
                    "Operation should return " + SC_BAD_REQUEST + " status");
            assertConstrainedByPresent(response);
        }
    }


    @Test
    public void testCreateVersionFromResourceWithHashURI() throws Exception {
        final HttpPost createMethod = postObjMethod();
        createMethod.addHeader("Slug", id);
        createMethod.addHeader(CONTENT_TYPE, N3);
        createMethod.setEntity(new StringEntity("<#test> <info:test#label> \"foo\""));

        assertEquals(CREATED.getStatusCode(), getStatus(createMethod), "Didn't get a CREATED response!");

        final String mementoUri = createMemento(subjectUri);
        assertMementoUri(mementoUri, subjectUri);

        try (final CloseableDataset dataset = getDataset(new HttpGet(mementoUri))) {
            final DatasetGraph results = dataset.asDatasetGraph();

            final Node mementoSubject = createURI(mementoUri);

            assertFalse(results.contains(ANY, mementoSubject, RDF.type.asNode(), MEMENTO_TYPE_NODE),
                    "Memento type should not be visible");

            assertMementoEqualsOriginal(mementoUri);
        }
    }

    @Test
    public void testCreateVersionFromResourceWithBlankNode() throws Exception {
        final HttpPost createMethod = postObjMethod();
        createMethod.addHeader("Slug", id);
        createMethod.addHeader(CONTENT_TYPE, TURTLE);
        createMethod.setEntity(new StringEntity("<> <http://purl.org/dc/terms/subject> " +
                "[ a <info:test#Something> ]"));

        try (final CloseableHttpResponse response = execute(createMethod)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response), "Didn't get a CREATED response!");
        }

        final String mementoUri = createMemento(subjectUri);
        assertMementoUri(mementoUri, subjectUri);

        try (final CloseableDataset dataset = getDataset(new HttpGet(mementoUri))) {
            final DatasetGraph results = dataset.asDatasetGraph();

            // Expect triple with Blank Node as Object
            final Iterator<Quad> quads =
                    results.find(ANY, createURI(subjectUri), createURI("http://purl.org/dc/terms/subject"), ANY);

            final List<Quad> quadList = ImmutableList.copyOf(quads);
            assertEquals(1, quadList.size(), "Should only be one element: " + quadList.size());

            final Quad quad = quadList.getFirst();
            // The quad:Object is the subject of the Blank Node triple we are expecting
            assertTrue(results.contains(ANY, quad.getObject(), RDF.type.asNode(), createURI("info:test#Something")),
                    "Should have found blank node triple");
        }
    }

    @Test
    public void testCreateVersionWithSlugHeader() throws Exception {
        createVersionedContainer(id);

        // Bad request with Slug header to create memento
        final HttpPost post = postObjMethod(id + "/" + FCR_VERSIONS);
        post.addHeader("Slug", "version_label");

        assertEquals(BAD_REQUEST.getStatusCode(), getStatus(post),
                "Created memento with Slug!");
    }

    @Test
    public void testCreateVersionWithMementoDateTimeHeader() throws Exception {
        createVersionedContainer(id);

        // Bad request with Memento-Datetime header to create memento
        final String mementoDateTime = "Tue, 3 Jun 2008 11:05:30 GMT";
        final String body = createContainerMementoBodyContent(subjectUri, N3);
        final HttpPost post = postObjMethod(id + "/" + FCR_VERSIONS);

        post.addHeader(MEMENTO_DATETIME_HEADER, mementoDateTime);
        post.addHeader(CONTENT_TYPE, N3);
        post.setEntity(new StringEntity(body));

        assertEquals(BAD_REQUEST.getStatusCode(), getStatus(post),
                "Created memento with Memento-Datetime!");
    }

    @Test
    public void testMementoContainmentReferences() throws Exception {
        createVersionedContainer(id);
        TimeUnit.SECONDS.sleep(1);

        final String childUri = subjectUri + "/x";
        createObjectAndClose(id + "/x");

        // create memento
        final String mementoUri = createMemento(subjectUri);

        TimeUnit.SECONDS.sleep(1);
        // Remove the child resource
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(childUri)),
                "Expected delete to succeed");

        // Ensure that the resource reference is gone
        try (final CloseableHttpResponse getResponse1 = execute(new HttpGet(subjectUri));
                final CloseableDataset dataset = getDataset(getResponse1)) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertFalse(graph.contains(ANY, ANY, createURI(CONTAINS.getURI()), createURI(childUri)),
                    "Expected NOT to have child resource: " + graph);
        }

        // Ensure that the resource reference is still in memento
        try (final CloseableHttpResponse getResponse1 = execute(new HttpGet(mementoUri));
                final CloseableDataset dataset = getDataset(getResponse1)) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY, ANY, createURI(CONTAINS.getURI()), createURI(childUri)),
                    "Expected child resource NOT found: " + graph);
        }
    }

    @Test
    public void testHeadOnMemento() throws Exception {

        createVersionedContainer(id);
        final String mementoUri = createMemento(subjectUri);

        // Status 200: HEAD request on existing memento
        final HttpHead headMethod = new HttpHead(mementoUri);
        assertEquals(OK.getStatusCode(), getStatus(headMethod),
                "Expected memento is NOT found: " + mementoUri);

        // Status 404: HEAD request on absent memento
        final HttpHead headMementoAbsent = headObjMethod(id + "/" + FCR_VERSIONS + "/20000101000001");
        assertEquals(NOT_FOUND.getStatusCode(), getStatus(headMementoAbsent),
                "Didn't get status 404 on absent memento!");

        // Status 400: HEAD request with invalid memento path
        final HttpHead headMethodInvalid = headObjMethod(id + "/" + FCR_VERSIONS + "/any");
        checkResponseWithInvalidMementoID(headMethodInvalid);
    }

    @Test
    public void testGetOnMemento() throws Exception {

        createVersionedContainer(id);
        final String mementoUri = createMemento(subjectUri);

        // Status 200: GET request on existing memento
        final HttpGet getMemento = new HttpGet(mementoUri);
        assertEquals(OK.getStatusCode(), getStatus(getMemento), "Expected memento is NOT found: " + mementoUri);

        // Status 404: GET request on absent memento
        final HttpGet getMementoAbsent = getObjMethod(id + "/" + FCR_VERSIONS + "/20000101000001");
        assertEquals(NOT_FOUND.getStatusCode(), getStatus(getMementoAbsent),
                "Didn't get status 404 on absent memento!");

        // Status 400: GET request with invalid memento path
        final HttpGet getMementoInvalid = getObjMethod(id + "/" + FCR_VERSIONS + "/any");
        checkResponseWithInvalidMementoID(getMementoInvalid);
    }

    @Test
    public void testGetOnMementoWithAcceptDatetimePresent() throws Exception {
        createVersionedContainer(id);
        final String mementoDateTime =
            MEMENTO_RFC_1123_FORMATTER.format(ISO_INSTANT.parse("2017-06-10T11:41:00Z", Instant::from));
        final String mementoUri = createMemento(subjectUri);
        // Status 200: GET request on existing memento
        final HttpGet getMemento = new HttpGet(mementoUri);
        getMemento.addHeader(ACCEPT_DATETIME, mementoDateTime);
        assertEquals(OK.getStatusCode(), getStatus(getMemento),
                "Expected memento could not be retrieved when Accept-Datetime header is present: " + mementoUri);
    }

    @Test
    public void testHeadOnMementoWithAcceptDatetimePresent() throws Exception {
        createVersionedContainer(id);
        final String mementoDateTime =
                MEMENTO_RFC_1123_FORMATTER.format(ISO_INSTANT.parse("2017-06-10T11:41:00Z", Instant::from));
        final String mementoUri = createMemento(subjectUri);
        // Status 200: HEAD request on existing memento
        final HttpHead headMemento = new HttpHead(mementoUri);
        headMemento.addHeader(ACCEPT_DATETIME, mementoDateTime);
        assertEquals(OK.getStatusCode(), getStatus(headMemento),
                "Expected memento could not be retrieved when Accept-Datetime header is present: " + mementoUri);
    }

    @Test
    public void testOptionsOnMemento() throws Exception {

        createVersionedContainer(id);
        final String mementoUri = createMemento(subjectUri);

        // Status 200: OPTIONS request on existing memento
        final HttpOptions optionsMemento = new HttpOptions(mementoUri);
        assertEquals(OK.getStatusCode(), getStatus(optionsMemento), "Expected memento is NOT found: " + mementoUri);

        // Status 404: OPTIONS request on absent memento
        final String absentMementoPath = serverAddress + id + "/" + FCR_VERSIONS + "/20000101000001";
        final HttpOptions optionsMementoAbsent = new HttpOptions(absentMementoPath);
        assertEquals(NOT_FOUND.getStatusCode(), getStatus(optionsMementoAbsent),
                "Didn't get status 404 on absent memento!");

        // Status 400: OPTIONS request with invalid memento path
        final HttpOptions optionsMementoInvalid = new HttpOptions(serverAddress + id + "/" + FCR_VERSIONS + "/any");
        checkResponseWithInvalidMementoID(optionsMementoInvalid);
    }

    @Test
    public void testMementoExternalReference() throws Exception {
        createVersionedContainer(id);

        final String pid = getRandomUniqueId();
        final String resource = serverAddress + pid;
        createObjectAndClose(pid);

        final HttpPatch updateObjectGraphMethod = patchObjMethod(id);
        updateObjectGraphMethod.addHeader(CONTENT_TYPE, "application/sparql-update");
        updateObjectGraphMethod.setEntity(new StringEntity("INSERT {"
                + " <> <http://pcdm.org/models#hasMember> <" + resource + "> } WHERE {}"));
        executeAndClose(updateObjectGraphMethod);

        // create memento
        final String mementoUri = createMemento(subjectUri);

        // Remove the referencing resource
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(resource)),
                "Expected delete to succeed");

        // Ensure that the resource reference remains (no referential integrity)
        try (final CloseableHttpResponse getResponse1 = execute(new HttpGet(subjectUri));
                final CloseableDataset dataset = getDataset(getResponse1)) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY, ANY, createURI("http://pcdm.org/models#hasMember"), createURI(resource)),
                    "Expected to see resource: " + graph);
        }

        try (final CloseableHttpResponse getResponse1 = execute(new HttpGet(mementoUri));
                final CloseableDataset dataset = getDataset(getResponse1)) {

            final DatasetGraph graph = dataset.asDatasetGraph();

            // Ensure that the resource reference is still in memento
            assertTrue(graph.contains(ANY, ANY, createURI("http://pcdm.org/models#hasMember"), createURI(resource)),
                    "Expected resource NOT found: " + graph);

            // Ensure that the subject of the memento is the original reosurce
            assertFalse(graph.contains(ANY, createURI(mementoUri), ANY, ANY),
                    "Subjects should be the original resource, not the memento: " + graph);
        }
    }

    @Test
    public void testDescriptionMementoReference() throws Exception {
        // Create binary with description referencing other resource
        createVersionedBinary(id);

        final String referencedPid = getRandomUniqueId();
        final String referencedResource = serverAddress + referencedPid;
        createObjectAndClose(referencedPid);

        final String metadataId = id + "/fcr:metadata";
        final String metadataUri = serverAddress + metadataId;

        final String relation = "http://purl.org/dc/elements/1.1/relation";
        final HttpPatch updateObjectGraphMethod = patchObjMethod(metadataId);
        updateObjectGraphMethod.addHeader(CONTENT_TYPE, "application/sparql-update");
        updateObjectGraphMethod.setEntity(new StringEntity(
                "INSERT {" + " <> <" + relation + "> <" + referencedResource + "> } WHERE {}"));
        executeAndClose(updateObjectGraphMethod);

        // Create memento
        final String mementoUri = createMemento(subjectUri);
        assertMementoUri(mementoUri, subjectUri);

        // Delete referenced resource
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(referencedResource)),
                "Expected delete to succeed");

        final Node originalBinaryNode = createURI(serverAddress + id);
        // Ensure that the resource reference remains
        try (final CloseableHttpResponse getResponse1 = execute(new HttpGet(metadataUri));
                final CloseableDataset dataset = getDataset(getResponse1)) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY, originalBinaryNode, createURI(relation), createURI(referencedResource)),
                    "Expected TO have resource: " + graph);
        }

        final String descMementoUrl = mementoUri.replace(FCR_VERSIONS, "fcr:metadata/fcr:versions");
        // Ensure that the resource reference is still in memento
        try (final CloseableHttpResponse getResponse1 = execute(new HttpGet(descMementoUrl));
                final CloseableDataset dataset = getDataset(getResponse1)) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY, originalBinaryNode, createURI(relation), createURI(referencedResource)),
                    "Expected resource NOT found: " + graph);
        }
    }

    @Test
    public void testPutOnTimeMapContainer() throws Exception {
        createVersionedContainer(id);

        // status 405: PUT On LPDCv is disallowed.
        assertEquals(405, getStatus(new HttpPut(serverAddress + id + "/" + FCR_VERSIONS)));
    }

    @Test
    public void testPatchOnTimeMapContainer() throws Exception {
        createVersionedContainer(id);

        // status 405: PATCH On LPDCv is disallowed.
        assertEquals(405, getStatus(new HttpPatch(serverAddress + id + "/" + FCR_VERSIONS)));
    }

    @Test
    public void testGetTimeMapResponseForBinary() throws Exception {
        objectSessionFactory.setDefaultCommitType(CommitType.UNVERSIONED);

        createVersionedBinary(id);

        verifyTimemapResponse(subjectUri);
    }

    @Test
    public void testGetTimeMapResponseWithBadAcceptHeader() throws Exception {
        createVersionedContainer(id);

        final HttpGet httpGet = getObjMethod(id + "/" + FCR_VERSIONS);
        httpGet.setHeader("Accept", "application/arbitrary");
        try (final CloseableHttpResponse response = execute(httpGet)) {
            assertEquals(NOT_ACCEPTABLE.getStatusCode(), getStatus(response),
                    "Should get a 'Not Acceptable' response!");
        }

    }

    @Test
    public void testGetTimeMapResponseForBinaryDescription() throws Exception {
        objectSessionFactory.setDefaultCommitType(CommitType.UNVERSIONED);

        createVersionedBinary(id);

        final String descriptionUri = subjectUri + "/fcr:metadata";
        verifyTimemapResponse(descriptionUri);
    }

    /**
     * Verify an application/link-format TimeMap response.
     *
     * @param uri The full URI of the Original Resource.
     * @throws Exception on HTTP request error
     */
    private void verifyTimemapResponse(final String uri) throws Exception {
        verifyTimemapResponse(uri, null, null, null);
    }

    /**
     * Verify an application/link-format TimeMap response.
     *
     * @param uri The full URI of the Original Resource.
     * @param mementoDateTime a RFC-1123 datetime
     * @throws Exception on HTTP request error
     */
    private void verifyTimemapResponse(final String uri, final String mementoDateTime)
        throws Exception {
        final String[] mementoDateTimes = { mementoDateTime };
        verifyTimemapResponse(uri, mementoDateTimes, null, null);
    }

    /**
     * Verify an application/link-format TimeMap response.
     *
     * @param uri The full URI of the Original Resource.
     * @param mementoDateTime Array of all the RFC-1123 datetimes for all the mementos.
     * @param rangeStart RFC-1123 datetime of the first memento.
     * @param rangeEnd RFC-1123 datetime of the last memento.
     * @throws Exception on HTTP request error
     */
    private void verifyTimemapResponse(final String uri, final String[] mementoDateTime,
        final String rangeStart, final String rangeEnd)
        throws Exception {
        final String ldpcvUri = uri + "/" + FCR_VERSIONS;
        final var expectedLinksOther = new ArrayList<Link>();
        final var expectedLinksMemento = new ArrayList<Link>();
        expectedLinksOther.add(Link.fromUri(uri).rel("original").build());
        expectedLinksOther.add(Link.fromUri(uri).rel("timegate").build());
        expectedLinksOther.sort(Comparator.comparing(Link::toString));

        final var expectedSelfLinkBuilder = Link.fromUri(ldpcvUri).rel("self")
                .type(APPLICATION_LINK_FORMAT);
        if (rangeStart != null && rangeEnd != null) {
            expectedSelfLinkBuilder.param("from", rangeStart).param("until",
                rangeEnd);
        }
        final var expectedSelfLink = expectedSelfLinkBuilder.build();

        if (mementoDateTime != null) {
            for (final String memento : mementoDateTime) {
                final TemporalAccessor instant = MEMENTO_RFC_1123_FORMATTER.parse(memento);
                expectedLinksMemento.add(Link.fromUri(ldpcvUri + "/" + MEMENTO_LABEL_FORMATTER.format(instant))
                              .rel("memento")
                    .param("datetime", memento)
                              .build());
            }
        }
        expectedLinksMemento.sort(Comparator.comparing(Link::toString));

        final HttpGet httpGet = new HttpGet(uri + "/" + FCR_VERSIONS);
        httpGet.setHeader("Accept", APPLICATION_LINK_FORMAT);
        try (final CloseableHttpResponse response = execute(httpGet)) {
            assertEquals(OK.getStatusCode(), getStatus(response), "Didn't get a OK response!");
            // verify headers in link format.
            verifyTimeMapHeaders(response, uri);
            final var responseBody = EntityUtils.toString(response.getEntity());
            final List<String> bodyList = asList(responseBody.split("," + System.lineSeparator()));
            //the links from the body are not

            Link selfLink = null;
            final var mementoLinks = new ArrayList<Link>();
            final var otherLinks = new ArrayList<Link>();

            final var allLinks = bodyList.stream().map(String::trim).filter(t -> !t.isEmpty())
                                                      .sorted(Comparator.naturalOrder())
                                                      .map(Link::valueOf).toList();

            for (final var link : allLinks) {
                if ("memento".equals(link.getRel())) {
                    mementoLinks.add(link);
                } else if ("self".equals(link.getRel())) {
                    selfLink = link;
                } else {
                    otherLinks.add(link);
                }
            }

            assertSelfLink(expectedSelfLink, selfLink);
            assertEquals(expectedLinksOther, otherLinks);
            assertEquals(expectedLinksMemento.size(), mementoLinks.size());
            for (var i = 0; i < expectedLinksMemento.size(); i++) {
                assertMementoLink(expectedLinksMemento.get(i), mementoLinks.get(i));
            }

        }
    }

    private void assertSelfLink(final Link expected, final Link actual) {
        if (!expected.equals(actual)) {
            assertEquals(expected.getUri(), actual.getUri());

            final var expectedFromStr = expected.getParams().get("from");
            final var expectedUntilStr = expected.getParams().get("until");
            final var actualFromStr = actual.getParams().get("from");
            final var actualUntilStr = actual.getParams().get("until");

            if (expectedFromStr != null) {
                assertNotNull(actualFromStr, "link cannot have a null 'from' param");
                assertNotNull(actualUntilStr, "link cannot have a null 'until' param");
                assertDuration(expectedFromStr, actualFromStr);
                assertDuration(expectedUntilStr, actualUntilStr);
            } else {
                assertNull(actualFromStr, "link cannot have a 'from' param");
                assertNull(actualUntilStr, "link cannot have a 'until' param");
            }
        }
    }

    private void assertMementoLink(final Link expected, final Link actual) {
        if (!expected.equals(actual)) {
            // Ensures the timestamps are close to each other
            assertDuration(expected.getParams().get("datetime"), actual.getParams().get("datetime"));

            final var expectedUri = expected.getUri().toString();
            final var actualUri = actual.getUri().toString();
            // Reduces the granularity of the timestamp strings to ensure their formatting is the same, even if they're
            // a few seconds apart.
            assertEquals(expectedUri.substring(0, expectedUri.length() - 3),
                    actualUri.substring(0, actualUri.length() - 3));
        }
    }

    private static void assertDuration(final String expected, final String actual) {
        final var expectedInstant = parseToInstant(expected);
        final var actualInstant = parseToInstant(actual);

        final var diff = Duration.between(expectedInstant, actualInstant);

        assertTrue(diff.abs().getSeconds() < 5,
                "Difference in expected and actual times should be less than 5 seconds");
    }

    private static Instant parseToInstant(final String value) {
        return Instant.from(MEMENTO_RFC_1123_FORMATTER.parse(value));
    }

    /**
     * Allow a one second on each side allowance when comparing memento URIs as tests can vary.
     * @param message Message to return.
     * @param expected The expected URI.
     * @param actual The actual URI.
     */
    private static void verifyMementoUri(final String message, final String expected, final String actual) {
        if (!expected.equals(actual)) {
            // Make 2 additional URIs by parsing the memento label and adding/subtracting 1 second.
            final String expectedUriPrefix = expected.substring(0, expected.lastIndexOf("/"));
            final String expectedDateString = expected.substring(expected.lastIndexOf("/") + 1);
            final Instant expectedInstant = Instant.from(MEMENTO_LABEL_FORMATTER.parse(expectedDateString));
            final List<String> allowed = List.of(
                    expectedUriPrefix + MEMENTO_LABEL_FORMATTER.format(expectedInstant.minusSeconds(1)),
                    expected,
                    expectedUriPrefix + MEMENTO_LABEL_FORMATTER.format(expectedInstant.plusSeconds(1))
            );
            assertTrue(allowed.contains(actual), message);
        }
    }

    /**
     * Utility function to verify TimeMap headers
     *
     * @param response the response
     * @param uri the URI of the resource.
     */
    private static void verifyTimeMapHeaders(final CloseableHttpResponse response, final String uri) {
        final var id = FedoraId.create(identifierConverter.toInternalId(uri));
        checkForLinkHeader(response, RESOURCE.toString(), "type");
        checkForLinkHeader(response, CONTAINER.toString(), "type");
        checkForLinkHeader(response, RDF_SOURCE.getURI(), "type");
        checkForLinkHeader(response, uri, "original");
        checkForLinkHeader(response, uri, "timegate");
        checkForLinkHeader(response, uri + "/" + FCR_VERSIONS, "timemap");
        checkForLinkHeader(response, VERSIONING_TIMEMAP_TYPE, "type");
        if (id.isDescription()) {
            final var binaryUri = identifierConverter.toExternalId(id.getFullDescribedId());
            checkForLinkHeader(response, binaryUri + "/" + FCR_ACL, "acl");
        } else {
            checkForLinkHeader(response, uri + "/" + FCR_ACL, "acl");
        }
        assertFalse(response.getFirstHeader("Allow").getValue().contains("DELETE"));
        assertTrue(response.getFirstHeader("Allow").getValue().contains("GET"));
        assertTrue(response.getFirstHeader("Allow").getValue().contains("HEAD"));
        assertTrue(response.getFirstHeader("Allow").getValue().contains("POST"));
        assertEquals(1, response.getHeaders("Accept-Post").length);
    }

    @Test
    public void testCreateVersionOfBinary() throws Exception {
        createVersionedBinary(id);

        final String mementoUri = createMemento(subjectUri);
        assertMementoUri(mementoUri, subjectUri);

        final HttpGet httpGet = new HttpGet(mementoUri);
        try (final CloseableHttpResponse response = execute(httpGet)) {
            assertMementoDatetimeHeaderMatches(response, now());
            assertEquals(BINARY_CONTENT, EntityUtils.toString(response.getEntity()),
                    "Binary content of memento must match original content");
        }

        // Verifying that the associated description memento was created
        final String descriptionMementoUri = mementoUri.replace("fcr:versions", "fcr:metadata/fcr:versions");

        final HttpGet descGet = new HttpGet(descriptionMementoUri);
        try (final CloseableHttpResponse response = execute(descGet)) {
            assertMementoDatetimeHeaderPresent(response);
            assertHasLink(response, type, RDF_SOURCE.getURI());
        }
    }

    @Test
    public void testCreateVersionOfBinaryWithDatetimeAndBody() throws Exception {
        objectSessionFactory.setDefaultCommitType(CommitType.UNVERSIONED);

        createVersionedBinary(id);

        final String mementoUri = createMemento(subjectUri);
        final String v1Time = now();
        assertMementoUri(mementoUri, subjectUri);

        TimeUnit.SECONDS.sleep(1);

        putVersionedBinary(id, OCTET_STREAM_TYPE, BINARY_UPDATED, true);

        final String mementoUri2 = createMemento(subjectUri);
        final String v2Time = now();
        assertMementoUri(mementoUri2, subjectUri);

        assertNotEquals(mementoUri, mementoUri2, "mementos should be different");

        // Verify that the memento has the updated binary
        try (final CloseableHttpResponse response = execute(new HttpGet(mementoUri))) {
            assertMementoDatetimeHeaderMatches(response, v1Time);

            assertEquals(OCTET_STREAM_TYPE, response.getFirstHeader(CONTENT_TYPE).getValue());

            assertEquals(BINARY_CONTENT, EntityUtils.toString(response.getEntity()),
                    "Binary content of memento must match original content");
        }

        try (final CloseableHttpResponse response = execute(new HttpGet(mementoUri2))) {
            assertMementoDatetimeHeaderMatches(response, v2Time);

            // Content-type is not retained for a binary memento created without description
            assertEquals(OCTET_STREAM_TYPE, response.getFirstHeader(CONTENT_TYPE).getValue());

            assertEquals(BINARY_UPDATED, EntityUtils.toString(response.getEntity()),
                    "Binary content of memento must match updated content");
        }
    }

    @Test
    public void testCreateVersionOfBinaryDescription() throws Exception {
        createVersionedBinary(id);

        final String descriptionUri = subjectUri + "/fcr:metadata";

        final HttpPatch patchProps = new HttpPatch(descriptionUri);
        patchProps.setHeader(CONTENT_TYPE, "application/sparql-update");
        final String updateString =
                "INSERT { <> <" + DC.title.getURI() + "> \"Original\" ." +
                        " <> <" + RDF.type.getURI() + "> <" + TEST_TYPE_RESOURCE.getURI() + "> } WHERE { }";
        patchProps.setEntity(new StringEntity(updateString));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(patchProps));
        TimeUnit.SECONDS.sleep(1);
        final String mementoUri = createMemento(descriptionUri);
        assertMementoUri(mementoUri, descriptionUri);
        TimeUnit.SECONDS.sleep(1);

        setDescriptionProperty(id, null, DC.title.getURI(), "Updated");
        TimeUnit.SECONDS.sleep(1);
        try (final CloseableDataset dataset = getDataset(new HttpGet(mementoUri))) {
            final DatasetGraph results = dataset.asDatasetGraph();

            final Node mementoSubject = createURI(subjectUri);

            assertTrue(results.contains(ANY, mementoSubject, DC.title.asNode(), createLiteral("Original")),
                    "Property added to original before versioning must appear");
            assertFalse(results.contains(ANY, mementoSubject, title.asNode(), createLiteral("Updated")),
                    "Property added after memento created must not appear");
            assertFalse(results.contains(ANY, mementoSubject, type.asNode(), MEMENTO_TYPE_NODE),
                    "Memento type should not be visible");
            assertTrue(results.contains(ANY, mementoSubject, RDF.type.asNode(), FEDORA_BINARY.asNode()),
                    "Must have binary type");
            assertTrue(results.contains(ANY, mementoSubject, RDF.type.asNode(), TEST_TYPE_RESOURCE.asNode()),
                    "Must have custom type");
        }
    }

    @Test
    public void testAddAndRetrieveVersion() throws Exception {
        createVersionedContainer(id);

        logger.debug("Setting a title");
        patchLiteralProperty(serverAddress + id, title.getURI(), "First Title");

        try (final CloseableDataset dataset = getContent(serverAddress + id)) {
            assertTrue(dataset.asDatasetGraph().contains(ANY, ANY, title.asNode(), createLiteral("First Title")),
                    "Should find original title");
        }
        logger.debug("Posting version v0.0.1");
        final String mementoUri = createMemento(subjectUri);
        assertMementoUri(mementoUri, subjectUri);
        TimeUnit.SECONDS.sleep(1);
        logger.debug("Replacing the title");
        patchLiteralProperty(serverAddress + id, title.getURI(), "Second Title");
        TimeUnit.SECONDS.sleep(1);

        final var subjectUri = createURI(serverAddress + id);
        try (final CloseableDataset dataset = getContent(mementoUri)) {
            logger.debug("Got version profile:");
            final DatasetGraph versionResults = dataset.asDatasetGraph();

            assertTrue(versionResults.contains(ANY, subjectUri, title.asNode(), createLiteral("First Title")),
                    "Should find a title in historic version");
            assertTrue(versionResults.contains(ANY, subjectUri, title.asNode(), createLiteral("First Title")),
                    "Should find original title in historic version");
            assertFalse(versionResults.contains(ANY, subjectUri, title.asNode(), createLiteral("Second Title")),
                    "Should not find the updated title in historic version");

        }
    }

    @Test
    public void testTimeMapResponseContentTypes() throws Exception {
        createVersionedContainer(id);

        final String[] timeMapResponseTypes = getTimeMapResponseTypes();
        for (final String type : timeMapResponseTypes) {
            final HttpGet method = new HttpGet(serverAddress + id + "/fcr:versions");
            method.addHeader(ACCEPT, type);
            assertEquals(type, getContentType(method));
        }
    }

    @Test
    public void testGetVersionResponseContentTypes() throws Exception {
        createVersionedContainer(id);
        final String versionUri = createMemento(subjectUri);

        final String[] rdfResponseTypes = rdfTypes.toArray(new String[0]);
        for (final String type : rdfResponseTypes) {
            final HttpGet method = new HttpGet(versionUri);
            method.addHeader(ACCEPT, type);
            assertEquals(type, getContentType(method));
        }
    }

    @Test
    public void testDatetimeNegotiationLDPRv() throws Exception {
        final String startDatetime = MEMENTO_RFC_1123_FORMATTER.format(Instant.now().atZone(ZoneOffset.UTC));

        // Make sure the start time is before the first memento
        TimeUnit.SECONDS.sleep(1);

        final CloseableHttpClient customClient = createClient(true);

        final String version1Uri = createVersionedContainerReturnMementoUri(id);

        TimeUnit.SECONDS.sleep(1);

        // Request datetime between memento1 and memento2
        final String betweenDatetime = MEMENTO_RFC_1123_FORMATTER.format(Instant.now().atZone(ZoneOffset.UTC));

        TimeUnit.SECONDS.sleep(1);

        putVersionedContainer(id, "update", true);
        final String version2Uri = createMemento(subjectUri);

        assertNotEquals(version1Uri, version2Uri, "mementos should be different");

        final HttpGet getMemento = getObjMethod(id);
        getMemento.addHeader(ACCEPT_DATETIME, betweenDatetime);

        try (final CloseableHttpResponse response = customClient.execute(getMemento)) {
            assertEquals(FOUND.getStatusCode(), getStatus(response), "Did not get FOUND response");
            assertNoMementoDatetimeHeaderPresent(response);
            verifyMementoUri("Did not get Location header", version1Uri, response.getFirstHeader(LOCATION).getValue());
            assertEquals("0", response.getFirstHeader(CONTENT_LENGTH).getValue(), "Did not get Content-Length == 0");
        }

        TimeUnit.SECONDS.sleep(1);

        // Request datetime more recent than both mementos
        final String afterDatetime = MEMENTO_RFC_1123_FORMATTER.format(Instant.now().atZone(ZoneOffset.UTC));

        final HttpGet getMemento2 = getObjMethod(id);
        getMemento2.addHeader(ACCEPT_DATETIME, afterDatetime);

        try (final CloseableHttpResponse response = customClient.execute(getMemento2)) {
            assertEquals(FOUND.getStatusCode(), getStatus(response), "Did not get FOUND response");
            assertNoMementoDatetimeHeaderPresent(response);
            verifyMementoUri("Did not get Location header", version2Uri, response.getFirstHeader(LOCATION).getValue());
            assertEquals("0", response.getFirstHeader(CONTENT_LENGTH).getValue(), "Did not get Content-Length == 0");
        }

        // Request datetime older than either mementos
        final HttpGet getMemento3 = getObjMethod(id);
        getMemento3.addHeader(ACCEPT_DATETIME, startDatetime);

        try (final CloseableHttpResponse response = customClient.execute(getMemento3)) {
            assertEquals(FOUND.getStatusCode(), getStatus(response), "Did not get FOUND response");
            assertNoMementoDatetimeHeaderPresent(response);
            verifyMementoUri("Did not get Location header", version1Uri, response.getFirstHeader(LOCATION).getValue());
            assertEquals("0", response.getFirstHeader(CONTENT_LENGTH).getValue(), "Did not get Content-Length == 0");
        }
    }

    @Test
    public void testDatetimeNegotiationExactMatch() throws Exception {
        testDatetimeNegotiation(false, false);
    }

    @Test
    public void testDatetimeNegotiationExactMatchHead() throws Exception {
        testDatetimeNegotiation(true, false);
    }

    @Test
    public void testDatetimeNegotiationOnTombstone() throws Exception {
        testDatetimeNegotiation(false, true);
    }

    @Test
    public void testDatetimeNegotiationOnTombstoneHead() throws Exception {
        testDatetimeNegotiation(true, true);
    }

    /**
     * Test datetime negotiation on a versioned resource.
     * @param isHead true if the request should be a HEAD request, false for GET
     * @param isTombstone true if the resource should be deleted before the request
     * @throws Exception on error
     */
    private void testDatetimeNegotiation(final boolean isHead, final boolean isTombstone) throws Exception {
        final CloseableHttpClient customClient = createClient(true);

        final String originalUri = createVersionedContainer(id);

        // Create a first memento
        final String version1Uri = createMemento(originalUri);
        final HttpHead httpHead = new HttpHead(version1Uri);
        final String version1Datetime;
        try (final CloseableHttpResponse response = customClient.execute(httpHead)) {
            version1Datetime = response.getFirstHeader(MEMENTO_DATETIME_HEADER).getValue();
        }

        TimeUnit.SECONDS.sleep(1);

        // Create second memento
        putVersionedContainer(id, "updated", true);
        final String version2Uri = createMemento(subjectUri);
        final HttpHead httpHead2 = new HttpHead(version2Uri);
        final String version2Datetime;
        try (final CloseableHttpResponse response = customClient.execute(httpHead2)) {
            version2Datetime = response.getFirstHeader(MEMENTO_DATETIME_HEADER).getValue();
        }

        assertNotEquals(version1Uri, version2Uri, "mementos should be different");

        if (isTombstone) {
            // Wait one second and then delete the resource
            TimeUnit.SECONDS.sleep(1);
            assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteObjMethod(id)));

            // Test we get a Tombstone now
            assertEquals(GONE.getStatusCode(), getStatus(getObjMethod(id)));
        }

        // Attempt to retrieve newer memento
        final HttpUriRequest requestVersion2 = isHead ? headObjMethod(id) : getObjMethod(id);
        requestVersion2.addHeader(ACCEPT_DATETIME, version2Datetime);

        try (final CloseableHttpResponse response = customClient.execute(requestVersion2)) {
            assertEquals(FOUND.getStatusCode(), getStatus(response), "Did not get FOUND response");
            assertNoMementoDatetimeHeaderPresent(response);
            verifyMementoUri("Did not get expected memento location",
                    version2Uri, response.getFirstHeader(LOCATION).getValue());
        }

        // Attempt to get older memento
        final HttpUriRequest requestVersion1 = isHead ? headObjMethod(id) : getObjMethod(id);
        requestVersion1.addHeader(ACCEPT_DATETIME, version1Datetime);

        try (final CloseableHttpResponse response = customClient.execute(requestVersion1)) {
            assertEquals(FOUND.getStatusCode(), getStatus(response), "Did not get FOUND response");
            assertNoMementoDatetimeHeaderPresent(response);
            verifyMementoUri("Did not get expected memento location",
                    version1Uri, response.getFirstHeader(LOCATION).getValue());
        }
    }

    @Test
    public void testDatetimeNegotiationNoMementos() throws Exception {
        objectSessionFactory.setDefaultCommitType(CommitType.UNVERSIONED);
        final CloseableHttpClient customClient = createClient(true);
        createVersionedContainer(id);
        final String requestDatetime =
                MEMENTO_RFC_1123_FORMATTER.format(ISO_INSTANT.parse("2017-01-12T00:00:00Z", Instant::from));
        final HttpGet getMemento = getObjMethod(id);
        getMemento.addHeader(ACCEPT_DATETIME, requestDatetime);

        try (final CloseableHttpResponse response = customClient.execute(getMemento)) {
            assertEquals(NOT_ACCEPTABLE.getStatusCode(), getStatus(response), "Didn't get NOT_ACCEPTABLE response");
            assertNull(response.getFirstHeader(LOCATION), "Didn't expect a Location header");
            assertNotEquals("0", response.getFirstHeader(CONTENT_LENGTH).getValue(), "Didn't get Content-Length > 0");
        }
    }


    @Test
    public void testGetWithDateTimeNegotiation() throws Exception {
        objectSessionFactory.setDefaultCommitType(CommitType.UNVERSIONED);
        final CloseableHttpClient customClient = createClient(true);
        final String mementoDateTime =
            MEMENTO_RFC_1123_FORMATTER.format(ISO_INSTANT.parse("2017-08-29T15:47:50Z", Instant::from));

        createVersionedContainer(id);

        // Status 406: Get absent memento with datetime negotiation.
        final HttpGet getMethod1 = getObjMethod(id);
        getMethod1.setHeader(ACCEPT_DATETIME, mementoDateTime);
        assertEquals(NOT_ACCEPTABLE.getStatusCode(), getStatus(customClient.execute(getMethod1)),
                "Didn't get status 406 on absent memento!");

        // Create memento
        final String mementoUri = createMemento(subjectUri);

        // Status 302: GET memento with datetime negotiation
        final HttpGet getMethod2 = getObjMethod(id);
        getMethod2.setHeader(ACCEPT_DATETIME, mementoDateTime);
        assertEquals(FOUND.getStatusCode(), getStatus(customClient.execute(getMethod2)),
                "Expected memento is NOT found: " + mementoUri);

        // Status 400: Get memento with bad Accept-Datetime header value
        final String badDataTime = "Wed, 29 Aug 2017 15:47:50 GMT"; // should be TUE, 29 Aug 2017 15:47:50 GMT
        final HttpGet getMethod3 = getObjMethod(id);
        getMethod3.setHeader(ACCEPT_DATETIME, badDataTime);
        assertEquals(BAD_REQUEST.getStatusCode(), getStatus(customClient.execute(getMethod3)),
                "Didn't get status 400 on bad Accept-Datetime value!");
    }

    @Test
    public void testFixityOnVersionedResource() throws Exception {
        createVersionedBinary(id);

        final String mementoUri = createMemento(subjectUri);

        final HttpGet checkFixity = new HttpGet(mementoUri);
        checkFixity.setHeader("Want-Digest", "sha");
        try (final CloseableHttpResponse response = execute(checkFixity)) {
            assertEquals(OK.getStatusCode(), getStatus(response), "Did not get OK response");
            assertTrue(decodeDigestHeader(response.getFirstHeader("Digest").getValue()).containsKey("sha"));
        }
    }

    @Test
    public void testOptionsMemento() throws Exception {
        createVersionedContainer(id);
        final String mementoUri = createMemento(subjectUri);

        final HttpOptions optionsRequest = new HttpOptions(mementoUri);
        try (final CloseableHttpResponse optionsResponse = execute(optionsRequest)) {
            assertEquals(OK.getStatusCode(), optionsResponse.getStatusLine().getStatusCode());
            assertMementoOptionsHeaders(optionsResponse);
        }
    }

    @Test
    public void testPatchOnMemento() throws Exception {
        createVersionedContainer(id);

        final String mementoUri = createMemento(subjectUri);
        final HttpPatch patch = new HttpPatch(mementoUri);
        patch.addHeader(CONTENT_TYPE, "application/sparql-update");
        patch.setEntity(new StringEntity(
                "INSERT DATA { <> <" + title.getURI() + "> \"Memento title\" } "));

        // status 405: PATCH on memento is not allowed.
        assertEquals(405, getStatus(patch));
    }

    @Test
    public void testPatchOnInvalidMementoPath() throws Exception {
        createVersionedContainer(id);

        final String anyMementoUri = subjectUri + "/fcr:versions/any";
        final HttpPatch anyPatch = new HttpPatch(anyMementoUri);
        anyPatch.addHeader(CONTENT_TYPE, "application/sparql-update");
        anyPatch.setEntity(new StringEntity(
                "INSERT DATA { <> <" + title.getURI() + "> \"Memento title\" } "));

        // status 405: PATCH on memento path is not allowed.
        assertEquals(405, getStatus(anyPatch));
    }

    @Test
    public void testPostOnMemento() throws Exception {
        createVersionedContainer(id);

        final String mementoUri = createMemento(subjectUri);
        final String body = createContainerMementoBodyContent(subjectUri, N3);
        final HttpPost post = new HttpPost(mementoUri);
        post.addHeader(CONTENT_TYPE, N3);
        post.setEntity(new StringEntity(body));

        // status 405: POST on memento is not allowed.
        assertEquals(405, getStatus(post));
    }

    @Test
    public void testDeleteOnMemento() throws Exception {
        createVersionedContainer(id);

        final String mementoUri = createMemento(subjectUri);
        final var delete = new HttpDelete(mementoUri);

        // status 405: DELETE on memento is not allowed.
        assertEquals(405, getStatus(delete));
    }

    @Test
    public void testPostOnInvalidMementoPath() throws Exception {
        createVersionedContainer(id);

        final String body = createContainerMementoBodyContent(subjectUri, N3);
        final String anyMementoUri = subjectUri + "/fcr:versions/any";
        final HttpPost anyPost = new HttpPost(anyMementoUri);
        anyPost.addHeader(CONTENT_TYPE, N3);
        anyPost.setEntity(new StringEntity(body));

        // status 405: POST on memento path is not allowed.
        assertEquals(405, getStatus(anyPost));
    }

    @Test
    public void testPutOnMemento() throws Exception {
        createVersionedContainer(id);

        final String mementoUri = createMemento(subjectUri);
        final String body = createContainerMementoBodyContent(subjectUri, N3);
        final HttpPut put = new HttpPut(mementoUri);
        put.addHeader(CONTENT_TYPE, N3);
        put.setEntity(new StringEntity(body));

        // status 405: PUT on memento is not allowed.
        assertEquals(405, getStatus(put));
    }

    @Test
    public void testPutOnInvalidMementoPath() throws Exception {
        createVersionedContainer(id);

        final String body = createContainerMementoBodyContent(subjectUri, N3);
        final String anyMementoUri = subjectUri + "/fcr:versions/any";
        final HttpPut anyPut = new HttpPut(anyMementoUri);
        anyPut.addHeader(CONTENT_TYPE, N3);
        anyPut.setEntity(new StringEntity(body));

        // status 405: PUT on memento path is not allowed.
        assertEquals(405, getStatus(anyPut));
    }

    @Test
    public void testGetLDPRSMementoHeaders() throws Exception {
        createVersionedContainer(id);

        final String version1Uri = createMemento(subjectUri);
        final HttpGet getRequest = new HttpGet(version1Uri);

        try (final CloseableHttpResponse response = execute(getRequest)) {
            assertMementoDatetimeHeaderMatches(response, now());
            checkForLinkHeader(response, MEMENTO_TYPE, "type");
            checkForLinkHeader(response, subjectUri, "original");
            checkForLinkHeader(response, subjectUri, "timegate");
            checkForLinkHeader(response, subjectUri + "/" + FCR_VERSIONS, "timemap");
            checkForLinkHeader(response, RESOURCE.toString(), "type");
            checkForLinkHeader(response, RDF_SOURCE.toString(), "type");
            assertNoLinkHeader(response, VERSIONED_RESOURCE.toString(), "type");
            assertNoLinkHeader(response, VERSIONING_TIMEMAP_TYPE, "type");
            assertNoLinkHeader(response, version1Uri + "/" + FCR_ACL, "acl");
        }
    }

    @Test
    public void testGetLDPNRMementoHeaders() throws Exception {
        createVersionedBinary(id, "text/plain", "This is some versioned content");

        final String version1Uri = createMemento(subjectUri);
        final HttpGet getRequest = new HttpGet(version1Uri);

        try (final CloseableHttpResponse response = execute(getRequest)) {
            assertMementoDatetimeHeaderMatches(response, now());
            checkForLinkHeader(response, MEMENTO_TYPE, "type");
            checkForLinkHeader(response, subjectUri, "original");
            checkForLinkHeader(response, subjectUri, "timegate");
            checkForLinkHeader(response, subjectUri + "/" + FCR_VERSIONS, "timemap");
            checkForLinkHeader(response, NON_RDF_SOURCE.toString(), "type");
            assertNoLinkHeader(response, VERSIONED_RESOURCE.toString(), "type");
            assertNoLinkHeader(response, VERSIONING_TIMEMAP_TYPE, "type");
            assertNoLinkHeader(response, version1Uri + "/" + FCR_ACL, "acl");
        }
    }

    /*
     * Verify binary description timemap RDF representation can be retrieved with and without
     * accompanying binary memento
     */
    @Test
    public void testFcrepo2792() throws Exception {
        // 1. Create versioned resource
        createVersionedBinary(id);

        final String descriptionUri = subjectUri + "/fcr:metadata";
        final String descTimemapUri = descriptionUri + "/" + FCR_VERSIONS;

        // 2. verify that metadata versions endpoint returns 200
        assertEquals(OK.getStatusCode(), getStatus(new HttpGet(descTimemapUri)));

        // 3. create a binary version against binary timemap
        final String mementoUri = createMemento(subjectUri);
        final String descMementoUri = mementoUri.replace("fcr:versions", "fcr:metadata/fcr:versions");

        final Node timemapSubject = createURI(descTimemapUri);
        final Node descMementoResc = createURI(descMementoUri);
        // 4. verify that the binary description timemap RDF is there and contains the new description memento
        try (final CloseableDataset dataset = getDataset(new HttpGet(descTimemapUri))) {
            final DatasetGraph results = dataset.asDatasetGraph();
            assertTrue(results.contains(ANY, timemapSubject, CONTAINS.asNode(), descMementoResc),
                    "Timemap RDF response must contain description memento");
        }

        // Wait a second to avoid timestamp collisions
        TimeUnit.SECONDS.sleep(1);

        // 5. Create a second binary description memento
        final String descMementoUri2 = createMemento(descriptionUri);

        // 6. verify that the binary description timemap availabe (returns 404 in fcrepo-2792)
        try (final CloseableDataset dataset = getDataset(new HttpGet(descTimemapUri))) {
            final DatasetGraph results = dataset.asDatasetGraph();
            final Node descMementoResc2 = createURI(descMementoUri2);

            assertTrue(results.contains(ANY, timemapSubject, CONTAINS.asNode(), descMementoResc),
                    "Timemap RDF response must contain first description memento");
            assertTrue(results.contains(ANY, timemapSubject, CONTAINS.asNode(), descMementoResc2),
                    "Timemap RDF response must contain second description memento");
        }
    }

    @Test
    public void testOptionsTimeMap() throws Exception {
        createVersionedContainer(id);
        final String timemapUri = subjectUri + "/" + FCR_VERSIONS;

        try (final CloseableHttpResponse response = execute(new HttpOptions(timemapUri))) {
            assertEquals(OK.getStatusCode(), getStatus(response));
            verifyTimeMapHeaders(response, subjectUri);
        }
    }

    @Test
    public void testCreateExternalBinaryProxyVersion() throws Exception {
        // Create binary to use as content for proxying
        final String proxyContent = "proxied content";
        final String proxiedId = getRandomUniqueId();
        final String proxiedUri = serverAddress + proxiedId + "/ds";
        createDatastream(proxiedId, "ds", proxyContent);

        // Create the proxied external binary object using the first binary
        createVersionedExternalBinaryMemento(id, "proxy", proxiedUri);

        // Create a version of the external binary using the second binary as content
        final String mementoUri = createMemento(subjectUri);

        // Verify that the historic version exists and proxies the old content
        final HttpGet httpGet1 = new HttpGet(mementoUri);
        try (final CloseableHttpResponse getResponse = execute(httpGet1)) {
            assertEquals(OK.getStatusCode(), getStatus(getResponse));
            assertMementoDatetimeHeaderPresent(getResponse);
            assertEquals(proxiedUri, getContentLocation(getResponse));
            final String content = EntityUtils.toString(getResponse.getEntity());
            assertEquals(proxyContent, content, "Entity Data doesn't match proxied versioned content!");
        }
    }

    @Test
    public void versionedResourcesCreatedByDefault() throws Exception {
        final String id = getRandomUniqueId();
        createObjectAndClose(id);
        final String timemap = id + "/" + FCR_VERSIONS;
        final HttpPost versionPost = postObjMethod(timemap);
        try (final CloseableHttpResponse response = execute(versionPost)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
        }
    }

    @Test
    public void testDeletedResourceMementosAreInaccessible() throws Exception {
        final String id = getRandomUniqueId();
        // Make a container (by default they are auto-versioned)
        createVersionedContainer(id);

        // Make sure the container exists
        final HttpGet getMethod = getObjMethod(id);
        assertEquals(OK.getStatusCode(), getStatus(getMethod));

        // Get the timemap and all the ldp:contains (which are the mementos)
        final List<String> mementos = getMementos(id);
        // There should be only one memento
        assertEquals(1, mementos.size());
        // Get the memento
        final HttpGet getMemento = new HttpGet(mementos.getFirst());
        assertEquals(OK.getStatusCode(), getStatus(getMemento));
        // Delete the original container.
        final HttpDelete deleteContainer = deleteObjMethod(id);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteContainer));
        // Check it is gone
        final HttpGet getDeletedContainer = getObjMethod(id);
        assertEquals(GONE.getStatusCode(), getStatus(getDeletedContainer));
        // Check the timemap still exist
        // The set of mementos might have changed after delete, depending on if it happened the same second as create
        final List<String> mementosAfterDelete = getMementos(id);
        assertTrue(mementosAfterDelete.size() == 1 || mementosAfterDelete.size() == 2);
        // The last memento should be gone
        final HttpGet getDeletedMemento = new HttpGet(mementosAfterDelete.getLast());
        assertEquals(GONE.getStatusCode(), getStatus(getDeletedMemento));
    }

    private List<String> getMementos(final String id) throws IOException {
        final HttpGet getTimemap = getObjMethod(id + "/" + FCR_VERSIONS);
        final List<String> mementos = new ArrayList<>();
        try (final CloseableHttpResponse response = execute(getTimemap)) {
            final Dataset data = getDataset(response);
            final DatasetGraph graph = data.asDatasetGraph();
            assertEquals(OK.getStatusCode(), getStatus(response));
            final var contains = graph.find(Node.ANY, Node.ANY, CONTAINS.asNode(), Node.ANY);
            while (contains.hasNext()) {
                final var memento = contains.next();
                mementos.add(memento.getObject().getURI());
            }
        }
        return mementos;
    }

    @Test
    public void testMementosMaintainContainment() throws Exception {
        final String parentUri;
        final String child1Uri;
        final String child2Uri;
        final String child3Uri;

        try (final CloseableHttpResponse response = execute(postObjMethod())) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            parentUri = getLocation(response);
        }
        TimeUnit.SECONDS.sleep(1);

        try (final var response = execute(new HttpPost(parentUri))) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            child1Uri = getLocation(response);
        }
        final var httpPatch1 = new HttpPatch(parentUri);
        httpPatch1.setHeader(CONTENT_TYPE, "application/sparql-update");
        httpPatch1.setEntity(new StringEntity("INSERT { <> <" + title + "> \"A new title\" . } WHERE {}"));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(httpPatch1));
        final String memento1Uri = createMemento(parentUri);
        TimeUnit.SECONDS.sleep(1);

        try (final var response = execute(new HttpPost(parentUri))) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            child2Uri = getLocation(response);
        }
        final var httpPatch2 = new HttpPatch(parentUri);
        httpPatch2.setHeader(CONTENT_TYPE, "application/sparql-update");
        httpPatch2.setEntity(new StringEntity("DELETE { <> <" + title + "> ?o } INSERT { <> <" + title +
                "> \"A second title\" . } WHERE { <> <" + title + "> ?o }"));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(httpPatch2));
        final String memento2Uri = createMemento(parentUri);
        final Instant memento2Instant = Instant.now();
        TimeUnit.SECONDS.sleep(1);

        try (final var response = execute(new HttpPost(parentUri))) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            child3Uri = getLocation(response);
        }
        final var httpPatch3 = new HttpPatch(parentUri);
        httpPatch3.setHeader(CONTENT_TYPE, "application/sparql-update");
        httpPatch3.setEntity(new StringEntity("DELETE { <> <" + title + "> ?o } INSERT { <> <" + title +
                "> \"A third title\" . } WHERE { <> <" + title + "> ?o }"));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(httpPatch3));
        final String memento3Uri = createMemento(parentUri);
        TimeUnit.SECONDS.sleep(1);

        // Delete child 1
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(child1Uri)));
        // Delete child 2
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(child2Uri)));
        // Delete child 3
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(child3Uri)));

        final Node parentNode = createURI(parentUri);
        final var get1 = new HttpGet(memento1Uri);
        try (final var dataset = getDataset(get1)) {
            final var graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child1Uri)));
            assertFalse(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child2Uri)));
            assertFalse(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child3Uri)));
        }
        final var get2 = new HttpGet(memento2Uri);
        try (final var dataset = getDataset(get2)) {
            final var graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child1Uri)));
            assertTrue(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child2Uri)));
            assertFalse(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child3Uri)));
        }
        final var get3 = new HttpGet(memento3Uri);
        try (final var dataset = getDataset(get3)) {
            final var graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child1Uri)));
            assertTrue(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child2Uri)));
            assertTrue(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child3Uri)));
        }
        final var get4 = new HttpGet(parentUri);
        try (final var dataset = getDataset(get4)) {
            final var graph = dataset.asDatasetGraph();
            assertFalse(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child1Uri)));
            assertFalse(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child2Uri)));
            assertFalse(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child3Uri)));
        }

        // Get the original resource with date/time around memento 2
        final var getWithDateTime = new HttpGet(parentUri);
        getWithDateTime.addHeader(ACCEPT_DATETIME, MEMENTO_RFC_1123_FORMATTER.format(memento2Instant));
        try (final var dataset = getDataset(getWithDateTime)) {
            final var graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child1Uri)));
            assertTrue(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child2Uri)));
            assertFalse(graph.contains(ANY, parentNode, CONTAINS.asNode(), createURI(child3Uri)));
        }
    }

    @Test
    public void testCreateMementoNotAffectModifiedDate() throws Exception {
        final String url;
        try (final var response = execute(postObjMethod())) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            url = getLocation(response);
        }
        final var get1 = new HttpGet(url);
        get1.addHeader(ACCEPT, "application/n-triples");
        final String originalTriples;
        try (final var response = execute(get1)) {
            assertEquals(OK.getStatusCode(), getStatus(response));
            originalTriples = IOUtils.toString(response.getEntity().getContent(), UTF_8);
        }
        // Create a new version without changing anything
        final var postV = new HttpPost(url + "/" + FCR_VERSIONS);
        final String memento;
        try (final var response = execute(postV)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            memento = getLocation(response);
        }
        // Get the memento last modified
        final String mementoTriples;
        final var getMemento = new HttpGet(memento);
        getMemento.addHeader(ACCEPT, "application/n-triples");
        try (final var response = execute(getMemento)) {
            assertEquals(OK.getStatusCode(), getStatus(response));
            mementoTriples = IOUtils.toString(response.getEntity().getContent(), UTF_8);
        }
        // Lastly get the resource last modified again
        final var get2 = new HttpGet(url);
        get2.addHeader(ACCEPT, "application/n-triples");
        final String updatedTriples;
        try (final var response = execute(get2)) {
            updatedTriples = IOUtils.toString(response.getEntity().getContent(), UTF_8);
        }
        // Assert they are all the same
        confirmResponseBodyNTriplesAreEqual(originalTriples, mementoTriples);
        confirmResponseBodyNTriplesAreEqual(updatedTriples, mementoTriples);
    }

    @Test
    public void testPostToDeletedTimemap() throws Exception {
        final String location;
        try (final var response = execute(postObjMethod())) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            location = getLocation(response);
        }
        final var timemap = location + "/" + FCR_VERSIONS;
        // Timemap exists and accepts POSTs
        final var preAccept = List.of("POST","HEAD","GET","OPTIONS").toArray();
        try (final var response = execute(new HttpGet(timemap))) {
            assertEquals(OK.getStatusCode(), getStatus(response));
            final var responseAccept = Arrays.stream(response.getFirstHeader("Allow").getValue().split(","))
                    .map(String::trim).toArray();
            // Assert the Allow header methods.
            assertArrayEquals(responseAccept, preAccept);
        }

        assertEquals(CREATED.getStatusCode(), getStatus(new HttpPost(timemap)));
        // Delete the resource
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(location)));
        assertEquals(GONE.getStatusCode(), getStatus(new HttpGet(location)));
        // After deleting the timemap is still accessible
        final var postAccept = List.of("HEAD","GET","OPTIONS").toArray();
        try (final var response = execute(new HttpGet(timemap))) {
            assertEquals(OK.getStatusCode(), getStatus(response));
            final var responseAccept = Arrays.stream(response.getFirstHeader("Allow").getValue().split(","))
                    .map(String::trim).toArray();
            // Assert the Allow header methods.
            assertArrayEquals(responseAccept, postAccept);
        }
        // But you can't POST
        assertEquals(GONE.getStatusCode(), getStatus(new HttpPost(timemap)));
    }

    private void createVersionedExternalBinaryMemento(final String rescId, final String handling,
            final String externalUri) throws Exception {
        final HttpPut httpPut = putObjMethod(rescId);
        httpPut.addHeader(LINK, "<" + NON_RDF_SOURCE.getURI() + ">;rel=\"type\"");
        httpPut.addHeader(LINK, getExternalContentLinkHeader(externalUri, handling, null));
        try (final CloseableHttpResponse response = execute(httpPut)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response), "Didn't get a CREATED response!");
        }
    }

    private static void assertMementoOptionsHeaders(final HttpResponse httpResponse) {
        final List<String> methods = headerValues(httpResponse, "Allow");
        assertTrue(methods.contains(HttpGet.METHOD_NAME), "Should allow GET");
        assertTrue(methods.contains(HttpHead.METHOD_NAME), "Should allow HEAD");
        assertTrue(methods.contains(HttpOptions.METHOD_NAME), "Should allow OPTIONS");
        assertFalse(methods.contains(HttpDelete.METHOD_NAME), "Should NOT allow DELETE");
    }

    private String createMemento(final String subjectUri) throws Exception {
        final HttpPost createVersionMethod = new HttpPost(subjectUri + "/" + FCR_VERSIONS);

        // Create new memento of resource with updated body
        try (final CloseableHttpResponse response = execute(createVersionMethod)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response), "Didn't get a CREATED response!");
            assertMementoDatetimeHeaderPresent(response);

            return response.getFirstHeader(LOCATION).getValue();
        }
    }

    private String createContainerMementoBodyContent(final String subjectUri, final String contentType)
        throws Exception {
        // Produce new body from current body with changed triple
        final String body;
        final HttpGet httpGet = new HttpGet(subjectUri);
        final Model model = createDefaultModel();
        final Lang rdfLang = RDFLanguages.contentTypeToLang(contentType);
        try (final CloseableHttpResponse response = execute(httpGet)) {
            model.read(response.getEntity().getContent(), "", rdfLang.getName());
        }
        final String resourceUri = subjectUri.replace("/fcr:metadata", "");
        final Resource subjectResc = model.getResource(resourceUri);
        subjectResc.removeAll(TEST_PROPERTY);
        subjectResc.addLiteral(TEST_PROPERTY, "bar");
        subjectResc.addProperty(RDF.type, TEST_TYPE_RESOURCE);

        try (final StringWriter stringOut = new StringWriter()) {
            RDFDataMgr.write(stringOut, model, RDFFormat.NTRIPLES);
            body = stringOut.toString();
        }

        return body;
    }

    /**
     * Create a versioned LDP-RS
     *
     * @param id the desired slug
     * @return Location of the new resource
     * @throws Exception on error
     */
    private String createVersionedContainer(final String id) throws Exception {
        final HttpPost createMethod = postObjMethod();
        createMethod.addHeader("Slug", id);
        createMethod.addHeader(CONTENT_TYPE, N3);
        createMethod.setEntity(new StringEntity("<> <info:test#label> \"foo\""));

        try (final CloseableHttpResponse response = execute(createMethod)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response), "Didn't get a CREATED response!");
            return response.getFirstHeader(LOCATION).getValue();
        }
    }

    private String createVersionedContainerReturnMementoUri(final String id) throws Exception {
        createVersionedContainer(id);

        try (final CloseableDataset dataset = getDataset(getObjMethod(id + "/" + FCR_VERSIONS))) {
            final DatasetGraph results = dataset.asDatasetGraph();
            final var contains = results.find(Node.ANY, Node.ANY, CONTAINS.asNode(), Node.ANY);
            return contains.next().getObject().getURI();
        }
    }

    /**
     * Create a versioned LDP-RS
     *
     * @param id the desired slug
     * @param label label text
     * @param isUpdate whether or not the operation is an update
     * @throws Exception on error
     */
    private void putVersionedContainer(final String id, final String label, final boolean isUpdate) throws Exception {
        final HttpPut createMethod = putObjMethod(id);
        createMethod.addHeader("Slug", id);
        createMethod.addHeader(CONTENT_TYPE, N3);
        createMethod.setEntity(new StringEntity("<> <info:test#label> \"" + label + "\""));

        try (final CloseableHttpResponse response = execute(createMethod)) {
            if (isUpdate) {
                assertEquals(NO_CONTENT.getStatusCode(), getStatus(response), "Didn't get a NO_CONTENT response!");
            } else {
                assertEquals(CREATED.getStatusCode(), getStatus(response), "Didn't get a CREATED response!");
                logger.info("created object: {}", response.getFirstHeader(LOCATION).getValue());
            }
        }
    }

    /**
     * Create a versioned Archival Group
     *
     * @param id the desired slug
     * @return Location of the new resource
     * @throws Exception on error
     */
    private String createVersionedArchivalGroup(final String id) throws Exception {
        final HttpPost createMethod = postObjMethod();
        createMethod.addHeader("Slug", id);
        createMethod.addHeader(CONTENT_TYPE, TURTLE);
        createMethod.addHeader("Link", Link.fromUri(BASIC_CONTAINER.getURI()).rel("type").build().toString());
        createMethod.addHeader("Link", Link.fromUri(ARCHIVAL_GROUP.getURI()).rel("type").build().toString());
        createMethod.setEntity(new StringEntity("<> <info:test#label> \"foo\""));

        try (final CloseableHttpResponse response = execute(createMethod)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response), "Didn't get a CREATED response!");
            return response.getFirstHeader(LOCATION).getValue();
        }
    }

    /**
     * Put a versioned LDP-NR.
     *
     * @param id the desired slug
     * @param mimeType the mimeType of the content
     * @param content the actual content
     * @param isUpdate whether or not the operation is an update
     * @throws Exception on error
     */
    private void putVersionedBinary(final String id, final String mimeType, final String content,
                                    final boolean isUpdate)
            throws Exception {
        final HttpPut createMethod = putObjMethod(id);
        createMethod.addHeader("Slug", id);
        createMethod.addHeader("Link", Link.fromUri(NON_RDF_SOURCE.getURI()).rel("type").build().toString());
        if (mimeType == null && content == null) {
            createMethod.addHeader(CONTENT_TYPE, OCTET_STREAM_TYPE);
            createMethod.setEntity(new StringEntity(BINARY_CONTENT));
        } else {
            createMethod.addHeader(CONTENT_TYPE, mimeType);
            createMethod.setEntity(new StringEntity(content));
        }

        try (final CloseableHttpResponse response = execute(createMethod)) {
            if (isUpdate) {
                assertEquals(NO_CONTENT.getStatusCode(), getStatus(response), "Didn't get a NO_CONTENT response!");
            } else {
                assertEquals(CREATED.getStatusCode(), getStatus(response), "Didn't get a CREATED response!");
                logger.info("created object: {}", response.getFirstHeader(LOCATION).getValue());
            }
        }
    }

    /**
     * Create a versioned LDP-NR.
     *
     * @param id the desired slug
     * @param mimeType the mimeType of the content
     * @param content the actual content
     * @return Location of the new resource
     * @throws Exception on error
     */
    private String createVersionedBinary(final String id, final String mimeType, final String content)
        throws Exception {
        final HttpPost createMethod = postObjMethod();
        createMethod.addHeader("Slug", id);
        if (mimeType == null && content == null) {
            createMethod.addHeader(CONTENT_TYPE, OCTET_STREAM_TYPE);
            createMethod.setEntity(new StringEntity(BINARY_CONTENT));
        } else {
            createMethod.addHeader(CONTENT_TYPE, mimeType);
            createMethod.setEntity(new StringEntity(content));
        }

        try (final CloseableHttpResponse response = execute(createMethod)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response), "Didn't get a CREATED response!");
            logger.info("created object: {}", response.getFirstHeader(LOCATION).getValue());
            return response.getFirstHeader(LOCATION).getValue();
        }
    }

    /**
     * Create a versioned LDP-NR.
     *
     * @param id the desired slug
     * @return Location of the new resource
     * @throws Exception on error
     */
    private String createVersionedBinary(final String id) throws Exception {
        return createVersionedBinary(id, null, null);
    }

    private static void assertNoMementoDatetimeHeaderPresent(final CloseableHttpResponse response) {
        assertNull(response.getFirstHeader(MEMENTO_DATETIME_HEADER),
                "No memento datetime header set in response");
    }

    private static void assertMementoDatetimeHeaderPresent(final CloseableHttpResponse response) {
        assertNotNull(response.getFirstHeader(MEMENTO_DATETIME_HEADER),
                "No memento datetime header set in response");
    }

    private static void assertMementoDatetimeHeaderMatches(final CloseableHttpResponse response,
            final String expected) {
        assertMementoDatetimeHeaderPresent(response);
        assertDuration(expected, response.getFirstHeader(MEMENTO_DATETIME_HEADER).getValue());
    }

    private static void patchLiteralProperty(final String url, final String predicate, final String literal)
            throws IOException {
        patchLiteralProperty(url, predicate, literal, null);
    }
    private static void patchLiteralProperty(final String url, final String predicate, final String literal,
                                             final String xsdType)
            throws IOException {
        final HttpPatch updateObjectGraphMethod = new HttpPatch(url);
        final String type = xsdType != null ? "^^" + xsdType : "";
        updateObjectGraphMethod.addHeader(CONTENT_TYPE, "application/sparql-update");
        updateObjectGraphMethod.setEntity(new StringEntity(
                "INSERT DATA { <> <" + predicate + "> \"" + literal + "\"" + type + " } "));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(updateObjectGraphMethod));
    }

    private CloseableDataset getContent(final String url) throws IOException {
        final HttpGet getVersion = new HttpGet(url);
        getVersion.addHeader("Prefer", "return=representation; include=\"" + EMBED_CONTAINED.toString() + "\"");
        return getDataset(getVersion);
    }

    private String[] getTimeMapResponseTypes() {
        rdfTypes.add(APPLICATION_LINK_FORMAT);
        return rdfTypes.toArray(new String[0]);
    }

    private static void assertMementoUri(final String mementoUri, final String subjectUri) {
        assertTrue(mementoUri.matches(subjectUri + "/fcr:versions/\\d+"));
    }

    private static void assertMementoEqualsOriginal(final String mementoURI) throws Exception {

        final HttpGet getMemento = new HttpGet(mementoURI);
        getMemento.addHeader(ACCEPT, "application/n-triples");

        try (final CloseableHttpResponse response = execute(getMemento)) {
            final HttpGet getOriginal = new HttpGet(getOriginalResourceUri(response));
            getOriginal.addHeader(ACCEPT, "application/n-triples");

            try (final CloseableHttpResponse origResponse = execute(getOriginal)) {

                final String[] mTriples = EntityUtils.toString(response.getEntity()).split("\\.\\r?\\n");
                final String[] oTriples = EntityUtils.toString(origResponse.getEntity()).split("\\.\\r?\\n");

                sort(mTriples);
                sort(oTriples);

                assertArrayEquals(mTriples, oTriples, "Memento and Original Resource triples do not match!");
            }
        }
    }

    private static void assertHasLink(final CloseableHttpResponse response, final Property relation,
            final String uri) {
        final String relName = relation.getLocalName();
        assertTrue(getLinkHeaders(response)
                        .stream().map(Link::valueOf)
                        .anyMatch(l -> relName.equals(l.getRel()) && uri.equals(l.getUri().toString())),
                "Missing link " + relName + " with value " + uri);
    }

    private void checkResponseWithInvalidMementoID(final HttpUriRequest req) throws IOException {
        try (final CloseableHttpResponse response = execute(req)) {
            assertEquals(BAD_REQUEST.getStatusCode(), getStatus(req),
                    "Didn't get status 400 with invalid memento path!");

            // Request must fail with constrained exception due to invalid memento ID
            assertConstrainedByPresent(response);
        }
    }

    private String now() {
        return MEMENTO_RFC_1123_FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC)
                .toInstant().truncatedTo(ChronoUnit.SECONDS));
    }

}
