/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.commons.session;

import static org.fcrepo.http.commons.session.TransactionConstants.ATOMIC_ID_HEADER;
import static org.fcrepo.http.commons.session.TransactionConstants.TX_PREFIX;
import static org.slf4j.LoggerFactory.getLogger;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.TransactionManager;
import org.fcrepo.kernel.api.exception.TransactionRuntimeException;
import org.glassfish.hk2.api.Factory;
import org.glassfish.jersey.uri.internal.JerseyUriBuilder;
import org.slf4j.Logger;

/**
 * Provide a fedora tranasction within the current request context
 *
 * @author awoods
 */
public class TransactionProvider implements Factory<Transaction> {

    private static final Logger LOGGER = getLogger(TransactionProvider.class);

    private final TransactionManager txManager;

    private final HttpServletRequest request;

    private final Pattern txIdPattern;

    private final URI baseUri;

    private final String jmsBaseUrl;

    /**
     * Create a new transaction provider for a request
     * @param txManager the transaction manager
     * @param request the request
     * @param baseUri base uri for the application
     * @param jmsBaseUrl base url to use for jms, optional
     */
    public TransactionProvider(final TransactionManager txManager,
                               final HttpServletRequest request,
                               final URI baseUri,
                               final String jmsBaseUrl) {
        this.txManager = txManager;
        this.request = request;
        this.txIdPattern = Pattern.compile("(^|" + baseUri + TX_PREFIX + ")([0-9a-f\\-]+)$");
        this.baseUri = baseUri;
        this.jmsBaseUrl = jmsBaseUrl;
    }

    @Override
    public Transaction provide() {
        final Transaction transaction = getTransactionForRequest();
        if (!transaction.isShortLived()) {
            transaction.refresh();
        }
        LOGGER.trace("Providing new transaction {}", transaction.getId());
        return transaction;
    }

    @Override
    public void dispose(final Transaction transaction) {
        if (transaction.isShortLived()) {
            LOGGER.trace("Disposing transaction {}", transaction.getId());
            transaction.expire();
        }
    }

    /**
     * Returns the transaction for the Request. If the request has ATOMIC_ID_HEADER header,
     * the transaction corresponding to that ID is returned, otherwise, a new transaction is
     * created.
     *
     * @return the transaction for the request
     */
    public Transaction getTransactionForRequest() {
        String txId = null;
        // Transaction id either comes from header or is the path
        String txUri = request.getHeader(ATOMIC_ID_HEADER);
        if (!StringUtils.isEmpty(txUri)) {
            final Matcher txMatcher = txIdPattern.matcher(txUri);
            if (txMatcher.matches()) {
                txId = txMatcher.group(2);
            } else {
                throw new TransactionRuntimeException("Invalid transaction id");
            }
        } else {
            txUri = request.getPathInfo();
            if (txUri != null) {
                final Matcher txMatcher = txIdPattern.matcher(txUri);
                if (txMatcher.matches()) {
                    txId = txMatcher.group(2);
                }
            }
        }

        if (!StringUtils.isEmpty(txId)) {
            return txManager.get(txId);
        } else {
            final var transaction = txManager.create();
            transaction.setUserAgent(request.getHeader("user-agent"));
            transaction.setBaseUri(resolveBaseUri());
            return transaction;
        }
    }

    private String resolveBaseUri() {
        final String baseURL = getBaseUrlProperty();
        if (baseURL.length() > 0) {
            return baseURL;
        }
        return baseUri.toString();
    }

    /**
     * Produce a baseURL for JMS events using the system property fcrepo.jms.baseUrl of the form http[s]://host[:port],
     * if it exists.
     *
     * @return String the base Url
     */
    private String getBaseUrlProperty() {
        if (StringUtils.isNotBlank(jmsBaseUrl) && jmsBaseUrl.startsWith("http")) {
            final URI propBaseUri = URI.create(jmsBaseUrl);
            if (propBaseUri.getPort() < 0) {
                return JerseyUriBuilder.fromUri(baseUri).port(-1).uri(propBaseUri).toString();
            }
            return JerseyUriBuilder.fromUri(baseUri).uri(propBaseUri).toString();
        }
        return "";
    }

}
