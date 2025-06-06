/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.commons.test.util;

import static java.net.URI.create;
import static jakarta.ws.rs.core.UriBuilder.fromUri;
import static org.apache.http.entity.ContentType.parse;
import static org.apache.jena.rdf.model.ModelFactory.createDefaultModel;
import static org.apache.jena.riot.RDFLanguages.contentTypeToLang;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import org.apache.http.HttpEntity;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * <p>Abstract TestHelpers class.</p>
 *
 * @author awoods
 */
public abstract class TestHelpers {

    public static ServletContext getServletContextImpl() {
        final ServletContext sc = mock(ServletContext.class);
        when(sc.getContextPath()).thenReturn("/fcrepo");
        return sc;
    }

    public static UriInfo getUriInfoImpl() {
        final UriInfo ui = mock(UriInfo.class);

        final Answer<UriBuilder> answer = new Answer<UriBuilder>() {

            @Override
            public UriBuilder answer(final InvocationOnMock invocation) {
                return fromUri("http://localhost/fcrepo");
            }
        };

        when(ui.getRequestUri()).thenReturn(
                URI.create("http://localhost/fcrepo"));
        when(ui.getBaseUri()).thenReturn(create("http://localhost/fcrepo"));
        when(ui.getBaseUriBuilder()).thenAnswer(answer);
        when(ui.getAbsolutePathBuilder()).thenAnswer(answer);

        return ui;
    }



    private static String getRdfSerialization(final HttpEntity entity) {
        final Lang lang = contentTypeToLang(parse(entity.getContentType().getValue()).getMimeType());
        assertNotNull(lang, "Entity is not an RDF serialization");
        return lang.getName();
    }

    public static CloseableDataset parseTriples(final HttpEntity entity) throws IOException {
        return parseTriples(entity.getContent(), getRdfSerialization(entity));
    }

    public static CloseableDataset parseTriples(final InputStream content) {
        return parseTriples(content, "N3");
    }

    public static CloseableDataset parseTriples(final InputStream content, final String contentType) {
        final Model model = createDefaultModel();
        model.read(content, "", contentType);
        return new CloseableDataset(model);
    }

    /**
     * Set a field via reflection
     *
     * @param parent the owner object of the field
     * @param name the name of the field
     * @param obj the value to set
     */
    public static void setField(final Object parent, final String name, final Object obj) {
        /* check the parent class too if the field could not be found */
        try {
            final Field f = findField(parent.getClass(), name);
            f.setAccessible(true);
            f.set(parent, obj);
        } catch (final IllegalArgumentException | IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(final Class<?> clazz, final String name)
        throws NoSuchFieldException {
        for (final Field f : clazz.getDeclaredFields()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        if (clazz.getSuperclass() == null) {
            throw new NoSuchFieldException("Field " + name
                    + " could not be found");
        }
        return findField(clazz.getSuperclass(), name);
    }
}
