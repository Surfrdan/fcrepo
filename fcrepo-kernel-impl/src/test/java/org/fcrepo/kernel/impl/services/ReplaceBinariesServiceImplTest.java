/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.impl.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.apache.commons.io.IOUtils.toInputStream;
import static org.fcrepo.kernel.api.models.ExternalContent.COPY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.ExternalContent;
import org.fcrepo.kernel.api.models.ResourceHeaders;
import org.fcrepo.kernel.api.observer.EventAccumulator;
import org.fcrepo.kernel.api.operations.NonRdfSourceOperation;
import org.fcrepo.kernel.api.operations.NonRdfSourceOperationFactory;
import org.fcrepo.kernel.api.operations.ResourceOperation;
import org.fcrepo.kernel.impl.TestTransactionHelper;
import org.fcrepo.kernel.impl.operations.NonRdfSourceOperationFactoryImpl;
import org.fcrepo.kernel.impl.operations.UpdateNonRdfSourceOperation;
import org.fcrepo.persistence.api.PersistentStorageSession;
import org.fcrepo.persistence.api.PersistentStorageSessionManager;
import org.fcrepo.persistence.api.exceptions.PersistentStorageException;
import org.fcrepo.search.api.SearchIndex;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * @author bbpennel
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ReplaceBinariesServiceImplTest {

    @TempDir
    public Path tempFolder;

    private static final String USER_PRINCIPAL = "fedoraUser";

    private static final FedoraId FEDORA_ID = FedoraId.create("info:fedora/resource1");

    private static final String TX_ID = "tx-1234";

    private final String MIME_TYPE = "text/plain";

    private final String FILENAME = "someFile.txt";

    private final Long FILESIZE = 123L;

    private final Collection<URI> DIGESTS = asList(URI.create("urn:sha1:1234abcd"), URI.create("urn:md5:zyxw9876"));

    @Mock
    private EventAccumulator eventAccumulator;

    private Transaction tx;

    @Mock
    private PersistentStorageSession pSession;

    @Mock
    private SearchIndex searchIndex;

    @Mock
    private PersistentStorageSessionManager psManager;

    @Mock
    private ExternalContent externalContent;

    @Mock
    private ResourceHeaders headers;

    private NonRdfSourceOperationFactory factory;

    @InjectMocks
    private ReplaceBinariesServiceImpl service;

    @Captor
    private ArgumentCaptor<UpdateNonRdfSourceOperation> operationCaptor;

    @BeforeEach
    public void setup() {
        factory = new NonRdfSourceOperationFactoryImpl();
        setField(service, "factory", factory);
        setField(service, "eventAccumulator", eventAccumulator);
        when(psManager.getSession(any(Transaction.class))).thenReturn(pSession);
        tx = TestTransactionHelper.mockTransaction(TX_ID, false);
        setField(service, "searchIndex", searchIndex);
        when(tx.getId()).thenReturn(TX_ID);
        when(pSession.getHeaders(FEDORA_ID, null)).thenReturn(headers);
    }

    @Test
    public void replaceInternalBinary() throws Exception {
        final String contentString = "This is some test data";
        final var stream = toInputStream(contentString, UTF_8);

        service.perform(tx, USER_PRINCIPAL, FEDORA_ID, FILENAME, MIME_TYPE, DIGESTS, stream, FILESIZE,
                null);
        verify(pSession).persist(operationCaptor.capture());
        final NonRdfSourceOperation op = operationCaptor.getValue();

        assertEquals(FEDORA_ID, operationCaptor.getValue().getResourceId());
        assertEquals(contentString, IOUtils.toString(op.getContentStream(), UTF_8));
        assertPropertiesPopulated(op);

        verify(tx).lockResource(FEDORA_ID);
        verify(tx).lockResource(FEDORA_ID.asDescription());
    }

    @Test
    public void replaceInternalBinaryInAg() throws Exception {
        final String contentString = "This is some test data";
        final var stream = toInputStream(contentString, UTF_8);

        final var agId = FedoraId.create("ag");
        final var binId = agId.resolve("bin");

        when(pSession.getHeaders(binId, null)).thenReturn(headers);
        when(headers.getArchivalGroupId()).thenReturn(agId);

        service.perform(tx, USER_PRINCIPAL, binId, FILENAME, MIME_TYPE, DIGESTS, stream, FILESIZE,
                null);
        verify(pSession).persist(operationCaptor.capture());
        final NonRdfSourceOperation op = operationCaptor.getValue();

        assertEquals(binId, operationCaptor.getValue().getResourceId());
        assertEquals(contentString, IOUtils.toString(op.getContentStream(), UTF_8));
        assertPropertiesPopulated(op);

        verify(tx).lockResource(agId);
        verify(tx).lockResource(binId);
        verify(tx).lockResource(binId.asDescription());
    }

    @Test
    public void replaceExternalBinary() throws Exception {
        final var realDigests = asList(URI.create("urn:sha1:94e66df8cd09d410c62d9e0dc59d3a884e458e05"),
                URI.create("urn:md5:9893532233caff98cd083a116b013c0b"));

        final File externalFile = Files.createFile(
                tempFolder.resolve("externalFile")
        ).toFile();
        FileUtils.write(externalFile, "some content", StandardCharsets.UTF_8);
        final URI uri = externalFile.toURI();
        when(externalContent.fetchExternalContent()).thenReturn(Files.newInputStream(externalFile.toPath()));
        when(externalContent.getURI()).thenReturn(uri);
        when(externalContent.getHandling()).thenReturn(ExternalContent.PROXY);

        service.perform(tx, USER_PRINCIPAL, FEDORA_ID, FILENAME, MIME_TYPE, realDigests, null, FILESIZE,
                externalContent);
        verify(pSession).persist(operationCaptor.capture());
        final NonRdfSourceOperation op = operationCaptor.getValue();

        assertEquals(FEDORA_ID, operationCaptor.getValue().getResourceId());
        assertEquals(uri, op.getContentUri());
        assertEquals(ExternalContent.PROXY, op.getExternalHandling());
        assertPropertiesPopulated(op, MIME_TYPE, FILENAME, FILESIZE, realDigests);

        assertNull(op.getContentStream());
    }

    // Check that the content type from the external content link is given preference
    @Test
    public void replaceExternalBinary_WithExternalContentType() throws Exception {
        final var realDigests = asList(URI.create("urn:sha1:94e66df8cd09d410c62d9e0dc59d3a884e458e05"),
                URI.create("urn:md5:9893532233caff98cd083a116b013c0b"));

        final String contentString = "some content";
        final File externalFile = Files.createFile(
                tempFolder.resolve("externalFile")
        ).toFile();
        FileUtils.write(externalFile, contentString, StandardCharsets.UTF_8);
        final URI uri = externalFile.toURI();
        when(externalContent.fetchExternalContent()).thenReturn(Files.newInputStream(externalFile.toPath()));
        when(externalContent.getContentSize()).thenReturn((long) contentString.length());
        when(externalContent.getURI()).thenReturn(uri);
        when(externalContent.getHandling()).thenReturn(COPY);
        when(externalContent.getContentType()).thenReturn(MIME_TYPE);

        service.perform(tx, USER_PRINCIPAL, FEDORA_ID, FILENAME, "application/octet-stream",
                realDigests, null, -1L, externalContent);
        verify(pSession).persist(operationCaptor.capture());
        final NonRdfSourceOperation op = operationCaptor.getValue();

        assertEquals(FEDORA_ID, operationCaptor.getValue().getResourceId());
        assertEquals(uri, op.getContentUri());
        assertEquals(COPY, op.getExternalHandling());
        assertPropertiesPopulated(op, MIME_TYPE, FILENAME, (long) contentString.length(), realDigests);

        assertNull(op.getContentStream());
    }

    @Test
    public void replaceBinary_PersistFailure() throws Exception {
        doThrow(new PersistentStorageException("Boom")).when(pSession)
                .persist(any(ResourceOperation.class));

        final var stream = toInputStream("Some content", UTF_8);

        assertThrows(RepositoryRuntimeException.class,
                () -> service.perform(tx, USER_PRINCIPAL, FEDORA_ID, FILENAME, MIME_TYPE, DIGESTS, stream, FILESIZE,
                null)
        );
    }

    @Test
    public void copyExternalBinary() throws Exception {
        final var realDigests = asList(URI.create("urn:sha1:94e66df8cd09d410c62d9e0dc59d3a884e458e05"));

        final File externalFile = Files.createFile(
                tempFolder.resolve("externalFile")
        ).toFile();
        final String contentString = "some content";
        FileUtils.write(externalFile, contentString, StandardCharsets.UTF_8);
        final URI uri = externalFile.toURI();
        when(externalContent.fetchExternalContent()).thenReturn(Files.newInputStream(externalFile.toPath()));
        when(externalContent.getURI()).thenReturn(uri);
        when(externalContent.isCopy()).thenReturn(true);
        when(externalContent.getHandling()).thenReturn(ExternalContent.COPY);
        when(externalContent.getContentType()).thenReturn("text/xml");

        service.perform(tx, USER_PRINCIPAL, FEDORA_ID, FILENAME, MIME_TYPE, realDigests, null,
                (long) contentString.length(), externalContent);
        verify(pSession).persist(operationCaptor.capture());
        final NonRdfSourceOperation op = operationCaptor.getValue();

        assertEquals(FEDORA_ID, operationCaptor.getValue().getResourceId());
        assertNull(op.getContentUri());
        assertNull(op.getExternalHandling());
        assertPropertiesPopulated(op, "text/xml", FILENAME, (long) contentString.length(), realDigests);

        //assertEquals(contentString, IOUtils.toString(op.getContentStream(), UTF_8));
    }

    private void assertPropertiesPopulated(final NonRdfSourceOperation op, final String exMimetype,
            final String exFilename, final long exContentSize, final Collection<URI> exDigests) {
        assertEquals(exMimetype, op.getMimeType());
        assertEquals(exFilename, op.getFilename());
        assertEquals(exContentSize, op.getContentSize());
        assertEquals(exDigests, op.getContentDigests());
    }

    private void assertPropertiesPopulated(final NonRdfSourceOperation op) {
        assertPropertiesPopulated(op, MIME_TYPE, FILENAME, FILESIZE, DIGESTS);
    }
}
