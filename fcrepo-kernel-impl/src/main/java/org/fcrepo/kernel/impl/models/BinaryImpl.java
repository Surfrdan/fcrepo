/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.impl.models;

import org.apache.commons.io.input.BoundedInputStream;
import org.fcrepo.kernel.api.RdfStream;
import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.cache.UserTypesCache;
import org.fcrepo.kernel.api.exception.ItemNotFoundException;
import org.fcrepo.kernel.api.exception.PathNotFoundException;
import org.fcrepo.kernel.api.exception.PathNotFoundRuntimeException;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.Binary;
import org.fcrepo.kernel.api.models.ExternalContent;
import org.fcrepo.kernel.api.models.FedoraResource;
import org.fcrepo.kernel.api.models.ResourceFactory;
import org.fcrepo.persistence.api.PersistentStorageSessionManager;
import org.fcrepo.persistence.api.exceptions.PersistentItemNotFoundException;
import org.fcrepo.persistence.api.exceptions.PersistentStorageException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.List;

import static org.fcrepo.kernel.api.RdfLexicon.FEDORA_BINARY;
import static org.fcrepo.kernel.api.models.ExternalContent.PROXY;


/**
 * Implementation of a Non-RDF resource.
 *
 * @author bbpennel
 */
public class BinaryImpl extends FedoraResourceImpl implements Binary {

    private static final URI FEDORA_BINARY_URI = URI.create(FEDORA_BINARY.getURI());

    private String externalHandling;

    private String externalUrl;

    private Long contentSize;

    private String filename;

    private String mimeType;

    private Collection<URI> digests;

    /**
     * Construct the binary
     *
     * @param fedoraID fedora identifier
     * @param transaction transaction
     * @param pSessionManager session manager
     * @param resourceFactory resource factory
     * @param userTypesCache the user types cache
     */
    public BinaryImpl(final FedoraId fedoraID,
                      final Transaction transaction,
                      final PersistentStorageSessionManager pSessionManager,
                      final ResourceFactory resourceFactory,
                      final UserTypesCache userTypesCache) {
        super(fedoraID, transaction, pSessionManager, resourceFactory, userTypesCache);
    }

    @Override
    public InputStream getContent() {
        try {
            if (isProxy() || isRedirect()) {
                // non-external streams are already buffered
                return new BufferedInputStream(URI.create(getExternalURL()).toURL().openStream());
            } else {
                return getSession().getBinaryContent(getFedoraId().asResourceId(), getMementoDatetime());
            }
        } catch (final PersistentItemNotFoundException e) {
            throw new ItemNotFoundException("Unable to find content for " + getId()
                    + " version " + getMementoDatetime(), e);
        } catch (final PersistentStorageException | IOException e) {
            throw new RepositoryRuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public InputStream getRange(final long start, final long end) {
        try {
            if (isProxy() || isRedirect()) {
                // non-external streams are already buffered
                final long length = end + 1;
                final var stream = new BoundedInputStream(URI.create(getExternalURL()).toURL().openStream(), length);
                stream.skip(start);
                return stream;
            } else {
                return getSession().getBinaryRange(getFedoraId().asResourceId(), getMementoDatetime(), start, end);
            }
        } catch (final PersistentItemNotFoundException e) {
            throw new ItemNotFoundException("Unable to find content for " + getId()
                    + " version " + getMementoDatetime(), e);
        } catch (final PersistentStorageException | IOException e) {
            throw new RepositoryRuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public long getContentSize() {
        return contentSize;
    }

    @Override
    public Collection<URI> getContentDigests() {
        if (digests == null) {
            return null;
        }
        return digests;
    }

    @Override
    public Boolean isProxy() {
        return PROXY.equals(externalHandling);
    }

    @Override
    public Boolean isRedirect() {
        return ExternalContent.REDIRECT.equals(externalHandling);
    }

    @Override
    public String getExternalURL() {
        return externalUrl;
    }

    @Override
    public String getMimeType() {
        return mimeType;
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public FedoraResource getDescription() {
        try {
            final FedoraId descId = getFedoraId().asDescription();
            if (this.isMemento()) {
                final var descIdAsMemento = descId.asMemento(getMementoDatetime());
                return resourceFactory.getResource(transaction, descIdAsMemento);
            }
            return resourceFactory.getResource(transaction, descId);
        } catch (final PathNotFoundException e) {
            throw new PathNotFoundRuntimeException(e.getMessage(), e);
        }
    }

    /**
     * @param externalHandling the externalHandling to set
     */
    protected void setExternalHandling(final String externalHandling) {
        this.externalHandling = externalHandling;
    }

    /**
     * @param externalUrl the externalUrl to set
     */
    protected void setExternalUrl(final String externalUrl) {
        this.externalUrl = externalUrl;
    }

    /**
     * @param contentSize the contentSize to set
     */
    protected void setContentSize(final Long contentSize) {
        this.contentSize = contentSize;
    }

    /**
     * @param filename the filename to set
     */
    protected void setFilename(final String filename) {
        this.filename = filename;
    }

    /**
     * @param mimeType the mimeType to set
     */
    protected void setMimeType(final String mimeType) {
        this.mimeType = mimeType;
    }

    /**
     * @param digests the digests to set
     */
    protected void setDigests(final Collection<URI> digests) {
        this.digests = digests;
    }

    @Override
    public List<URI> getSystemTypes(final boolean forRdf) {
        var types = resolveSystemTypes(forRdf);

        if (types == null) {
            types = super.getSystemTypes(forRdf);
            // Add fedora:Binary type.
            types.add(FEDORA_BINARY_URI);
        }

        return types;
    }

    @Override
    public RdfStream getTriples() {
        return getDescription().getTriples();
    }
}
