/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.auth.webac;

import static org.fcrepo.auth.webac.URIConstants.WEBAC_MODE_READ;
import static org.fcrepo.auth.webac.URIConstants.WEBAC_MODE_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * @author acoburn
 * @since 9/2/15
 */
public class WebACAuthorizationTest {

    private final String AGENT1 = "me";
    private final String AGENT2 = "you";
    private final String AGENT_CLASS1 = "this";
    private final String AGENT_CLASS2 = "that";
    private final String ACCESS_TO1 = "/foo";
    private final String ACCESS_TO2 = "/bar";
    private final String ACCESS_TO3 = "/baz";
    private final String ACCESS_TO_CLASS1 = "ex:Image";
    private final String ACCESS_TO_CLASS2 = "ex:Archive";
    private final String ACCESS_GROUP1 = "/groupA";
    private final String ACCESS_GROUP2 = "/groupB";
    private final String DEFAULT_1 = "/foo";

    @Test
    public void testLists() {
        final List<String> agents = Arrays.asList(AGENT1, AGENT2, AGENT1);
        final List<String> agentClasses = Arrays.asList(AGENT_CLASS1, AGENT_CLASS2, AGENT_CLASS2);
        final List<URI> modes = Arrays.asList(WEBAC_MODE_READ, WEBAC_MODE_WRITE, WEBAC_MODE_READ);
        final List<String> accessTo = Arrays.asList(ACCESS_TO1, ACCESS_TO2, ACCESS_TO3);
        final List<String> accessToClass = Arrays.asList(ACCESS_TO_CLASS1, ACCESS_TO_CLASS2);
        final List<String> accessGroups = Arrays.asList(ACCESS_GROUP1, ACCESS_GROUP2);
        final List<String> defaults = Collections.singletonList(DEFAULT_1);

        final WebACAuthorizationImpl auth = new WebACAuthorizationImpl(agents, agentClasses,
                modes, accessTo, accessToClass, accessGroups, defaults);

        assertEquals(2, auth.getAgents().size());
        assertTrue(auth.getAgents().contains(AGENT1));
        assertEquals(2, auth.getAgentClasses().size());
        assertTrue(auth.getAgentClasses().contains(AGENT_CLASS2));
        assertEquals(2, auth.getModes().size());
        assertTrue(auth.getModes().contains(WEBAC_MODE_READ));
        assertEquals(3, auth.getAccessToURIs().size());
        assertTrue(auth.getAccessToURIs().contains(ACCESS_TO3));
        assertEquals(2, auth.getAccessToClassURIs().size());
        assertTrue(auth.getAccessToClassURIs().contains(ACCESS_TO_CLASS2));
        assertEquals(2, auth.getAgentGroups().size());
        assertTrue(auth.getAgentGroups().contains(ACCESS_GROUP1));
        assertTrue(auth.getAgentGroups().contains(ACCESS_GROUP2));
        assertEquals(1, auth.getDefaults().size());

    }

    @Test
    public void testSets() {
        final Set<String> agents = new HashSet<>(Arrays.asList(AGENT1, AGENT2));
        final Set<String> agentClasses = new HashSet<>(Arrays.asList(AGENT_CLASS1, AGENT_CLASS2));
        final Set<URI> modes = new HashSet<>(Arrays.asList(WEBAC_MODE_WRITE, WEBAC_MODE_READ));
        final Set<String> accessTo = new HashSet<>(Arrays.asList(ACCESS_TO1, ACCESS_TO2, ACCESS_TO3));
        final Set<String> accessToClass = new HashSet<>(Arrays.asList(ACCESS_TO_CLASS1, ACCESS_TO_CLASS2));
        final Set<String> accessGroups = new HashSet<>(Arrays.asList(ACCESS_GROUP1, ACCESS_GROUP2));
        final Set<String> defaults = new HashSet<>(Collections.singletonList(DEFAULT_1));

        final WebACAuthorizationImpl auth = new WebACAuthorizationImpl(agents, agentClasses,
                modes, accessTo, accessToClass, accessGroups, defaults);

        assertEquals(2, auth.getAgents().size());
        assertTrue(auth.getAgents().contains(AGENT1));
        assertEquals(2, auth.getAgentClasses().size());
        assertTrue(auth.getAgentClasses().contains(AGENT_CLASS2));
        assertEquals(2, auth.getModes().size());
        assertTrue(auth.getModes().contains(WEBAC_MODE_READ));
        assertEquals(3, auth.getAccessToURIs().size());
        assertTrue(auth.getAccessToURIs().contains(ACCESS_TO3));
        assertEquals(2, auth.getAccessToClassURIs().size());
        assertTrue(auth.getAccessToClassURIs().contains(ACCESS_TO_CLASS2));
        assertEquals(1, auth.getDefaults().size());
        assertEquals(2, auth.getAgentGroups().size());
        assertTrue(auth.getAgentGroups().contains(ACCESS_GROUP1));
        assertTrue(auth.getAgentGroups().contains(ACCESS_GROUP2));
    }


}
