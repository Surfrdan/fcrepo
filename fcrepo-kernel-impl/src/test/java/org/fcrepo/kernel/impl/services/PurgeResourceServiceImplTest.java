/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.impl.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.fcrepo.kernel.api.ContainmentIndex;
import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.auth.ACLHandle;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.Binary;
import org.fcrepo.kernel.api.models.Container;
import org.fcrepo.kernel.api.models.NonRdfSourceDescription;
import org.fcrepo.kernel.api.models.ResourceFactory;
import org.fcrepo.kernel.api.models.ResourceHeaders;
import org.fcrepo.kernel.api.models.WebacAcl;
import org.fcrepo.kernel.api.observer.EventAccumulator;
import org.fcrepo.kernel.impl.TestTransactionHelper;
import org.fcrepo.kernel.impl.operations.DeleteResourceOperationFactoryImpl;
import org.fcrepo.kernel.impl.operations.PurgeResourceOperation;
import org.fcrepo.persistence.api.PersistentStorageSession;
import org.fcrepo.persistence.api.PersistentStorageSessionManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.github.benmanes.caffeine.cache.Cache;

/**
 * PurgeResourceServiceTest
 *
 * Copy of DeleteResourceServiceTest
 *
 * @author dbernstein
 * @author whikloj
 */
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@ContextConfiguration("/containmentIndexTest.xml")
public class PurgeResourceServiceImplTest {

    private static final String USER = "fedoraAdmin";

    private Transaction tx;

    @Mock
    private EventAccumulator eventAccumulator;

    @Mock
    private PersistentStorageSession pSession;

    @Inject
    private ContainmentIndex containmentIndex;

    @Mock
    private PersistentStorageSessionManager psManager;

    @Mock
    private ResourceFactory resourceFactory;

    @Mock
    private Container container;

    @Mock
    private Container childContainer;

    @Mock
    private Binary binary;

    @Mock
    private WebacAcl acl;

    @Mock
    private NonRdfSourceDescription binaryDesc;

    @Mock
    private ResourceHeaders resourceHeaders;
    @Mock
    private ResourceHeaders childHeaders;
    @Mock
    private ResourceHeaders descHeaders;
    @Mock
    private ResourceHeaders aclHeaders;
    @Mock
    private Cache<String, Optional<ACLHandle>> authHandleCache;

    @Captor
    private ArgumentCaptor<PurgeResourceOperation> operationCaptor;

    @InjectMocks
    private PurgeResourceServiceImpl service;

    private static final FedoraId RESOURCE_ID =  FedoraId.create("test-resource");
    private static final FedoraId CHILD_RESOURCE_ID = RESOURCE_ID.resolve("test-resource-child");
    private static final FedoraId RESOURCE_DESCRIPTION_ID = RESOURCE_ID.resolve("fcr:metadata");
    private static final FedoraId RESOURCE_ACL_ID = RESOURCE_ID.resolve("fcr:acl");
    private static final String TX_ID = "tx-1234";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        tx = TestTransactionHelper.mockTransaction(TX_ID, false);
        when(psManager.getSession(any(Transaction.class))).thenReturn(pSession);
        final DeleteResourceOperationFactoryImpl factoryImpl = new DeleteResourceOperationFactoryImpl();
        setField(service, "deleteResourceFactory", factoryImpl);
        setField(service, "containmentIndex", containmentIndex);
        setField(service, "eventAccumulator", eventAccumulator);
        when(container.getFedoraId()).thenReturn(RESOURCE_ID);

        when(pSession.getHeaders(RESOURCE_ID, null)).thenReturn(resourceHeaders);
        when(pSession.getHeaders(CHILD_RESOURCE_ID, null)).thenReturn(childHeaders);
        when(pSession.getHeaders(RESOURCE_DESCRIPTION_ID, null)).thenReturn(descHeaders);
        when(pSession.getHeaders(RESOURCE_ACL_ID, null)).thenReturn(aclHeaders);
    }

    @Test
    public void testContainerPurge() throws Exception {
        when(container.isAcl()).thenReturn(false);
        when(container.getAcl()).thenReturn(null);

        service.perform(tx, container, USER);
        verifyResourceOperation(RESOURCE_ID, operationCaptor, pSession);
    }

    @Test
    public void testRecursivePurge() throws Exception {
        when(container.isAcl()).thenReturn(false);
        when(container.getAcl()).thenReturn(null);
        when(childContainer.getFedoraId()).thenReturn(CHILD_RESOURCE_ID);
        when(childContainer.isAcl()).thenReturn(false);
        when(childContainer.getAcl()).thenReturn(null);

        when(resourceFactory.getResource(tx, CHILD_RESOURCE_ID)).thenReturn(childContainer);
        containmentIndex.addContainedBy(tx, container.getFedoraId(), childContainer.getFedoraId());
        containmentIndex.commitTransaction(tx);
        containmentIndex.removeContainedBy(tx, container.getFedoraId(), childContainer.getFedoraId());

        service.perform(tx, container, USER);

        verify(pSession, times(2)).persist(operationCaptor.capture());
        final List<PurgeResourceOperation> operations = operationCaptor.getAllValues();
        assertEquals(2, operations.size());

        assertEquals(CHILD_RESOURCE_ID, operations.get(0).getResourceId());
        assertEquals(RESOURCE_ID, operations.get(1).getResourceId());

        assertEquals(0, containmentIndex.getContains(tx, RESOURCE_ID).count());

        verify(tx).lockResource(RESOURCE_ID);
        verify(tx).lockResource(CHILD_RESOURCE_ID);
    }

    private void verifyResourceOperation(final FedoraId fedoraID,
                                         final ArgumentCaptor<PurgeResourceOperation> captor,
                                         final PersistentStorageSession pSession) throws Exception {
        verify(pSession).persist(captor.capture());
        final PurgeResourceOperation containerOperation = captor.getValue();
        assertEquals(fedoraID, containerOperation.getResourceId());
    }

    @Test
    public void testAclPurge() throws Exception {
        when(acl.getFedoraId()).thenReturn(RESOURCE_ACL_ID);
        when(acl.isAcl()).thenReturn(true);
        service.perform(tx, acl, USER);
        verifyResourceOperation(RESOURCE_ACL_ID, operationCaptor, pSession);
    }

    @Test
    public void testBinaryDescriptionPurge() throws Exception {
        when(binaryDesc.getFedoraId()).thenReturn(RESOURCE_DESCRIPTION_ID);
        assertThrows(RepositoryRuntimeException.class, () -> service.perform(tx, binaryDesc, USER));
    }

    @Test
    public void testBinaryPurgeWithAcl() throws Exception {
        when(binary.getFedoraId()).thenReturn(RESOURCE_ID);
        when(binary.isAcl()).thenReturn(false);
        when(binary.getDescription()).thenReturn(binaryDesc);
        when(binaryDesc.getFedoraId()).thenReturn(RESOURCE_DESCRIPTION_ID);
        when(binary.getAcl()).thenReturn(acl);
        when(acl.getFedoraId()).thenReturn(RESOURCE_ACL_ID);

        service.perform(tx, binary, USER);

        verify(pSession, times(3)).persist(operationCaptor.capture());
        final List<PurgeResourceOperation> operations = operationCaptor.getAllValues();
        assertEquals(3, operations.size());

        assertEquals(RESOURCE_DESCRIPTION_ID, operations.get(0).getResourceId());
        assertEquals(RESOURCE_ACL_ID, operations.get(1).getResourceId());
        assertEquals(RESOURCE_ID, operations.get(2).getResourceId());

        verify(tx).lockResource(RESOURCE_ID);
        verify(tx).lockResource(RESOURCE_DESCRIPTION_ID);
        verify(tx).lockResource(RESOURCE_ACL_ID);
    }

}
