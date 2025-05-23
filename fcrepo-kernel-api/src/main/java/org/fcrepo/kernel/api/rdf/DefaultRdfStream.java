/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.api.rdf;

import static java.util.Spliterator.IMMUTABLE;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.Stream.empty;
import static java.util.stream.StreamSupport.stream;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.fcrepo.kernel.api.utils.WrappingStream;
import org.fcrepo.kernel.api.RdfStream;

/**
 * Implementation of a context-bearing RDF stream
 *
 * @author acoburn
 * @since Dec 6, 2015
 */
public class DefaultRdfStream extends WrappingStream<Triple> implements RdfStream {

    private final Node node;

    /**
     * Create an RdfStream
     * @param node the topic of the stream
     */
    public DefaultRdfStream(final Node node) {
        this(node, empty());
    }

    /**
     * Create an RdfStream
     * @param node the topic of the stream
     * @param stream the incoming stream
     */
    public DefaultRdfStream(final Node node, final Stream<Triple> stream) {
        Objects.requireNonNull(node);
        this.node = node;
        this.stream = stream;
    }

    /**
     * Create an RdfStream from an existing Model.
     * @param node The subject node
     * @param model An input Model
     * @return a new RdfStream object
     */
    public static RdfStream fromModel(final Node node, final Model model) {
        return new DefaultRdfStream(node,
                stream(spliteratorUnknownSize(model.listStatements(), IMMUTABLE), false).map(Statement::asTriple));
    }

    @Override
    public Node topic() {
        return node;
    }

    @Override
    public RdfStream distinct() {
        return new DefaultRdfStream(topic(), stream.distinct());
    }

    @Override
    public RdfStream filter(final Predicate<? super Triple> predicate) {
        return new DefaultRdfStream(topic(), stream.filter(predicate));
    }

    @Override
    public RdfStream limit(final long maxSize) {
        return new DefaultRdfStream(topic(), stream.limit(maxSize));
    }

    @Override
    public RdfStream peek(final Consumer<? super Triple> action) {
        return new DefaultRdfStream(topic(), stream.peek(action));
    }

    @Override
    public RdfStream skip(final long n) {
        return new DefaultRdfStream(topic(), stream.skip(n));
    }

    @Override
    public RdfStream sorted() {
        return new DefaultRdfStream(topic(), stream.sorted());
    }

    @Override
    public RdfStream sorted(final Comparator<? super Triple> comparator) {
        return new DefaultRdfStream(topic(), stream.sorted(comparator));
    }

    @Override
    public RdfStream onClose(final Runnable closeHandler) {
        return new DefaultRdfStream(topic(), stream.onClose(closeHandler));
    }

    @Override
    public RdfStream parallel() {
        return new DefaultRdfStream(topic(), stream.parallel());
    }

    @Override
    public RdfStream sequential() {
        return new DefaultRdfStream(topic(), stream.sequential());
    }

    @Override
    public RdfStream unordered() {
        return new DefaultRdfStream(topic(), stream.unordered());
    }
}
