/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.integration.http.api;

import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static org.fcrepo.http.api.ContentExposingResource.HTTP_HEADER_OVERWRITE_TOMBSTONE;
import static org.fcrepo.kernel.api.FedoraTypes.FCR_TOMBSTONE;
import static org.fcrepo.kernel.api.FedoraTypes.FCR_VERSIONS;
import static org.fcrepo.kernel.api.RdfLexicon.CONTAINS;
import static org.fcrepo.kernel.api.services.VersionService.MEMENTO_LABEL_FORMATTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.HttpHeaders.LINK;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static jakarta.ws.rs.core.Response.Status.GONE;
import static jakarta.ws.rs.core.Response.Status.METHOD_NOT_ALLOWED;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.NO_CONTENT;
import static jakarta.ws.rs.core.Response.Status.OK;

import jakarta.ws.rs.core.Link;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import java.nio.charset.StandardCharsets;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.DatasetGraph;
import org.fcrepo.http.commons.test.util.CloseableDataset;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestExecutionListeners;

/**
 * Tests related to tombstone stuff.
 *
 * @author whikloj
 */
@TestExecutionListeners(
        listeners = { TestIsolationExecutionListener.class },
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public class FedoraTombstonesIT extends AbstractResourceIT {

    private Link getTombstoneLink(final HttpResponse res) {
        final Collection<String> headers = getLinkHeaders(res);
        return headers.stream().map(Link::valueOf).filter(t -> t.getRel().equals("hasTombstone")).findFirst()
                .orElse(null);
    }

    private Link getTombstoneLink(final HttpUriRequest req) throws IOException {
        try (final CloseableHttpResponse response = execute(req)) {
            return getTombstoneLink(response);
        }
    }

    private Instant convertMementoToDate(final String mementoUri) {
        final String[] splits = mementoUri.split(FCR_VERSIONS + "/");
        return Instant.from(MEMENTO_LABEL_FORMATTER.parse(splits[1]));
    }

    @Test
    public void testDeleteObjectAndTombstone() throws IOException {
        final String id = getRandomUniqueId();
        createObjectAndClose(id);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(serverAddress + id)));
        assertDeleted(id);
        final HttpGet httpGet = getObjMethod(id);
        final Link tombstone = getTombstoneLink(httpGet);
        assertEquals("hasTombstone", tombstone.getRel());
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(tombstone.getUri())));
        assertEquals(NOT_FOUND.getStatusCode(), getStatus(httpGet));
    }

    @Test
    public void testTrailingSlashTombstoneLink() throws IOException {
        final String id = getRandomUniqueId();
        final URI expectedTombstone = URI.create(serverAddress + id + "/fcr:tombstone");
        createObjectAndClose(id);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(serverAddress + id)));
        assertDeleted(id);
        final HttpGet get1 = getObjMethod(id);
        final Link tombstone = getTombstoneLink(get1);
        assertEquals("hasTombstone", tombstone.getRel());
        assertEquals(expectedTombstone, tombstone.getUri());
        // Now with a trailing slash
        final HttpGet get2 = getObjMethod(id + "/");
        final Link tombstone2 = getTombstoneLink(get2);
        assertEquals("hasTombstone", tombstone2.getRel());
        assertEquals(expectedTombstone, tombstone2.getUri());
    }

    @Test
    public void testDisallowedMethods() throws Exception {
        final String id = getRandomUniqueId();
        final HttpPut putMethod = putObjMethod(id);
        final HttpEntity body = new StringEntity("<> <http://example.org#title> 'a title'", StandardCharsets.UTF_8);
        putMethod.setEntity(body);
        putMethod.setHeader(CONTENT_TYPE, "text/turtle");
        assertEquals(CREATED.getStatusCode(), getStatus(putMethod));

        final HttpGet getContainer = getObjMethod(id);
        assertEquals(OK.getStatusCode(), getStatus(getContainer));

        final HttpDelete deleteContainer = deleteObjMethod(id);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteContainer));

        final HttpGet getContainer2 = getObjMethod(id);
        final Link tombstoneUri;
        try (final CloseableHttpResponse res = execute(getContainer2)) {
            assertEquals(GONE.getStatusCode(), getStatus(res));
            tombstoneUri = getTombstoneLink(res);
        }

        final var expectedAllowed = "DELETE";
        final HttpOptions optionsTombstone = new HttpOptions(tombstoneUri.getUri());
        try (final var response = execute(optionsTombstone)) {
            assertEquals(OK.getStatusCode(), response.getStatusLine().getStatusCode());
            assertEquals(expectedAllowed, response.getFirstHeader("Allow").getValue());
        }

        final HttpGet getTombstone = new HttpGet(tombstoneUri.getUri());
        try (final var response = execute(getTombstone)) {
            assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), response.getStatusLine().getStatusCode());
            assertEquals(expectedAllowed, response.getFirstHeader("Allow").getValue());
        }

        final HttpPut putTombstone = new HttpPut(tombstoneUri.getUri());
        try (final var response = execute(putTombstone)) {
            assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), response.getStatusLine().getStatusCode());
            assertEquals(expectedAllowed, response.getFirstHeader("Allow").getValue());
        }

        final HttpPost postTombstone = new HttpPost(tombstoneUri.getUri());
        try (final var response = execute(postTombstone)) {
            assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), response.getStatusLine().getStatusCode());
            assertEquals(expectedAllowed, response.getFirstHeader("Allow").getValue());
        }

        final HttpDelete deleteTombstone = new HttpDelete(tombstoneUri.getUri());
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteTombstone));
    }

    @Test
    public void testArchiveGroupDisallowedMethods() throws Exception {
        final HttpPost post = postObjMethod();
        post.setHeader(LINK, "<http://fedora.info/definitions/v4/repository#ArchivalGroup>;rel=\"type\"");
        final String agPath;
        try (final CloseableHttpResponse response = execute(post)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            agPath = getLocation(response);
        }

        final HttpGet getAg = new HttpGet(agPath);
        assertEquals(OK.getStatusCode(), getStatus(getAg));

        final HttpPost postChild = new HttpPost(agPath);
        final String childPath;
        try (final CloseableHttpResponse response = execute(postChild)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            childPath = getLocation(response);
        }

        final HttpGet getChild = new HttpGet(childPath);
        assertEquals(OK.getStatusCode(), getStatus(getChild));

        final HttpDelete deleteChild = new HttpDelete(childPath);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteChild));

        final HttpGet getChild2 = new HttpGet(childPath);
        final Link tombstoneUri;
        try (final CloseableHttpResponse res = execute(getChild2)) {
            assertEquals(GONE.getStatusCode(), getStatus(res));
            tombstoneUri = getTombstoneLink(res);
        }

        final HttpOptions optionsTombstone = new HttpOptions(tombstoneUri.getUri());
        try (final var response = execute(optionsTombstone)) {
            assertEquals(OK.getStatusCode(), response.getStatusLine().getStatusCode());
            assertTrue(response.getFirstHeader("Allow").getValue().isEmpty());
        }

        final HttpGet getTombstone = new HttpGet(tombstoneUri.getUri());
        try (final var response = execute(getTombstone)) {
            assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), response.getStatusLine().getStatusCode());
            assertTrue(response.getFirstHeader("Allow").getValue().isEmpty());
        }

        final HttpPut putTombstone = new HttpPut(tombstoneUri.getUri());
        try (final var response = execute(putTombstone)) {
            assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), response.getStatusLine().getStatusCode());
            assertTrue(response.getFirstHeader("Allow").getValue().isEmpty());
        }

        final HttpPost postTombstone = new HttpPost(tombstoneUri.getUri());
        try (final var response = execute(postTombstone)) {
            assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), response.getStatusLine().getStatusCode());
            assertTrue(response.getFirstHeader("Allow").getValue().isEmpty());
        }

        final HttpDelete deleteTombstone = new HttpDelete(tombstoneUri.getUri());
        assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), getStatus(deleteTombstone));

        final HttpDelete deleteAg = new HttpDelete(agPath);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteAg));

        final HttpDelete deleteAgTombstone = new HttpDelete(agPath + "/" + FCR_TOMBSTONE);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteAgTombstone));
    }

    @Test
    public void testPurgingGrandchildren() throws Exception {
        final HttpPost post = postObjMethod();
        post.setHeader(LINK, "<http://fedora.info/definitions/v4/repository#ArchivalGroup>;rel=\"type\"");
        final String agPath;
        try (final CloseableHttpResponse response = execute(post)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            agPath = getLocation(response);
        }

        final HttpGet get1 = new HttpGet(agPath);
        assertEquals(OK.getStatusCode(), getStatus(get1));

        final HttpPost postChild = new HttpPost(agPath);
        final String childPath;
        try (final CloseableHttpResponse response = execute(postChild)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            childPath = getLocation(response);
        }

        final HttpGet get2 = new HttpGet(childPath);
        assertEquals(OK.getStatusCode(), getStatus(get2));

        final HttpPost postGrandChild = new HttpPost(childPath);
        final String grandchildPath;
        try (final CloseableHttpResponse response = execute(postGrandChild)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            grandchildPath = getLocation(response);
        }

        final HttpGet get3 = new HttpGet(grandchildPath);
        assertEquals(OK.getStatusCode(), getStatus(get3));

        final HttpDelete deleteChild = new HttpDelete(childPath);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteChild));

        final HttpGet get4 = new HttpGet(childPath);
        assertEquals(GONE.getStatusCode(), getStatus(get4));

        final HttpGet get5 = new HttpGet(grandchildPath);
        assertEquals(GONE.getStatusCode(), getStatus(get5));

        final HttpDelete purgeGrandchild = new HttpDelete(grandchildPath + "/" + FCR_TOMBSTONE);
        assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), getStatus(purgeGrandchild));

        final HttpGet get6 = new HttpGet(grandchildPath);
        assertEquals(GONE.getStatusCode(), getStatus(get6));

        final HttpDelete purgeChild = new HttpDelete(childPath + "/" + FCR_TOMBSTONE);
        assertEquals(METHOD_NOT_ALLOWED.getStatusCode(), getStatus(purgeChild));

        final HttpGet get7 = new HttpGet(agPath);
        assertEquals(OK.getStatusCode(), getStatus(get7));

        final HttpDelete purgeBeforeDelete = new HttpDelete(agPath + "/" + FCR_TOMBSTONE);
        assertEquals(NOT_FOUND.getStatusCode(), getStatus(purgeBeforeDelete));

        final HttpDelete deleteAg = new HttpDelete(agPath);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(deleteAg));

        final HttpGet get8 = new HttpGet(agPath);
        assertEquals(GONE.getStatusCode(), getStatus(get8));

        final HttpDelete finalPurge  = new HttpDelete(agPath + "/" + FCR_TOMBSTONE);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(finalPurge));

        final HttpGet finalGet = new HttpGet(agPath);
        assertEquals(NOT_FOUND.getStatusCode(), getStatus(finalGet));
    }

    @Test
    public void testNoChildrenOfTombstone() throws Exception {
        final String uri;
        try (final var response = execute(postObjMethod())) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            uri = getLocation(response);
        }
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(uri)));
        assertEquals(GONE.getStatusCode(), getStatus(new HttpPut(uri)));
        assertEquals(BAD_REQUEST.getStatusCode(), getStatus(new HttpPut(uri + "/" + getRandomUniqueId())));
        assertEquals(BAD_REQUEST.getStatusCode(), getStatus(new HttpPut(uri + "/" + getRandomUniqueId() + "/" +
                getRandomUniqueId())));
    }

    @Test
    public void testTimeMapVisibleOnTombstone() throws Exception {
        final String uri;
        try (final var response = execute(postObjMethod())) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            uri = getLocation(response);
        }
        final var timemapUri = uri + "/" + FCR_VERSIONS;
        TimeUnit.SECONDS.sleep(1);
        final var patchReq = new HttpPatch(uri);
        patchReq.addHeader(CONTENT_TYPE, "application/sparql-update");
        patchReq.setEntity(new StringEntity("INSERT DATA { <> <http://purl.org/dc/elements/1.1/title> \"This is a title\" }"));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(patchReq));
        TimeUnit.SECONDS.sleep(1);
        final var patchReq1 = new HttpPatch(uri);
        patchReq1.addHeader(CONTENT_TYPE, "application/sparql-update");
        patchReq1.setEntity(new StringEntity("INSERT DATA { <> <http://purl.org/dc/elements/1.1/description> \"This is a description\" }"));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(patchReq1));
        // Object should have 3 versions.
        TimeUnit.SECONDS.sleep(1);
        // Delete the object, now it has 4 versions.
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(uri)));
        try (final var response = execute(new HttpGet(uri))) {
            // Assert it is gone.
            assertEquals(GONE.getStatusCode(), getStatus(response));
            // Assert the timemap link header is returned
            checkForLinkHeader(response, timemapUri, "timemap");
        }
        final ArrayList<String> mementos = new ArrayList<>();
        try (final var response = execute(new HttpGet(timemapUri))) {
            // Assert the timemap is accessible
            assertEquals(OK.getStatusCode(), getStatus(response));
            try (final CloseableDataset dataset = getDataset(response)) {
                final DatasetGraph graph = dataset.asDatasetGraph();
                final var iter = graph.find(Node.ANY, Node.ANY, CONTAINS.asNode(), Node.ANY);
                while (iter.hasNext()) {
                    final var memento = iter.next().getObject().getURI();
                    mementos.add(memento);
                }
                assertEquals(4, mementos.size(), 0);
            }
        }
        final Node subjectNode = createResource(uri).asNode();
        // Sort the mementos by datetime.
        mementos.sort((e1, e2) -> convertMementoToDate(e1).compareTo(convertMementoToDate(e2)));
        // The other mementos should return 200 OK.
        for (var foo = 0; foo < mementos.size() - 1; foo += 1) {
            assertEquals(OK.getStatusCode(), getStatus(new HttpGet(mementos.get(foo))));
        }
        // The last is the deleted, which returns the 410 Gone.
        assertEquals(GONE.getStatusCode(), getStatus(new HttpGet(mementos.get(mementos.size() - 1))));
    }

    @Test
    public void testOverwriteTombstoneInteractionModel() throws Exception {
        final HttpPost post = postObjMethod();
        post.setHeader("Link", "<http://fedora.info/definitions/v4/repository#ArchivalGroup>;rel=\"type\"");
        final String parent;
        try (final CloseableHttpResponse response = execute(post)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            parent = getLocation(response);
        }
        assertEquals(OK.getStatusCode(), getStatus(new HttpGet(parent)));

        final HttpPost postChild = new HttpPost(parent);
        final String id;
        try (final CloseableHttpResponse response = execute(postChild)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            id = getLocation(response);
        }
        assertEquals(OK.getStatusCode(), getStatus(new HttpGet(id)));

        final HttpDelete delete = new HttpDelete(id);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(delete));

        final HttpPut put = new HttpPut(id);
        put.setHeader(LINK, NON_RDF_SOURCE_LINK_HEADER);
        put.addHeader(HTTP_HEADER_OVERWRITE_TOMBSTONE, "true");
        assertEquals(CONFLICT.getStatusCode(), getStatus(put));
    }

    @Test
    public void testOverwriteArchivalGroupTombstone() throws IOException {
        final var agHeader = "<http://fedora.info/definitions/v4/repository#ArchivalGroup>;rel=\"type\"";
        final HttpPost post = postObjMethod();
        post.setHeader(LINK, agHeader);
        final String parent;
        try (final CloseableHttpResponse response = execute(post)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            parent = getLocation(response);
        }
        assertEquals(OK.getStatusCode(), getStatus(new HttpGet(parent)));

        final HttpDelete delete = new HttpDelete(parent);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(delete));

        final HttpPut put = new HttpPut(parent);
        put.setHeader(LINK, agHeader);
        put.addHeader(HTTP_HEADER_OVERWRITE_TOMBSTONE, "true");
        assertEquals(CREATED.getStatusCode(), getStatus(put));
    }

    @Test
    public void testOverwriteArchivalGroupInteractionModel() throws IOException {
        final var agHeader = "<http://fedora.info/definitions/v4/repository#ArchivalGroup>;rel=\"type\"";
        final HttpPost post = postObjMethod();
        post.setHeader(LINK, agHeader);
        final String parent;
        try (final CloseableHttpResponse response = execute(post)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            parent = getLocation(response);
        }
        assertEquals(OK.getStatusCode(), getStatus(new HttpGet(parent)));

        final HttpDelete delete = new HttpDelete(parent);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(delete));

        final HttpPut put = new HttpPut(parent);
        put.setHeader(LINK, BASIC_CONTAINER_LINK_HEADER);
        put.addHeader(HTTP_HEADER_OVERWRITE_TOMBSTONE, "true");
        assertEquals(CONFLICT.getStatusCode(), getStatus(put));
    }

    @Test
    public void testChangeContainerToArchivalGroup() throws IOException {
        final var agHeader = "<http://fedora.info/definitions/v4/repository#ArchivalGroup>;rel=\"type\"";
        final HttpPost post = postObjMethod();
        post.setHeader(LINK, BASIC_CONTAINER_LINK_HEADER);
        final String parent;
        try (final CloseableHttpResponse response = execute(post)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            parent = getLocation(response);
        }
        assertEquals(OK.getStatusCode(), getStatus(new HttpGet(parent)));

        final HttpDelete delete = new HttpDelete(parent);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(delete));

        final HttpPut put = new HttpPut(parent);
        put.setHeader(LINK, agHeader);
        put.addHeader(HTTP_HEADER_OVERWRITE_TOMBSTONE, "true");
        assertEquals(CONFLICT.getStatusCode(), getStatus(put));
    }
}
