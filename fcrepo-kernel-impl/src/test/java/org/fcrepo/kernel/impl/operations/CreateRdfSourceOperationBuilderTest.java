/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.impl.operations;

import static org.fcrepo.kernel.api.RdfCollectors.toModel;
import static org.fcrepo.kernel.api.RdfLexicon.CREATED_BY;
import static org.fcrepo.kernel.api.RdfLexicon.CREATED_DATE;
import static org.fcrepo.kernel.api.RdfLexicon.LAST_MODIFIED_BY;
import static org.fcrepo.kernel.api.RdfLexicon.LAST_MODIFIED_DATE;
import static org.fcrepo.kernel.api.RdfLexicon.RDF_SOURCE;
import static org.fcrepo.kernel.api.rdf.DefaultRdfStream.fromModel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;

import org.fcrepo.config.ServerManagedPropsMode;
import org.fcrepo.kernel.api.RdfStream;
import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.exception.MalformedRdfException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.operations.CreateRdfSourceOperation;
import org.fcrepo.kernel.api.operations.CreateRdfSourceOperationBuilder;
import org.fcrepo.kernel.api.operations.RdfSourceOperation;

import org.apache.jena.datatypes.xsd.XSDDateTime;
import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * @author bseeger
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CreateRdfSourceOperationBuilderTest {

    private CreateRdfSourceOperationBuilder builder;

    private Model model;

    private RdfStream stream;

    private static final FedoraId PARENT_ID = FedoraId.create("info:fedora/parent");

    private static final FedoraId RESOURCE_ID = FedoraId.create("info:fedora/test-subject");

    private static final Resource id = ResourceFactory.createResource(RESOURCE_ID.getResourceId());
    private static final Node id_node = id.asNode();

    private static final String PROPERTY_ID = "http://example.org/isLinkedTo/";

    private static final Property prop = ResourceFactory.createProperty(PROPERTY_ID);

    private static final String OBJECT_VALUE = "Somebody";

    private static final RDFNode object = ResourceFactory.createPlainLiteral(OBJECT_VALUE);

    private final Instant CREATED_INSTANT = Instant.parse("2019-11-12T10:00:30.0Z");
    private XSDDateTime Created_xsddatetime;

    private final Instant MODIFIED_INSTANT = Instant.parse("2019-11-12T14:11:05.0Z");
    private XSDDateTime Modified_xsddatetime;

    private final String USER_PRINCIPAL = "fedoraUser";

    private final Calendar calendar = Calendar.getInstance();

    @Mock
    private Transaction tx;

    @BeforeEach
    public void setUp() {
        calendar.setTime(Date.from(CREATED_INSTANT));
        Created_xsddatetime = new XSDDateTime(calendar);
        calendar.setTime(Date.from(MODIFIED_INSTANT));
        Modified_xsddatetime = new XSDDateTime(calendar);
        builder = new CreateRdfSourceOperationBuilderImpl(tx, RESOURCE_ID, RDF_SOURCE.toString(),
                ServerManagedPropsMode.STRICT);
        model = ModelFactory.createDefaultModel();
        model.add(
                ResourceFactory.createResource(RESOURCE_ID.getResourceId()),
                ResourceFactory.createProperty(PROPERTY_ID),
                ResourceFactory.createPlainLiteral(OBJECT_VALUE)
        );
        final OutputStream outputStream = new ByteArrayOutputStream();
        model.write(outputStream, "TURTLE");
        stream = fromModel(id_node, model);
    }

    @Test
    public void testStream() {
        final RdfSourceOperation op = builder.triples(stream).build();
        assertEquals(CreateRdfSourceOperationImpl.class, op.getClass());
        final var newModel = op.getTriples().collect(toModel());
        assertTrue(newModel.contains(id, prop, object));
        assertModelsMatch(model, newModel);
    }

    private void assertModelsMatch(final Model expected, final Model test) {
        final var stmtIter = expected.listStatements();
        while (stmtIter.hasNext()) {
            final var testStmt = stmtIter.nextStatement();
            assertTrue(test.contains(testStmt));
            test.remove(testStmt);
        }
        assertTrue(test.isEmpty());
    }

    @Test
    public void testRelaxedPropertiesAllFields() {
        final var resc = model.getResource(RESOURCE_ID.getResourceId());
        resc.addLiteral(LAST_MODIFIED_DATE, Modified_xsddatetime);
        resc.addLiteral(LAST_MODIFIED_BY, USER_PRINCIPAL);
        resc.addLiteral(CREATED_DATE, Created_xsddatetime);
        resc.addLiteral(CREATED_BY, USER_PRINCIPAL);

        final RdfSourceOperation op = buildOperationWithRelaxProperties(model);

        assertEquals(USER_PRINCIPAL, op.getCreatedBy());
        assertEquals(USER_PRINCIPAL, op.getLastModifiedBy());
        assertEquals(CREATED_INSTANT, op.getCreatedDate());
        assertEquals(MODIFIED_INSTANT, op.getLastModifiedDate());
    }

    @Test
    public void testRelaxedPropertiesNonDate() {
        final var resc = model.getResource(RESOURCE_ID.getResourceId());
        resc.addLiteral(LAST_MODIFIED_DATE, "Notadate");
        resc.addLiteral(LAST_MODIFIED_BY, USER_PRINCIPAL);
        resc.addLiteral(CREATED_DATE, Created_xsddatetime);
        resc.addLiteral(CREATED_BY, USER_PRINCIPAL);

        assertThrows(MalformedRdfException.class, () -> buildOperationWithRelaxProperties(model));
    }

    @Test
    public void testRelaxedPropertiesNonDate2() {
        final var resc = model.getResource(RESOURCE_ID.getResourceId());
        resc.addLiteral(LAST_MODIFIED_DATE, Modified_xsddatetime);
        resc.addLiteral(LAST_MODIFIED_BY, USER_PRINCIPAL);
        resc.addLiteral(CREATED_DATE, "Notadate");
        resc.addLiteral(CREATED_BY, USER_PRINCIPAL);

        assertThrows(MalformedRdfException.class, () -> buildOperationWithRelaxProperties(model));
    }

    @Test
    public void testRelaxedPropertiesNotRelaxed() {
        final var resc = model.getResource(RESOURCE_ID.getResourceId());
        resc.addLiteral(LAST_MODIFIED_DATE, Modified_xsddatetime);
        resc.addLiteral(LAST_MODIFIED_BY, USER_PRINCIPAL);
        resc.addLiteral(CREATED_DATE, Created_xsddatetime);
        resc.addLiteral(CREATED_BY, USER_PRINCIPAL);

        // Relaxed system property not set
        final RdfSourceOperation op = builder.relaxedProperties(model).build();

        assertNull(op.getCreatedBy());
        assertNull(op.getLastModifiedBy());
        assertNull(op.getCreatedDate());
        assertNull(op.getLastModifiedDate());
    }

    @Test
    public void testRelaxedPropertiesNoProperties() {
        final RdfSourceOperation op = buildOperationWithRelaxProperties(model);

        assertNull(op.getCreatedBy());
        assertNull(op.getLastModifiedBy());
        assertNull(op.getCreatedDate());
        assertNull(op.getLastModifiedDate());
    }

    @Test
    public void testArchivalGroupFalseByDefault() {
        final CreateRdfSourceOperation op = builder.build();
        assertFalse(op.isArchivalGroup());
    }

    @Test
    public void testArchivalGroup() {
        final CreateRdfSourceOperation op = builder.archivalGroup(true).build();
        assertTrue(op.isArchivalGroup());
    }


    private RdfSourceOperation buildOperationWithRelaxProperties(final Model model) {
        builder = new CreateRdfSourceOperationBuilderImpl(tx, RESOURCE_ID, RDF_SOURCE.toString(),
                ServerManagedPropsMode.RELAXED);
        return builder.relaxedProperties(model).build();
    }

    @Test
    public void testUserPrincipal() {
        final RdfSourceOperation op = builder.userPrincipal(USER_PRINCIPAL).build();

        assertEquals(USER_PRINCIPAL, op.getUserPrincipal());
    }

    @Test
    public void testParentId() {
        final CreateRdfSourceOperation op = builder.parentId(PARENT_ID).build();

        assertEquals(PARENT_ID, op.getParentId());
    }
}
