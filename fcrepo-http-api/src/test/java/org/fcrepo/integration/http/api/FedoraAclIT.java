/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.integration.http.api;

import static jakarta.ws.rs.core.HttpHeaders.ACCEPT;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.HttpHeaders.LINK;
import static jakarta.ws.rs.core.Link.fromUri;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.NO_CONTENT;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.apache.jena.graph.Node.ANY;

import static org.apache.jena.graph.NodeFactory.createLiteral;
import static org.apache.jena.graph.NodeFactory.createURI;
import static org.apache.jena.vocabulary.RDF.type;
import static org.fcrepo.kernel.api.FedoraTypes.FCR_ACL;
import static org.fcrepo.kernel.api.RdfLexicon.CONSTRAINED_BY;
import static org.fcrepo.kernel.api.RdfLexicon.RDF_SOURCE;
import static org.fcrepo.kernel.api.RdfLexicon.WEBAC_NAMESPACE_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import jakarta.ws.rs.core.Link;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.DatasetGraph;
import org.fcrepo.http.commons.test.util.CloseableDataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestExecutionListeners;

/**
 * @author lsitu
 * @author 4/20/2018
 */
@TestExecutionListeners(
        listeners = { TestIsolationExecutionListener.class },
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public class FedoraAclIT extends AbstractResourceIT {

    private String subjectUri;
    private String id;

    @BeforeEach
    public void init() {
        id = getRandomUniqueId();
        subjectUri = serverAddress + id;
        authPropsConfig.setRootAuthAclPath(null);
    }

    @Test
    public void testCreateAclWithoutBody() throws Exception {
        createObjectAndClose(id);

        final HttpPut put = new HttpPut(subjectUri + "/" + FCR_ACL);
        final String aclLocation;
        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            aclLocation = response.getFirstHeader("Location").getValue();
            // verify the acl container is translated to fcr:acl
            assertEquals(subjectUri + "/" + FCR_ACL, aclLocation);
        }
    }

    @Test
    public void testCreateAclOnAclResource() throws Exception {
        createObjectAndClose(id);

        final String aclLocation = createACL();

        final HttpPut put1 = new HttpPut(aclLocation + "/" + FCR_ACL);
        assertEquals(BAD_REQUEST.getStatusCode(), getStatus(put1));
    }

    private String createACL() throws IOException {
        final HttpPut put = new HttpPut(subjectUri + "/" + FCR_ACL);

        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            return response.getFirstHeader("Location").getValue();
        }
    }

    @Test
    public void testCreateAclOnBinary() throws Exception {
        createDatastream(id, "x", "some content");

        final HttpPut put = new HttpPut(subjectUri + "/x/" + FCR_ACL);
        final String aclLocation;
        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            aclLocation = response.getFirstHeader("Location").getValue();
            // verify the acl container for binary is translated to fcr:acl
            assertEquals(subjectUri + "/x/" + FCR_ACL, aclLocation);
        }

        try (final CloseableDataset dataset = getDataset(new HttpGet(aclLocation))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                                      createURI(aclLocation),
                                      type.asNode(),
                                      RDF_SOURCE.asNode()));
        }
    }

    @Test
    public void testPatchAcl() throws Exception {
        createObjectAndClose(id);
        final String aclURI = createACL();
        final HttpPatch patch = new HttpPatch(aclURI);
        patch.addHeader(CONTENT_TYPE, "application/sparql-update");
        patch.setEntity(new StringEntity("PREFIX acl: <http://www.w3.org/ns/auth/acl#> " +
                                         "INSERT { <#writeAccess> acl:mode acl:Write . } WHERE { }"));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(patch));

        //verify the patch worked
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclURI))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                                      createURI(aclURI + "#writeAccess"),
                                      createURI("http://www.w3.org/ns/auth/acl#mode"),
                                      createURI("http://www.w3.org/ns/auth/acl#Write")));
        }

    }

    @Test
    public void testPatchAclDelete() throws Exception {
        final String aclURI = subjectUri + "/" + FCR_ACL;
        createObjectAndClose(id);
        final String aclBody = "@prefix acl: <http://www.w3.org/ns/auth/acl#> . " +
                "<#authorization> a acl:Authorization ; acl:agent \"user3\" ; acl:mode acl:Read ; " +
                "acl:accessTo <" + subjectUri + "> ; acl:default <" + subjectUri + "> .";
        final HttpPut putAcl = putAcl(id + "/" + FCR_ACL, aclBody);
        assertEquals(CREATED.getStatusCode(), getStatus(putAcl));

        final Node subjectNode = createURI(subjectUri);
        final Node authNode = createURI(aclURI + "#authorization");
        final Node modeNode = createURI("http://www.w3.org/ns/auth/acl#mode");
        final Node writeNode = createURI("http://www.w3.org/ns/auth/acl#Read");
        final Node accessToNode = createURI("http://www.w3.org/ns/auth/acl#accessTo");
        final Node defaultNode = createURI("http://www.w3.org/ns/auth/acl#default");
        // Verify initial state
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclURI))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                    authNode,
                    modeNode,
                    writeNode));
            assertTrue(graph.contains(ANY,
                    authNode,
                    accessToNode,
                    subjectNode));
            assertTrue(graph.contains(ANY,
                    authNode,
                    defaultNode,
                    subjectNode));
        }

        final HttpPatch patch = new HttpPatch(aclURI);
        patch.addHeader(CONTENT_TYPE, "application/sparql-update");
        patch.setEntity(new StringEntity("PREFIX acl: <http://www.w3.org/ns/auth/acl#> " +
                "DELETE { <#authorization> acl:default <" + subjectUri + "> . } WHERE { }"));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(patch));

        // verify the patch worked
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclURI))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                    authNode,
                    modeNode,
                    writeNode));
            assertTrue(graph.contains(ANY,
                    authNode,
                    accessToNode,
                    subjectNode));
            assertFalse(graph.contains(ANY,
                    authNode,
                    defaultNode,
                    subjectNode));
        }
    }

    @Test
    public void testCreateAndRetrieveAcl() throws Exception {
        createObjectAndClose(id);

        final HttpPut put = new HttpPut(subjectUri + "/" + FCR_ACL);
        final String aclLocation;
        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            aclLocation = response.getFirstHeader("Location").getValue();
            // verify the acl container is translated to fcr:acl
            assertEquals(subjectUri + "/" + FCR_ACL, aclLocation);
        }

        final HttpGet get = new HttpGet(aclLocation);
        assertEquals(OK.getStatusCode(), getStatus(get));

    }

    @Test
    public void testPutACLBadRdf() throws IOException {
        createObjectAndClose(id);

        final HttpPut put = putAcl(subjectUri + "/" + FCR_ACL, "<> a junk:Object ;");
        assertEquals(BAD_REQUEST.getStatusCode(), getStatus(put));
    }

    @Test
    public void testDeleteAcl() throws Exception {
        createObjectAndClose(id);

        final HttpPut put = new HttpPut(subjectUri + "/" + FCR_ACL);
        final String aclLocation;
        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            aclLocation = response.getFirstHeader("Location").getValue();
            // verify the acl container is translated to fcr:acl
            assertEquals(subjectUri + "/" + FCR_ACL, aclLocation);
        }

        final HttpGet get = new HttpGet(aclLocation);
        assertEquals(OK.getStatusCode(), getStatus(get));

        final HttpDelete delete = new HttpDelete(aclLocation);
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(delete));

        final HttpGet getNotFound = new HttpGet(aclLocation);
        assertEquals(NOT_FOUND.getStatusCode(), getStatus(getNotFound));

    }

    @Test
    public void testGetNonExistentAcl() {
        createObjectAndClose(id);
        final HttpGet getNotFound = new HttpGet(subjectUri + "/" + FCR_ACL);
        assertEquals(NOT_FOUND.getStatusCode(), getStatus(getNotFound));

    }

    @Test
    public void testGetDefaultRootAcl() throws Exception {
        final String rootAclUri = serverAddress + FCR_ACL;
        final String rootFedoraUri = serverAddress;
        final String authzUri = rootFedoraUri + FCR_ACL + "#authz";
        try (final CloseableDataset dataset = getDataset(new HttpGet(rootAclUri))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                    createURI(authzUri),
                    createURI("http://www.w3.org/2000/01/rdf-schema#label"),
                    createLiteral("Root Authorization")));

            assertTrue(graph.contains(ANY,
                    createURI(authzUri),
                    createURI(WEBAC_NAMESPACE_VALUE + "default"),
                    createURI(rootFedoraUri)));

            assertTrue(graph.contains(ANY,
                    createURI(authzUri),
                    createURI(WEBAC_NAMESPACE_VALUE + "accessTo"),
                    createURI(rootFedoraUri)));

            assertTrue(graph.contains(ANY,
                    createURI(authzUri),
                    createURI(WEBAC_NAMESPACE_VALUE + "mode"),
                    createURI(WEBAC_NAMESPACE_VALUE + "Read")));
        }
    }

    @Test
    public void testDeleteDefaultRootAcl() {
        final String rootAclUri = serverAddress + FCR_ACL;
        assertEquals(CONFLICT.getStatusCode(), getStatus(new HttpDelete(rootAclUri)),
                "DELETE should fail for default generated root ACL.");
    }

    @Test
    public void testPatchDefaultRootAcl() {
        final String rootAclUri = serverAddress + FCR_ACL;
        assertEquals(CONFLICT.getStatusCode(), getStatus(new HttpPatch(rootAclUri)),
                "PATCH should fail for default generated root ACL.");
    }

    @Test
    public void testGetUserDefinedDefaultRootAcl() throws Exception {
        authPropsConfig.setRootAuthAclPath(Paths.get("./target/test-classes/test-root-authorization.ttl"));
        final String rootAclUri = serverAddress + FCR_ACL;
        try (final CloseableDataset dataset = getDataset(new HttpGet(rootAclUri))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                                      createURI(rootAclUri),
                                      createURI("http://www.w3.org/2000/01/rdf-schema#label"),
                                      createLiteral("(Test) Root ACL")));

            assertTrue(graph.contains(ANY,
                                      createURI(rootAclUri),
                                      createURI(WEBAC_NAMESPACE_VALUE + "default"),
                                      createURI(serverAddress)));
            }
    }

    @Test
    public void testAddModifyDeleteUserDefinedDefaultRootAcl() throws Exception {
        final String rootAclUri = serverAddress + FCR_ACL;
        final String aclBody = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n" +
                               "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n" +
                               "@prefix ldp: <http://www.w3.org/ns/ldp#> .\n" +
                               "\n" +
                               "<#readAccess> a acl:Authorization ;\n" +
                               "    acl:mode acl:Read .";
        final HttpPut put = putAcl(rootAclUri, aclBody);

        // Test PUT
        assertEquals(CREATED.getStatusCode(), getStatus(put),
                "PUT a new ACL should succeed.");

        // Test PATCH
        final HttpPatch patch = new HttpPatch(rootAclUri);
        patch.addHeader(CONTENT_TYPE, "application/sparql-update");
        patch.setEntity(new StringEntity("PREFIX acl: <http://www.w3.org/ns/auth/acl#> " +
                                            "INSERT { <#readAccess> acl:mode acl:Write . } WHERE { }"));
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(patch),
                "PATCH should succeed for default generated root ACL.");

        // Test DELETE
        assertEquals(NO_CONTENT.getStatusCode(), getStatus(new HttpDelete(rootAclUri)),
                "DELETE should succeed for user-defined default root ACL.");
    }

    @Test
    public void testCreateAclWithBody() throws Exception {
        createObjectAndClose(id);

        final String aclBody = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n" +
                               "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n" +
                               "@prefix ldp: <http://www.w3.org/ns/ldp#> .\n" +
                               "\n" +
                               "<#readAccess> a acl:Authorization ;\n" +
                               "    acl:mode acl:Read .";
        final HttpPut put = putAcl(subjectUri + "/" + FCR_ACL, aclBody);

        final String aclLocation;
        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            aclLocation = response.getFirstHeader("Location").getValue();
            // verify the acl container is translated to fcr:acl
            assertEquals(subjectUri + "/" + FCR_ACL, aclLocation);

        }

        //verify the put worked
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclLocation))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                                      createURI(aclLocation + "#readAccess"),
                                      createURI("http://www.w3.org/ns/auth/acl#mode"),
                                      createURI("http://www.w3.org/ns/auth/acl#Read")));
        }

    }

    @Test
    public void testCreateAclWithoutAccessToSetsDefaultTarget() throws Exception {
        createObjectAndClose(id);

        final String aclBody = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n" +
                               "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n" +
                               "@prefix ldp: <http://www.w3.org/ns/ldp#> .\n" +
                               "\n" +
                               "<#readAccess> a acl:Authorization ;\n" +
                               "    acl:mode acl:Read .";
        final HttpPut put = putAcl(subjectUri + "/" + FCR_ACL, aclBody);

        final String aclLocation;
        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            aclLocation = response.getFirstHeader("Location").getValue();
            // verify the acl container is translated to fcr:acl
            assertEquals(subjectUri + "/" + FCR_ACL, aclLocation);
        }

        // verify that the accessTo is set to subjectUri when no accessTo or accessToClass
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclLocation))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                                      createURI(aclLocation + "#readAccess"),
                                      createURI("http://www.w3.org/ns/auth/acl#accessTo"),
                                      createURI(subjectUri)));
        }

    }

    @Test
    public void testCreateAclWithAccessTo() throws Exception {
        createObjectAndClose(id);

        final String aclBody = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n" +
                               "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n" +
                               "@prefix ldp: <http://www.w3.org/ns/ldp#> .\n" +
                               "\n" +
                               "<#readAccess> a acl:Authorization ;\n" +
                               "    acl:mode acl:Read ;\n" +
                               "    acl:accessTo <http://example.com/> .";
        final HttpPut put = putAcl(subjectUri + "/" + FCR_ACL, aclBody);

        final String aclLocation;
        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            aclLocation = response.getFirstHeader("Location").getValue();
            // verify the acl container is translated to fcr:acl
            assertEquals(subjectUri + "/" + FCR_ACL, aclLocation);
        }

        // verify that the accessTo is set to the sepcified accessTo Target
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclLocation))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                                      createURI(aclLocation + "#readAccess"),
                                      createURI("http://www.w3.org/ns/auth/acl#accessTo"),
                                      createURI("http://example.com/")));
        }

        // verify that the accessTo is not set to subjectUri (default)
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclLocation))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertFalse(graph.contains(ANY,
                                      createURI(aclLocation + "#readAccess"),
                                      createURI("http://www.w3.org/ns/auth/acl#accessTo"),
                                      createURI(subjectUri)));
        }
    }

    @Test
    public void testCreateAclWithAccessToClass() throws Exception {
        createObjectAndClose(id);

        final String aclBody = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n" +
                "@prefix webac: <http://fedora.info/definitions/v4/webac#> .\n" +
                "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n" +
                "@prefix ldp: <http://www.w3.org/ns/ldp#> .\n" +
                "\n" +
                "<#readAccess> a acl:Authorization ;\n" +
                "    acl:mode acl:Read ;\n" +
                "    acl:accessToClass webac:Acl .";
        final HttpPut put = putAcl(subjectUri + "/" + FCR_ACL, aclBody);

        final String aclLocation;
        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(CREATED.getStatusCode(), getStatus(response));
            aclLocation = response.getFirstHeader("Location").getValue();
            // verify the acl container is translated to fcr:acl
            assertEquals(subjectUri + "/" + FCR_ACL, aclLocation);

        }

        // verify that the accessToClass is set to the specified accessToClass
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclLocation))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertTrue(graph.contains(ANY,
                                      createURI(aclLocation + "#readAccess"),
                                      createURI("http://www.w3.org/ns/auth/acl#accessToClass"),
                                      createURI("http://fedora.info/definitions/v4/webac#Acl")));
        }

        // verify that the accessTo is not set to subjectUri (default)
        try (final CloseableDataset dataset = getDataset(new HttpGet(aclLocation))) {
            final DatasetGraph graph = dataset.asDatasetGraph();
            assertFalse(graph.contains(ANY,
                                      createURI(aclLocation + "#readAccess"),
                                      createURI("http://www.w3.org/ns/auth/acl#accessTo"),
                                      createURI(subjectUri)));
        }
    }

    @Test
    public void testCreateAclWithBothAccessToandAccessToClassIsNotAllowed() throws Exception {
        createObjectAndClose(id);

        final HttpPut put = new HttpPut(subjectUri + "/" + FCR_ACL);
        final String aclBody = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n" +
                               "@prefix webac: <http://fedora.info/definitions/v4/webac#> .\n" +
                               "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n" +
                               "@prefix ldp: <http://www.w3.org/ns/ldp#> .\n" +
                               "\n" +
                               "<#readAccess> a acl:Authorization ;\n" +
                               "    acl:mode acl:Read ;\n" +
                               "    acl:accessTo <http://example.com/> ;\n" +
                               "    acl:accessToClass webac:Acl .";
        final Link ex = fromUri(URI.create(serverAddress +
                                    "static/constraints/ACLAuthorizationConstraintViolationException.rdf"))
                               .rel(CONSTRAINED_BY.getURI()).build();

        put.setEntity(new StringEntity(aclBody));
        put.setHeader("Content-Type", "text/turtle");

        try (final CloseableHttpResponse response = execute(put)) {
            assertEquals(BAD_REQUEST.getStatusCode(), getStatus(response));
            assertEquals(ex.toString(), response.getFirstHeader(LINK).getValue());
        }
    }

    @Test
    public void testGetDefaultAcl() throws Exception {
        final Node aclMode = createURI(WEBAC_NAMESPACE_VALUE + "mode");
        final Node aclRead = createURI(WEBAC_NAMESPACE_VALUE + "Read");
        final Node aclWrite = createURI(WEBAC_NAMESPACE_VALUE + "Write");

        final Node defaultAclSubject = createURI(serverAddress + FCR_ACL + "#authz");

        final var getTurtle = getObjMethod(FCR_ACL);
        getTurtle.addHeader(ACCEPT, "text/turtle");
        try (final var response = execute(getTurtle)) {
            assertEquals(OK.getStatusCode(), getStatus(response));
            final var graph = getDataset(response).asDatasetGraph();
            assertTrue(graph.contains(ANY, defaultAclSubject, aclMode, aclRead));
            assertFalse(graph.contains(ANY, defaultAclSubject, aclMode, aclWrite));
        }

        final var getHtml = getObjMethod(FCR_ACL);
        getHtml.addHeader(ACCEPT, "text/html");
        assertEquals(OK.getStatusCode(), getStatus(getHtml));
    }

    /**
     * Create a HttpPut with a text/turtle body
     * @param url The URL to PUT to
     * @param aclBody The body of the PUT request
     * @return HttpPut
     */
    private HttpPut putAcl(final String url, final String aclBody) {
        final String aclLocation = (!url.startsWith(serverAddress) ? serverAddress : "") + url;
        final HttpPut put = new HttpPut(aclLocation);
        put.setEntity(new StringEntity(aclBody, StandardCharsets.UTF_8));
        put.setHeader("Content-Type", "text/turtle");
        return put;
    }
}
