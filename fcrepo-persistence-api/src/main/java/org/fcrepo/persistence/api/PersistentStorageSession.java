/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.persistence.api;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.fcrepo.kernel.api.RdfStream;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.ResourceHeaders;
import org.fcrepo.kernel.api.operations.ResourceOperation;
import org.fcrepo.persistence.api.exceptions.PersistentStorageException;

/**
 * An interface that mediates CRUD operations to and from persistence storage.
 *
 * @author dbernstein
 * @author whikloj
 */
public interface PersistentStorageSession {

    /**
     * Return the ID for this session, or null for a read-only session.
     *
     * @return the session id.
     */
    String getId();

    /**
     * Perform a persistence operation on a resource
     *
     * @param operation The persistence operation to perform
     * @throws PersistentStorageException Error persisting the resource.
     */
    void persist(final ResourceOperation operation)
            throws PersistentStorageException;

    /**
     * Get the header information for the identified resource.
     *
     * @param identifier identifier of the resource
     * @param version instant identifying the version of the resource to read from.
     *      If null, then the head version is used.
     * @return header information
     * @throws PersistentStorageException  Either a PersistentItemNotFoundException or PersistentSessionClosedException
     */
    ResourceHeaders getHeaders(final FedoraId identifier, final Instant version)
            throws PersistentStorageException;

    /**
     * Get the client managed triples for the provided resource.
     *
     * @param identifier identifier for the resource.
     * @param version instant identifying the version of the resource to read from. If null, then the head version is
     *        used.
     * @return the triples as an RdfStream.
     * @throws PersistentStorageException  Either a PersistentItemNotFoundException or PersistentSessionClosedException
     */
    RdfStream getTriples(final FedoraId identifier, final Instant version)
            throws PersistentStorageException;

    /**
     * Get the persisted binary content for the provided resource.
     *
     * @param identifier identifier for the resource.
     * @param version instant identifying the version of the resource to read from. If null, then the head version is
     *        used.
     * @return the binary content.
     * @throws PersistentStorageException  Either a PersistentItemNotFoundException or PersistentSessionClosedException
     */
    InputStream getBinaryContent(final FedoraId identifier, final Instant version)
            throws PersistentStorageException;

    /**
     * Get a range of bytes from the binary content for the provided resource.
     * @param identifier identifier for the resource.
     * @param version instant identifying the version of the resource to read from. If null, then the head version is
     *       used.
     * @param start the start byte position, inclusive
     * @param end the end byte position, inclusive
     * @return the requested range of bytes
     * @throws PersistentStorageException Either a PersistentItemNotFoundException or PersistentSessionClosedException
     */
    InputStream getBinaryRange(final FedoraId identifier, final Instant version, final long start, final long end)
            throws PersistentStorageException;

    /**
     * Returns a list of immutable versions associated with the specified fedora identifier in ascending order
     * by creation time of the version.
     *
     * @param identifier identifier for the resource.
     * @return The list of instants that map to the underlying versions, ordered by time created
     * @throws PersistentStorageException  Either a PersistentItemNotFoundException or PersistentSessionClosedException
     */
    List<Instant> listVersions(final FedoraId identifier)
            throws PersistentStorageException;

    /**
     * Does anything that's necessary to prepare the session to be committed, for example committing database
     * changes. This method MUST be called before commit(). If prepare() fails, then the session should be rolled back.
     * @throws PersistentStorageException if an error is encountered
     */
    void prepare() throws PersistentStorageException;

    /**
     * Commits any changes in the current session to persistent storage.
     * @throws PersistentStorageException Error during commit.
     */
    void commit() throws PersistentStorageException;

    /**
     * Rolls back any changes in the current session.
     *
     * @throws PersistentStorageException Error completing rollback.
     */
    void rollback() throws PersistentStorageException;

}
