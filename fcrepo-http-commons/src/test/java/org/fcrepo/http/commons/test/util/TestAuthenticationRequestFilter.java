/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.http.commons.test.util;

import static java.lang.reflect.Proxy.newProxyInstance;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Base64;
import java.util.Set;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;

import org.glassfish.grizzly.http.server.GrizzlyPrincipal;
import org.slf4j.Logger;

/**
 * @author Gregory Jansen
 */
public class TestAuthenticationRequestFilter implements Filter {

    private static final Logger log = getLogger(TestAuthenticationRequestFilter.class);

    private static final String FEDORA_ADMIN_USER = "fedoraAdmin";

    /*
     * (non-Javadoc)
     * @see
     * com.sun.jersey.spi.container.ContainerRequestFilter#filter(com.sun.jersey
     * .spi.container.ContainerRequest)
     */
    @Override
    public void doFilter(final ServletRequest request,
            final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        final HttpServletRequest req = (HttpServletRequest) request;
        final String username = getUsername(req);
        // Validate the extracted credentials
        Set<String> containerRoles = emptySet();
        if (username == null) {
            log.debug("ANONYMOUS");
        } else if (FEDORA_ADMIN_USER.equals(username)) {
            containerRoles = singleton("fedoraAdmin");
            log.debug("ADMIN AUTHENTICATED");
        } else if ("noroles".equals(username)) {
            log.debug("USER (without roles); AUTHENTICATED");
        } else {
            containerRoles = singleton("fedoraUser");
            log.debug("USER AUTHENTICATED");
        }
        final ServletRequest proxy = proxy(req, username, containerRoles);
        chain.doFilter(proxy, response);
    }

    private static ServletRequest proxy(final HttpServletRequest request,
            final String username, final Set<String> containerRoles) {
        final Principal user = username != null ? new GrizzlyPrincipal(username) : null;
        final HttpServletRequest result =
                (HttpServletRequest) newProxyInstance(request.getClass()
                        .getClassLoader(),
                        new Class[] {HttpServletRequest.class},
                        new InvocationHandler() {

                            @Override
                            public Object invoke(final Object proxy,
                                    final Method method, final Object[] args)
                                    throws Throwable {
                                if (method.getName().equals("isUserInRole")) {
                                    final String role = (String) args[0];
                                    return containerRoles.contains(role);
                                } else if (method.getName().equals(
                                        "getUserPrincipal")) {
                                    return user;
                                } else if (method.getName().equals(
                                        "getRemoteUser")) {
                                    return username;
                                }
                                return method.invoke(request, args);
                            }
                        });
        return result;
    }

    private static String getUsername(final HttpServletRequest request) {
        // Extract authentication credentials
        String authentication = request.getHeader(AUTHORIZATION);
        if (authentication == null) {
            return null;
        }
        if (!authentication.startsWith("Basic ")) {
            return null;
        }
        authentication = authentication.substring("Basic ".length());
        final String[] values = new String(Base64.getDecoder().decode(authentication)).split(":");
        if (values.length < 2) {
            throw new WebApplicationException(400);
            // "Invalid syntax for username and password"
        }
        final String username = values[0];
        final String password = values[1];
        if ((username == null) || (password == null)) {
            return null;
        }
        return username;
    }

    /*
     * (non-Javadoc)
     * @see jakarta.servlet.Filter#init(jakarta.servlet.FilterConfig)
     */
    @Override
    public void init(final FilterConfig filterConfig) {
    }

    /*
     * (non-Javadoc)
     * @see jakarta.servlet.Filter#destroy()
     */
    @Override
    public void destroy() {
    }
}
