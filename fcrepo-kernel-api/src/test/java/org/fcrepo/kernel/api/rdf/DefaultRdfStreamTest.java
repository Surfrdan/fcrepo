/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.api.rdf;

import static java.util.Arrays.asList;
import static java.util.stream.Stream.of;

import static org.apache.jena.graph.NodeFactory.createLiteralByValue;
import static org.apache.jena.graph.NodeFactory.createLiteral;
import static org.apache.jena.graph.NodeFactory.createURI;
import static org.apache.jena.rdf.model.ModelFactory.createDefaultModel;
import static org.apache.jena.rdf.model.ResourceFactory.createPlainLiteral;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.apache.jena.rdf.model.ResourceFactory.createTypedLiteral;
import static org.fcrepo.kernel.api.RdfLexicon.CREATED_BY;
import static org.fcrepo.kernel.api.RdfLexicon.CREATED_DATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.fcrepo.kernel.api.RdfStream;
import org.fcrepo.kernel.api.utils.WrappingStream;
import org.fcrepo.kernel.api.utils.WrappingStreamTest;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.impl.StatementImpl;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

/**
 * Test Class for {@link DefaultRdfStream}
 *
 * @author acoburn
 * @author whikloj
 */
public class DefaultRdfStreamTest extends WrappingStreamTest {

    @Override
    protected WrappingStream<Triple> generateFloatStream() {
        return new DefaultRdfStream(createURI("subject"), Stream.of(
                Triple.create(subject, predicate, objectFloat1),
                Triple.create(subject, predicate, objectFloat2),
                Triple.create(subject, predicate, objectFloat3)
        ));
    }

    @Override
    protected WrappingStream<Triple> generateTextStream() {
        return new DefaultRdfStream(createURI("subject"), Stream.of(
                Triple.create(subject, predicate, objectA),
                Triple.create(subject, predicate, objectB),
                Triple.create(subject, predicate, objectC)
        ));
    }

    @Override
    protected WrappingStream<Triple> generateIntStream() {
        return new DefaultRdfStream(createURI("subject"), Stream.of(
                Triple.create(subject, predicate, objectInt1),
                Triple.create(subject, predicate, objectInt2),
                Triple.create(subject, predicate, objectInt3)
        ));
    }

    @Test
    public final void testMapCustom() {
        final Node subject = createURI("subject");
        try (final RdfStream stream = new DefaultRdfStream(subject, getTriples(subject).stream())) {

            final List<String> objs = stream.map(Triple::getObject).map(Node::getURI).toList();

            assertEquals(6, objs.size());
            assertEquals("obj1", objs.get(0));
            assertEquals("obj2", objs.get(1));
            assertEquals("obj3", objs.get(2));
        }
    }

    @Test
    public final void testFlatMapCustom() {
        final Node subject = createURI("subject");

        final List<String> objs = of(subject, subject, subject)
            .flatMap(x -> new DefaultRdfStream(x, getTriples(x).stream()))
            .map(Triple::getObject)
            .map(Node::getURI)
            .toList();

        assertEquals(18, objs.size());
        assertEquals("obj1", objs.get(0));
        assertEquals("obj1", objs.get(6));
    }

    private static List<Triple> getTriples(final Node subject) {
        final Node prop1 = createURI("prop1");
        final Node prop2 = createURI("prop2");
        return asList(
                Triple.create(subject, prop1, createURI("obj1")),
                Triple.create(subject, prop1, createURI("obj2")),
                Triple.create(subject, prop1, createURI("obj3")),
                Triple.create(subject, prop2, createURI("obj1")),
                Triple.create(subject, prop2, createURI("obj2")),
                Triple.create(subject, prop2, createURI("obj3")));
    }

    @Test
    public void testFromModel() {
        final Resource subject = createResource("subject");
        final Model model = createDefaultModel();
        model.add(new StatementImpl(subject, CREATED_BY, createPlainLiteral("test-user")));
        model.add(new StatementImpl(
                subject,
                CREATED_DATE,
                createTypedLiteral("2023-10-01T00:00:00Z", XSDDatatype.XSDdateTime)
        ));
        model.add(new StatementImpl(subject, RDF.type, createResource("http://example.org/Type")));
        try (final var stream = DefaultRdfStream.fromModel(subject.asNode(), model)) {
            assertEquals(subject.asNode(), stream.topic());
            final List<Triple> objects = stream.toList();
            assertEquals(3, objects.size());
            assertTrue(objects.contains(Triple.create(subject.asNode(), RDF.type.asNode(), createURI("http://example.org/Type"))));
            assertTrue(objects.contains(Triple.create(subject.asNode(), CREATED_BY.asNode(),
                    createLiteral("test-user"))));
            assertTrue(objects.contains(Triple.create(
                    subject.asNode(),
                    CREATED_DATE.asNode(),
                    createLiteralByValue("2023-10-01T00:00:00Z", XSDDatatype.XSDdateTime)
            )));
        }
    }

    @Test
    public void testSort() {
        try (final var stream = generateTextStream()) {
            assertEquals(3, stream.count());
        }
        try (final var stream = generateTextStream()) {
            assertEquals(3, stream.sorted().count());
        }
        try (final var stream = generateTextStream()) {
            assertEquals(3, stream.sorted(Comparator.comparing(a -> a.getObject().toString()))
                    .count());
        }
    }

    @Test
    public void testDistinct() {
        try (final var stream = generateTextStream()) {
            assertEquals(3, stream.count());
        }
        try (final var stream = generateTextStream()) {
            assertEquals(3, stream.distinct().count());
        }
    }

    @Test
    public void testSkip() {
        try (final var stream = generateTextStream()) {
            assertEquals(3, stream.count());
        }
        try (final var stream = generateTextStream()) {
            assertEquals(2, stream.skip(1).count());
        }
        try (final var stream = generateTextStream()) {
            assertEquals(0, stream.skip(3).count());
        }
    }

    @Test
    public void testLimit() {
        try (final var stream = generateTextStream()) {
            assertEquals(3, stream.count());
        }
        try (final var stream = generateTextStream()) {
            assertEquals(2, stream.limit(2).count());
        }
        try (final var stream = generateTextStream()) {
            assertEquals(0, stream.limit(0).count());
        }
    }

    @Test
    public void testPeek() {
        try (final var stream = generateTextStream()) {
            assertEquals(3, stream.count());
        }
        try (final var stream = generateTextStream()) {
            final List<String> peeks = new ArrayList<>();
            final List<Triple> peeked = stream.peek(
                    triple -> peeks.add(triple.getObject().getLiteralValue().toString()))
                    .toList();
            assertEquals(3, peeked.size());
            assertEquals(3, peeks.size());
            assertEquals("a", peeks.get(0));
            assertEquals("b", peeks.get(1));
            assertEquals("c", peeks.get(2));
        }
    }
}
