/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.api.utils;

import static java.net.URI.create;
import static org.fcrepo.config.DigestAlgorithm.SHA1;
import static org.fcrepo.kernel.api.utils.ContentDigest.asURI;
import static org.fcrepo.kernel.api.utils.ContentDigest.getAlgorithm;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fcrepo.config.DigestAlgorithm;

import org.junit.jupiter.api.Test;

/**
 * <p>ContentDigestTest class.</p>
 *
 * @author ksclarke
 */
public class ContentDigestTest {

    @Test
    public void testSHA_1() {
        assertEquals(create("urn:sha1:fake"), asURI(SHA1.getAlgorithm(), "fake"),
                "Failed to produce a proper content digest URI!");
    }

    @Test
    public void testSHA1() {
        assertEquals(create("urn:sha1:fake"), asURI("SHA", "fake"),
                "Failed to produce a proper content digest URI!");
    }

    @Test
    public void testGetAlgorithm() {
        assertEquals(SHA1.getAlgorithm(), getAlgorithm(asURI(SHA1.getAlgorithm(), "fake")),
                "Failed to produce a proper digest algorithm!");
    }

    @Test
    public void testSHA256() {
        assertEquals(create("urn:sha-256:fake"), asURI("SHA-256", "fake"),
                "Failed to produce a proper content digest URI!");
    }

    @Test
    public void testMissingAlgorithm() {
        assertEquals(create("missing:fake"), asURI("SHA-819", "fake"),
                "Failed to produce a proper content digest URI!");
    }

    @Test
    public void testFromAlgorithm() {
        assertEquals(DigestAlgorithm.SHA1, DigestAlgorithm.fromAlgorithm("SHA"));
        assertEquals(DigestAlgorithm.SHA1, DigestAlgorithm.fromAlgorithm("sha-1"));
    }

    @Test
    public void testFromAlgorithmMissing() {
        assertEquals(DigestAlgorithm.MISSING, DigestAlgorithm.fromAlgorithm("what"));
    }

    @Test
    public void testAsUriBytes() {
        final byte[] bytes = "fake".getBytes();
        assertEquals(create("urn:sha1:66616b65"), asURI("SHA", bytes));
    }
}
