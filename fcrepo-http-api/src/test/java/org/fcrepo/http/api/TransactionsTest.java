/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.api;

import static java.time.Instant.now;
import static org.fcrepo.http.commons.session.TransactionConstants.ATOMIC_EXPIRES_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import org.fcrepo.config.FedoraPropsConfig;
import org.fcrepo.http.commons.api.rdf.HttpIdentifierConverter;
import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.TransactionManager;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.exception.TransactionClosedException;
import org.fcrepo.kernel.api.exception.TransactionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * <p>TransactionsTest class.</p>
 *
 * @author awoods
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TransactionsTest {

    private static final String VALID_TX_ID = "123";
    private static final String VALID_TX_URI = "http://example.com/fcr:tx/123";

    private Transactions testObj;

    @Mock
    private Transaction mockTransaction;

    @Mock
    private TransactionManager mockTxManager;

    @Mock
    private UriInfo mockUriInfo;

    @Mock
    private HttpIdentifierConverter mockIdConverter;

    @Mock
    private HttpServletRequest mockRequest;

    @Mock
    private Principal mockPrincipal;

    @Mock
    private SecurityContext mockSecurityContext;

    private FedoraPropsConfig propsConfig;

    @BeforeEach
    public void setUp() {
        propsConfig = new FedoraPropsConfig();
        testObj = new Transactions();
        mockTransaction.setShortLived(false);
        when(mockTxManager.create()).thenReturn(mockTransaction);
        when(mockTxManager.get(VALID_TX_ID)).thenReturn(mockTransaction);
        when(mockTxManager.get(AdditionalMatchers.not(ArgumentMatchers.eq(VALID_TX_ID))))
            .thenThrow(new TransactionNotFoundException("No Transaction found with transactionId"));
        when(mockTransaction.getId()).thenReturn("123");
        when(mockTransaction.getExpires()).thenReturn(now().plusSeconds(100));

        when(mockUriInfo.getBaseUri()).thenReturn(URI.create("http://localhost/rest"));

        setField(testObj, "txManager", mockTxManager);
        setField(testObj, "uriInfo", mockUriInfo);
        setField(testObj, "fedoraPropsConfig", propsConfig);
    }

    @Test
    public void shouldStartANewTransaction() throws URISyntaxException {
        setField(testObj, "identifierConverter", mockIdConverter);
        setField(testObj, "servletRequest", mockRequest);

        when(mockIdConverter.toExternalId(anyString())).thenReturn(VALID_TX_URI);

        final Response response = testObj.createTransaction();
        assertEquals(201, response.getStatus());
        assertEquals(VALID_TX_URI, response.getHeaderString("Location"));
    }

    @Test
    public void shouldCommitATransaction() {
        final Response response = testObj.commit(VALID_TX_ID);
        assertEquals(204, response.getStatus());
        verify(mockTransaction).commit();
    }

    @Test
    public void shouldErrorIfCommitClosedTransaction() {
        when(mockTxManager.get(VALID_TX_ID))
                .thenThrow(new TransactionClosedException("Transaction closed"));
        final Response rollback = testObj.commit(VALID_TX_ID);
        assertEquals(410, rollback.getStatus());
    }

    @Test
    public void shouldErrorIfCommitNonExistingTransactionId() {
        final Response response = testObj.commit("tx:404");
        assertEquals(404, response.getStatus());
    }

    @Test
    public void shouldErrorIfCommitFails() {
        doThrow(new RepositoryRuntimeException("Oops")).when(mockTransaction).commit();
        final Response response = testObj.commit(VALID_TX_ID);
        assertEquals(409, response.getStatus());
    }

    @Test
    public void shouldRollBackATransaction() {
        final Response response = testObj.rollback(VALID_TX_ID);
        assertEquals(204, response.getStatus());
        verify(mockTransaction).rollback();
    }

    @Test
    public void shouldErrorIfRollbackNonExistingTransactionId() {
        final Response rollback = testObj.rollback("tx:404");
        assertEquals(404, rollback.getStatus());
    }

    @Test
    public void shouldErrorIfRollbackClosedTransaction() {
        when(mockTxManager.get(VALID_TX_ID))
                .thenThrow(new TransactionClosedException("Transaction closed"));
        final Response rollback = testObj.rollback(VALID_TX_ID);
        assertEquals(410, rollback.getStatus());
    }

    @Test
    public void shouldErrorIfRollbackFails() {
        when(mockTxManager.get(VALID_TX_ID))
                .thenThrow(new RepositoryRuntimeException("Rollback failed"));
        final Response rollback = testObj.rollback(VALID_TX_ID);
        assertEquals(409, rollback.getStatus());
    }

    @Test
    public void shouldRefreshATransaction() {
        final Response response = testObj.refreshTransaction(VALID_TX_ID);
        assertEquals(204, response.getStatus());
        assertNotNull(response.getHeaderString(ATOMIC_EXPIRES_HEADER));
        verify(mockTransaction).refresh();
    }

    @Test
    public void shouldErrorIfRefreshNonExistentTx() {
        final Response response = testObj.refreshTransaction("tx:404");
        assertEquals(404, response.getStatus());
        verify(mockTransaction, never()).refresh();
    }

    @Test
    public void shouldErrorIfRefreshExpiredTx() {
        when(mockTxManager.get(VALID_TX_ID))
                .thenThrow(new TransactionClosedException("Transaction expired"));
        final Response response = testObj.refreshTransaction(VALID_TX_ID);
        assertEquals(410, response.getStatus());
        verify(mockTransaction, never()).refresh();
    }

    @Test
    public void shouldGetTransactionStatus() {
        final Response response = testObj.getTransactionStatus(VALID_TX_ID);
        assertEquals(204, response.getStatus());
        assertNotNull(response.getHeaderString(ATOMIC_EXPIRES_HEADER));
        verify(mockTransaction, never()).refresh();
    }

    @Test
    public void shouldErrorIfGetStatusNonExistentTx() {
        final Response response = testObj.getTransactionStatus("tx:404");
        assertEquals(404, response.getStatus());
    }

    @Test
    public void shouldErrorIfGetStatusExpired() {
        when(mockTxManager.get(VALID_TX_ID))
                .thenThrow(new TransactionClosedException("Transaction expired"));
        final Response response = testObj.getTransactionStatus(VALID_TX_ID);
        assertEquals(410, response.getStatus());
    }
}
