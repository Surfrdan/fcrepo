/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.impl.services;

import static org.apache.jena.vocabulary.DC_11.title;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import org.apache.commons.io.IOUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.fcrepo.config.FedoraPropsConfig;
import org.fcrepo.config.ServerManagedPropsMode;
import org.fcrepo.kernel.api.RdfCollectors;
import org.fcrepo.kernel.api.RdfLexicon;
import org.fcrepo.kernel.api.RdfStream;
import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.cache.UserTypesCache;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.ResourceHeaders;
import org.fcrepo.kernel.api.observer.EventAccumulator;
import org.fcrepo.kernel.api.operations.NonRdfSourceOperation;
import org.fcrepo.kernel.api.operations.NonRdfSourceOperationFactory;
import org.fcrepo.kernel.api.operations.RdfSourceOperation;
import org.fcrepo.kernel.api.operations.RdfSourceOperationFactory;
import org.fcrepo.kernel.api.services.MembershipService;
import org.fcrepo.kernel.api.services.ReferenceService;
import org.fcrepo.kernel.impl.operations.NonRdfSourceOperationFactoryImpl;
import org.fcrepo.kernel.impl.operations.RdfSourceOperationFactoryImpl;
import org.fcrepo.kernel.impl.operations.UpdateRdfSourceOperation;
import org.fcrepo.persistence.api.PersistentStorageSession;
import org.fcrepo.persistence.api.PersistentStorageSessionManager;
import org.fcrepo.search.api.SearchIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

/**
 * DeleteResourceServiceTest
 *
 * @author bseeger
 */
@ExtendWith(MockitoExtension.class)
public class ReplacePropertiesServiceImplTest {

    private static final String USER_PRINCIPAL = "fedoraUser";

    @Mock
    private Transaction tx;

    @Mock
    private PersistentStorageSession pSession;

    @Mock
    private PersistentStorageSessionManager psManager;

    @Mock
    private EventAccumulator eventAccumulator;

    @Mock
    private ReferenceService referenceService;

    @Mock
    private MembershipService membershipService;

    @Mock
    private SearchIndex searchIndex;

    @Mock
    private ResourceHeaders headers;

    @Mock
    private UserTypesCache userTypesCache;

    @InjectMocks
    private UpdateRdfSourceOperation operation;

    @InjectMocks
    private ReplacePropertiesServiceImpl service;

    private RdfSourceOperationFactory factory;

    private NonRdfSourceOperationFactory nonRdfFactory;

    @Captor
    private ArgumentCaptor<RdfSourceOperation> operationCaptor;

    @Captor
    private ArgumentCaptor<NonRdfSourceOperation> nonRdfOperationCaptor;


    private FedoraPropsConfig propsConfig;

    private static final FedoraId FEDORA_ID = FedoraId.create("info:fedora/resource1");
    private static final String TX_ID = "tx-1234";
    private static final String RDF =
            "<" + FEDORA_ID + "> <" + title + "> 'fancy title' .\n" +
            "<" + FEDORA_ID + "> <" + title + "> 'another fancy title' .";

    @BeforeEach
    public void setup() {
        propsConfig = new FedoraPropsConfig();
        factory = new RdfSourceOperationFactoryImpl();
        nonRdfFactory = new NonRdfSourceOperationFactoryImpl();
        setField(service, "factory", factory);
        setField(service, "nonRdfFactory", nonRdfFactory);
        setField(service, "eventAccumulator", eventAccumulator);
        setField(service, "referenceService", referenceService);
        setField(service, "membershipService", membershipService);
        setField(service, "searchIndex", searchIndex);
        setField(service, "fedoraPropsConfig", propsConfig);
        setField(service, "userTypesCache", userTypesCache);
        propsConfig.setServerManagedPropsMode(ServerManagedPropsMode.STRICT);
        when(psManager.getSession(any(Transaction.class))).thenReturn(pSession);
        when(pSession.getHeaders(any(FedoraId.class), nullable(Instant.class))).thenReturn(headers);
    }

    @Test
    public void testReplaceProperties() throws Exception {
        final Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, IOUtils.toInputStream(RDF, "UTF-8"), Lang.NTRIPLES);

        when(headers.getInteractionModel()).thenReturn(RdfLexicon.RDF_SOURCE.toString());

        service.perform(tx, USER_PRINCIPAL, FEDORA_ID, model);
        verify(tx).lockResource(FEDORA_ID);
        verify(pSession).persist(operationCaptor.capture());
        assertEquals(FEDORA_ID, operationCaptor.getValue().getResourceId());
        final RdfStream stream = operationCaptor.getValue().getTriples();
        final Model captureModel = stream.collect(RdfCollectors.toModel());

        assertTrue(captureModel.contains(ResourceFactory.createResource(FEDORA_ID.getResourceId()),
                ResourceFactory.createProperty(title.getURI()),
                "another fancy title"));
        assertTrue(captureModel.contains(ResourceFactory.createResource(FEDORA_ID.getResourceId()),
                ResourceFactory.createProperty(title.getURI()),
                "fancy title"));
    }

    @Test
    public void lockRelatedResourcesOnBinaryDescInAg() throws Exception {
        final Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, IOUtils.toInputStream(RDF, "UTF-8"), Lang.NTRIPLES);

        final var agId = FedoraId.create("ag");
        final var binaryId = agId.resolve("bin");
        final var descId = binaryId.asDescription();

        when(headers.getInteractionModel()).thenReturn(RdfLexicon.FEDORA_NON_RDF_SOURCE_DESCRIPTION_URI);
        when(headers.getArchivalGroupId()).thenReturn(agId);

        service.perform(tx, USER_PRINCIPAL, descId, model);
        verify(tx).lockResource(agId);
        verify(tx).lockResource(binaryId);
        verify(tx).lockResource(descId);
        verify(pSession).persist(operationCaptor.capture());
        verify(pSession).persist(nonRdfOperationCaptor.capture());

        final var updateOp = operationCaptor.getValue();
        assertEquals(descId, updateOp.getResourceId());
        final var nonRdfOp = nonRdfOperationCaptor.getValue();
        assertEquals(binaryId, nonRdfOp.getResourceId());
    }
}

