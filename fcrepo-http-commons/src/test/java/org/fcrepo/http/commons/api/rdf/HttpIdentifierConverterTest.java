/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.commons.api.rdf;

import static org.fcrepo.kernel.api.FedoraTypes.FCR_ACL;
import static org.fcrepo.kernel.api.FedoraTypes.FCR_METADATA;
import static org.fcrepo.kernel.api.FedoraTypes.FCR_VERSIONS;
import static org.fcrepo.kernel.api.FedoraTypes.FEDORA_ID_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.ws.rs.core.UriBuilder;

import java.util.UUID;

/**
 * @author whikloj
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class HttpIdentifierConverterTest {

    private HttpIdentifierConverter converter;

    private static final String uriBase = "http://localhost:8080/some";

    private static final String uriTemplate = uriBase + "/{path: .*}";

    private UriBuilder uriBuilder;

    @BeforeEach
    public void setUp() {
        uriBuilder = UriBuilder.fromUri(uriTemplate);
        converter = new HttpIdentifierConverter(uriBuilder);
    }

    @Test
    public void testBlankUri() {
        final String testUri = "";
        assertThrows(IllegalArgumentException.class, () -> converter.toInternalId(testUri));
    }

    /**
     * Test that a blank string toDomain becomes a /
     */
    @Test
    public void testBlankToDomain() {
        final String testUri = "";
        final String fedoraUri = converter.toDomain(testUri);
        assertEquals(uriBase + "/", fedoraUri);
    }

    @Test
    public void testBlankId() {
        final String testId = "";
        assertThrows(IllegalArgumentException.class, () -> converter.toExternalId(testId));
    }

    @Test
    public void testinExternalDomainSuccess() {
        final String testURI = uriBase + "/someurl/thatWeWant";
        assertTrue(converter.inExternalDomain(testURI));
    }

    @Test
    public void testinExternalDomainFailure() {
        final String testURI = "http://someplace.com/whatHappened";
        assertFalse(converter.inExternalDomain(testURI));
    }

    @Test
    public void testinInternalDomainSuccess() {
        final String testID = FEDORA_ID_PREFIX + "/myLittleResource";
        assertTrue(converter.inInternalDomain(testID));
    }

    @Test
    public void testinInternalDomainFailure() {
        final String testID = "info:test/myLittleResource";
        assertFalse(converter.inInternalDomain(testID));
    }

    @Test
    public void testRootUriWithTrailingSlash() {
        final String testUri = uriBase + "/";
        final String fedoraId = converter.toInternalId(testUri);
        assertEquals(FEDORA_ID_PREFIX, fedoraId);
        final String httpUri = converter.toExternalId(fedoraId);
        assertEquals(testUri, httpUri);
    }

    @Test
    public void testRootUriWithoutTrailingSlash() {
        final String testUri = uriBase;
        final String fedoraId = converter.toInternalId(testUri);
        assertEquals(FEDORA_ID_PREFIX, fedoraId);
        final String httpUri = converter.toExternalId(fedoraId);
        // We also return the trailing slash.
        assertEquals(testUri + "/", httpUri);
    }

    @Test
    public void testFirstLevel() {
        final String baseUid = getUniqueId();
        final String testUri = uriBase + "/" + baseUid;
        final String fedoraId = converter.toInternalId(testUri);
        assertEquals(FEDORA_ID_PREFIX + "/" + baseUid, fedoraId);
        final String httpUri = converter.toExternalId(fedoraId);
        assertEquals(testUri, httpUri);
    }

    @Test
    public void testFirstLevelExternalPath() {
        final String baseUid = getUniqueId();
        final String testUri = "/" + baseUid;
        final String fedoraId = converter.toInternalId(converter.toDomain(testUri));
        assertEquals(FEDORA_ID_PREFIX + "/" + baseUid, fedoraId);
        final String httpUri = converter.toExternalId(fedoraId);
        assertEquals(uriBase + testUri, httpUri);
    }

    @Test
    public void testFirstLevelWithAcl() {
        final String testUri = "/" + getUniqueId() + "/" + FCR_ACL;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testFirstLevelWithMetadata() {
        final String baseUid = getUniqueId();
        final String testUri = "/" + baseUid + "/" + FCR_METADATA;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testFirstLevelWithVersions() {
        final String baseUid = getUniqueId();
        final String testUri = "/" + baseUid + "/" + FCR_VERSIONS;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testFirstLevelWithMemento() {
        final String memento = "20190926133245";
        final String baseUid = getUniqueId();
        final String testUri = "/" + baseUid + "/" + FCR_VERSIONS + "/" + memento;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testSecondLevel() {
        final String testUri = "/" + getUniqueId() + "/" + getUniqueId();
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testSecondLevelWithAcl() {
        final String baseUid = "/" + getUniqueId() + "/" + getUniqueId();
        final String testUri = baseUid + "/" + FCR_ACL;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testSecondLevelWithMetadata() {
        final String baseUid = "/" + getUniqueId() + "/" + getUniqueId();
        final String testUri = baseUid + "/" + FCR_METADATA;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testSecondLevelWithVersions() {
        final String baseUid = "/" + getUniqueId() + "/" + getUniqueId();
        final String testUri = baseUid + "/" + FCR_VERSIONS;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testSecondLevelWithMemento() {
        final String memento = "20190926133245";
        final String baseUid = "/" + getUniqueId() + "/" + getUniqueId();
        final String testUri = baseUid + "/" + FCR_VERSIONS + "/" + memento;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    @Test
    public void testItemWithDoubleAcl() {
        final String baseUid = getUniqueId();
        final String testUri = "/" + baseUid + "/" + FCR_ACL + "/" + FCR_ACL;
        final String external = uriBase + testUri;
        final String internal = FEDORA_ID_PREFIX + testUri;
        final String fedoraId = converter.toInternalId(external);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(external, httpUri);
    }

    /**
     * We decode on the way in, but don't re-encode on the way back out. This is the same as Fedora 5.1.0
     */
    @Test
    public void testWithEncodedColon() {
        final String externalOriginal = uriBase + "/some%3Atest";
        final String internal = FEDORA_ID_PREFIX + "/some:test";
        final String externalNew = uriBase + "/some:test";
        final String fedoraId = converter.toInternalId(externalOriginal);
        assertEquals(internal, fedoraId);
        final String httpUri = converter.toExternalId(internal);
        assertEquals(externalNew, httpUri);
    }


    @Test
    public void testBlankPathToId() {
        final String testUri = "";
        final FedoraId fedoraUri = converter.pathToInternalId(testUri);
        assertEquals(FedoraId.getRepositoryRootId(), fedoraUri);
    }

    @Test
    public void testSinglePathToId() {
        final String externalOriginal = "/object";
        final FedoraId internal = FedoraId.create("object");
        final FedoraId id = converter.pathToInternalId(externalOriginal);
        assertEquals(internal, id);
        // Without leading slash
        final String externalOriginal2 = "object";
        final FedoraId id2 = converter.pathToInternalId(externalOriginal2);
        assertEquals(internal, id2);
    }

    @Test
    public void testDoublePathToId() {
        final String externalOriginal = "/object/child";
        final FedoraId internal = FedoraId.create("object", "child");
        final FedoraId id = converter.pathToInternalId(externalOriginal);
        assertEquals(internal, id);
        // Without leading slash
        final String externalOriginal2 = "object/child";
        final FedoraId id2 = converter.pathToInternalId(externalOriginal2);
        assertEquals(internal, id2);
    }

    /**
     * Test some characters that should remain the same when part of the path
     */
    @Test
    public void testQueryParamCharacters() {
        final String id = "+&";
        final String testUri = uriBase + "/" + id;
        final String fedoraId = converter.toInternalId(testUri);
        final String expectedId = FEDORA_ID_PREFIX + "/" + id;
        assertEquals(expectedId, fedoraId);
    }

    /**
     * Test encoded versions characters that should remain the same when part of the path
     */
    @Test
    public void testEncodedQueryParamCharacters() {
        final String encoded = "%2B%26";
        final String testUri = uriBase + "/" + encoded;
        final String fedoraId = converter.toInternalId(testUri);
        final String expectedId = FEDORA_ID_PREFIX + "/+&";
        assertEquals(expectedId, fedoraId);
    }

    @Test
    public void testTranslateInternalUri() {
        final String testUri = "/some/uri";
        assertEquals(uriBase + "/uri", converter.translateUri(testUri));
    }

    @Test
    public void testTranslateExternalUri() {
        final String testUri = "http://example.com/some/uri";
        assertEquals(testUri, converter.translateUri(testUri));
    }

    @Test
    public void testBuildHashUri() {
        final String testUri = "/uri#hashuri";
        assertEquals(uriBase + testUri, converter.toDomain(testUri));
    }

    /**
     * Utility function to get a UUID.
     * @return a UUID.
     */
    private static String getUniqueId() {
        return UUID.randomUUID().toString();
    }
}
