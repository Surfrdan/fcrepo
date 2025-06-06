/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.api;

import static java.util.Arrays.asList;
import static org.apache.jena.graph.NodeFactory.createURI;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;

import org.junit.jupiter.api.Test;

/**
 * @author acoburn
 */
public class RdfCollectorsTest {

    @Test
    public void streamToModel() {
        final Node subject = createURI("subject");
        final Node prop1 = createURI("prop1");
        final Node prop2 = createURI("prop2");
        final List<Triple> triples = asList(
                Triple.create(subject, prop1, createURI("obj1")),
                Triple.create(subject, prop1, createURI("obj2")),
                Triple.create(subject, prop1, createURI("obj3")),
                Triple.create(subject, prop2, createURI("obj1")),
                Triple.create(subject, prop2, createURI("obj2")),
                Triple.create(subject, prop2, createURI("obj3")));

        final Model filtered = triples.stream().filter(x -> x.getPredicate().equals(prop1))
                .collect(RdfCollectors.toModel());

        assertEquals(3, filtered.size());
    }
}

