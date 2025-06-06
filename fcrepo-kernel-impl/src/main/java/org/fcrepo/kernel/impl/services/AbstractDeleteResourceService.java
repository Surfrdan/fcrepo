/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.impl.services;

import static java.lang.String.format;

import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.auth.ACLHandle;
import org.fcrepo.kernel.api.exception.PathNotFoundException;
import org.fcrepo.kernel.api.exception.PathNotFoundRuntimeException;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.Binary;
import org.fcrepo.kernel.api.models.Container;
import org.fcrepo.kernel.api.models.FedoraResource;
import org.fcrepo.kernel.api.models.NonRdfSourceDescription;
import org.fcrepo.kernel.api.models.ResourceFactory;
import org.fcrepo.kernel.api.models.Tombstone;
import org.fcrepo.persistence.api.PersistentStorageSession;
import org.fcrepo.persistence.api.PersistentStorageSessionManager;
import org.fcrepo.persistence.api.exceptions.PersistentStorageException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;

/**
 * Shared delete/purge code.
 * @author whikloj
 */
abstract public class AbstractDeleteResourceService extends AbstractService {

    private final static Logger log = LoggerFactory.getLogger(AbstractDeleteResourceService.class);

    @Inject
    protected ResourceFactory resourceFactory;

    @Inject
    protected PersistentStorageSessionManager psManager;

    @Inject
    private Cache<String, Optional<ACLHandle>> authHandleCache;

    /**
     * The starts the service, does initial checks and setups for processing.
     * @param tx the transaction.
     * @param fedoraResource the resource to start delete/purging.
     * @param userPrincipal the user performing the action.
     */
    public void perform(final Transaction tx, final FedoraResource fedoraResource, final String userPrincipal) {
        final String fedoraResourceId = fedoraResource.getId();

        if (fedoraResource instanceof NonRdfSourceDescription) {
            throw new RepositoryRuntimeException(
            format("A NonRdfSourceDescription cannot be deleted independently of the NonRDFSource:  %s",
            fedoraResourceId));
        }

        try {
            log.debug("operating on {}", fedoraResourceId);
            final PersistentStorageSession pSession = this.psManager.getSession(tx);
            deleteDepthFirst(tx, pSession, fedoraResource, userPrincipal);
        } catch (final PersistentStorageException ex) {
            throw new RepositoryRuntimeException(format("failed to delete/purge resource %s", fedoraResourceId), ex);
        }
    }

    /**
     * Code to perform the recursion of containers.
     * @param tx the transaction
     * @param pSession the persistent storage session
     * @param fedoraResource the current resource to check for any children.
     * @param userPrincipal the user performing the action.
     * @throws PersistentStorageException any problems accessing the underlying storage.
     */
    private void deleteDepthFirst(final Transaction tx, final PersistentStorageSession pSession,
                                  final FedoraResource fedoraResource, final String userPrincipal)
            throws PersistentStorageException {

        final FedoraId fedoraId = fedoraResource.getFedoraId();

        if (fedoraResource instanceof Container) {
            final Stream<String> children = getContained(tx, fedoraResource);
            children.forEach(childResourceId -> {
                try {

                    final FedoraResource res = resourceFactory.getResource(tx, FedoraId.create(childResourceId));
                    if (res instanceof Tombstone) {
                        deleteDepthFirst(tx, pSession, ((Tombstone) res).getDeletedObject(), userPrincipal);
                    } else {
                        deleteDepthFirst(tx, pSession, res, userPrincipal);
                    }
                } catch (final PathNotFoundException ex) {
                    log.error("Path not found for {}: {}", fedoraId.getFullId(), ex.getMessage());
                    throw new PathNotFoundRuntimeException(ex.getMessage(), ex);
                } catch (final PersistentStorageException ex) {
                    throw new RepositoryRuntimeException(format("failed to delete resource %s", fedoraId.getFullId()),
                            ex);
                }
            });
        } else if (fedoraResource instanceof Binary) {
            doAction(tx, pSession, fedoraResource.getDescription().getFedoraId(), userPrincipal);
        }

        //delete/purge the acl if this is not the acl
        if (!fedoraResource.isAcl()) {
            final FedoraResource acl = fedoraResource.getAcl();
            if (acl != null) {
                doAction(tx, pSession, acl.getFedoraId(), userPrincipal);
                // Flush ACL cache on any ACL creation/update/deletion.
                authHandleCache.invalidateAll();
            }
        } else {
            // Flush ACL cache on any ACL creation/update/deletion.
            authHandleCache.invalidateAll();
        }

        //delete/purge the resource itself
        doAction(tx, pSession, fedoraId, userPrincipal);
    }

    /**
     * Get the contained resources to act upon.
     * @param tx the transaction this occurs in.
     * @param resource the parent resource to find contained resources for.
     * @return stream of child ids.
     */
    abstract protected Stream<String> getContained(final Transaction tx, final FedoraResource resource);

    /**
     * Perform the actual delete or purge action
     * @param tx the transaction this occurs in.
     * @param pSession the persistent storage session.
     * @param resourceId the resource to perform the action on.
     * @param userPrincipal the user performing the action
     * @throws PersistentStorageException if problem performing the action.
     */
    abstract protected void doAction(final Transaction tx, final PersistentStorageSession pSession,
                                     final FedoraId resourceId, final String userPrincipal)
            throws PersistentStorageException;
}
