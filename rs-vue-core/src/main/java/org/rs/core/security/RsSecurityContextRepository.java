package org.rs.core.security;

import javax.servlet.AsyncContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.rs.core.mapper.RsRefreshMapper;
import org.rs.core.security.authentication.RsAuthenticationToken;
import org.rs.core.security.authentication.RsLoginUser;
import org.rs.core.service.RsUserService;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.Transient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SaveContextOnUpdateOrErrorResponseWrapper;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.Assert;
import org.springframework.web.util.WebUtils;

public class RsSecurityContextRepository implements SecurityContextRepository {

	/**
	 * The default key under which the security context will be stored in the session.
	 */
	public static final String SPRING_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";

	protected final Log logger = LogFactory.getLog(this.getClass());

	/**
	 * SecurityContext instance used to check for equality with default (unauthenticated)
	 * content
	 */
	private final Object contextObject = SecurityContextHolder.createEmptyContext();
	private boolean allowSessionCreation = true;
	private boolean disableUrlRewriting = false;
	private String springSecurityContextKey = SPRING_SECURITY_CONTEXT_KEY;
	private RsRefreshMapper refreshMapper;
	private RsUserService userService;
	private AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
	
	public RsSecurityContextRepository(ApplicationContext ctx) {
		
		this.refreshMapper = ctx.getBean(RsRefreshMapper.class);
		this.userService = ctx.getBean(RsUserService.class);
	}

	/**
	 * Gets the security context for the current request (if available) and returns it.
	 * <p>
	 * If the session is null, the context object is null or the context object stored in
	 * the session is not an instance of {@code SecurityContext}, a new context object
	 * will be generated and returned.
	 */
	public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
		HttpServletRequest request = requestResponseHolder.getRequest();
		HttpServletResponse response = requestResponseHolder.getResponse();
		HttpSession httpSession = request.getSession(false);

		SecurityContext context = readSecurityContextFromSession(httpSession);

		if (context == null) {
			if (logger.isDebugEnabled()) {
				logger.debug("No SecurityContext was available from the HttpSession: "
						+ httpSession + ". " + "A new one will be created.");
			}
			context = generateNewContext();

		}

		SaveToSessionResponseWrapper wrappedResponse = new SaveToSessionResponseWrapper(
				response, request, httpSession != null, context);
		requestResponseHolder.setResponse(wrappedResponse);

		requestResponseHolder.setRequest(new SaveToSessionRequestWrapper(
				request, wrappedResponse));

		return context;
	}

	public void saveContext(SecurityContext context, HttpServletRequest request,
			HttpServletResponse response) {
		SaveToSessionResponseWrapper responseWrapper = 
				WebUtils.getNativeResponse(response,SaveToSessionResponseWrapper.class);
		if (responseWrapper == null) {
			throw new IllegalStateException(
					"Cannot invoke saveContext on response "
							+ response
							+ ". You must use the HttpRequestResponseHolder.response after invoking loadContext");
		}
		// saveContext() might already be called by the response wrapper
		// if something in the chain called sendError() or sendRedirect(). This ensures we
		// only call it
		// once per request.
		if (!responseWrapper.isContextSaved()) {
			responseWrapper.saveContext(context);
		}
	}

	public boolean containsContext(HttpServletRequest request) {
		HttpSession session = request.getSession(false);

		if (session == null) {
			return false;
		}

		return session.getAttribute(springSecurityContextKey) != null;
	}

	/**
	 *
	 * @param httpSession the session obtained from the request.
	 */
	private SecurityContext readSecurityContextFromSession(HttpSession httpSession) {
		final boolean debug = logger.isDebugEnabled();

		if (httpSession == null) {
			if (debug) {
				logger.debug("No HttpSession currently exists");
			}

			return null;
		}

		// Session exists, so try to obtain a context from it.

		Object contextFromSession = httpSession.getAttribute(springSecurityContextKey);

		if (contextFromSession == null) {
			if (debug) {
				logger.debug("HttpSession returned null object for SPRING_SECURITY_CONTEXT");
			}

			return null;
		}

		// We now have the security context object from the session.
		if (!(contextFromSession instanceof SecurityContext)) {
			if (logger.isWarnEnabled()) {
				logger.warn(springSecurityContextKey
						+ " did not contain a SecurityContext but contained: '"
						+ contextFromSession
						+ "'; are you improperly modifying the HttpSession directly "
						+ "(you should always use SecurityContextHolder) or using the HttpSession attribute "
						+ "reserved for this class?");
			}

			return null;
		}

		if (debug) {
			logger.debug("Obtained a valid SecurityContext from "
					+ springSecurityContextKey + ": '" + contextFromSession + "'");
		}

		// Everything OK. The only non-null return from this method.
		SecurityContext ctx = (SecurityContext) contextFromSession;
		Authentication auth = ctx.getAuthentication();
		long refreshTime = refreshMapper.getRefresh(springSecurityContextKey);
		long lastAccessedTime = httpSession.getLastAccessedTime();
		if( auth instanceof RsAuthenticationToken && lastAccessedTime <= refreshTime ) {
			
			RsAuthenticationToken token = (RsAuthenticationToken)auth;
			RsLoginUser loginUser = (RsLoginUser) token.getPrincipal();
			UserDetails userDetails = userService.loadUserByUsername(loginUser.getDlmc());
			RsAuthenticationToken newToken = RsAuthenticationToken.createSuccessAuthentication(userDetails, token, userDetails);
			ctx.setAuthentication(newToken);
		}		
		return ctx;
	}

	/**
	 * By default, calls {@link SecurityContextHolder#createEmptyContext()} to obtain a
	 * new context (there should be no context present in the holder when this method is
	 * called). Using this approach the context creation strategy is decided by the
	 * {@link SecurityContextHolderStrategy} in use. The default implementations will
	 * return a new <tt>SecurityContextImpl</tt>.
	 *
	 * @return a new SecurityContext instance. Never null.
	 */
	protected SecurityContext generateNewContext() {
		return SecurityContextHolder.createEmptyContext();
	}

	/**
	 * If set to true (the default), a session will be created (if required) to store the
	 * security context if it is determined that its contents are different from the
	 * default empty context value.
	 * <p>
	 * Note that setting this flag to false does not prevent this class from storing the
	 * security context. If your application (or another filter) creates a session, then
	 * the security context will still be stored for an authenticated user.
	 *
	 * @param allowSessionCreation
	 */
	public void setAllowSessionCreation(boolean allowSessionCreation) {
		this.allowSessionCreation = allowSessionCreation;
	}

	/**
	 * Allows the use of session identifiers in URLs to be disabled. Off by default.
	 *
	 * @param disableUrlRewriting set to <tt>true</tt> to disable URL encoding methods in
	 * the response wrapper and prevent the use of <tt>jsessionid</tt> parameters.
	 */
	public void setDisableUrlRewriting(boolean disableUrlRewriting) {
		this.disableUrlRewriting = disableUrlRewriting;
	}

	/**
	 * Allows the session attribute name to be customized for this repository instance.
	 *
	 * @param springSecurityContextKey the key under which the security context will be
	 * stored. Defaults to {@link #SPRING_SECURITY_CONTEXT_KEY}.
	 */
	public void setSpringSecurityContextKey(String springSecurityContextKey) {
		Assert.hasText(springSecurityContextKey,
				"springSecurityContextKey cannot be empty");
		this.springSecurityContextKey = springSecurityContextKey;
	}

	// ~ Inner Classes
	// ==================================================================================================

	private static class SaveToSessionRequestWrapper extends
			HttpServletRequestWrapper {
		private final SaveContextOnUpdateOrErrorResponseWrapper response;

		SaveToSessionRequestWrapper(HttpServletRequest request,
				SaveContextOnUpdateOrErrorResponseWrapper response) {
			super(request);
			this.response = response;
		}

		@Override
		public AsyncContext startAsync() {
			response.disableSaveOnResponseCommitted();
			return super.startAsync();
		}

		@Override
		public AsyncContext startAsync(ServletRequest servletRequest,
				ServletResponse servletResponse) throws IllegalStateException {
			response.disableSaveOnResponseCommitted();
			return super.startAsync(servletRequest, servletResponse);
		}
	}

	/**
	 * Wrapper that is applied to every request/response to update the
	 * <code>HttpSession<code> with
	 * the <code>SecurityContext</code> when a <code>sendError()</code> or
	 * <code>sendRedirect</code> happens. See SEC-398.
	 * <p>
	 * Stores the necessary state from the start of the request in order to make a
	 * decision about whether the security context has changed before saving it.
	 */
	final class SaveToSessionResponseWrapper extends
			SaveContextOnUpdateOrErrorResponseWrapper {

		private final HttpServletRequest request;
		private final boolean httpSessionExistedAtStartOfRequest;
		private final SecurityContext contextBeforeExecution;
		private final Authentication authBeforeExecution;

		/**
		 * Takes the parameters required to call <code>saveContext()</code> successfully
		 * in addition to the request and the response object we are wrapping.
		 *
		 * @param request the request object (used to obtain the session, if one exists).
		 * @param httpSessionExistedAtStartOfRequest indicates whether there was a session
		 * in place before the filter chain executed. If this is true, and the session is
		 * found to be null, this indicates that it was invalidated during the request and
		 * a new session will now be created.
		 * @param context the context before the filter chain executed. The context will
		 * only be stored if it or its contents changed during the request.
		 */
		SaveToSessionResponseWrapper(HttpServletResponse response,
				HttpServletRequest request, boolean httpSessionExistedAtStartOfRequest,
				SecurityContext context) {
			super(response, disableUrlRewriting);
			this.request = request;
			this.httpSessionExistedAtStartOfRequest = httpSessionExistedAtStartOfRequest;
			this.contextBeforeExecution = context;
			this.authBeforeExecution = context.getAuthentication();
		}

		/**
		 * Stores the supplied security context in the session (if available) and if it
		 * has changed since it was set at the start of the request. If the
		 * AuthenticationTrustResolver identifies the current user as anonymous, then the
		 * context will not be stored.
		 *
		 * @param context the context object obtained from the SecurityContextHolder after
		 * the request has been processed by the filter chain.
		 * SecurityContextHolder.getContext() cannot be used to obtain the context as it
		 * has already been cleared by the time this method is called.
		 *
		 */
		@Override
		protected void saveContext(SecurityContext context) {
			final Authentication authentication = context.getAuthentication();
			HttpSession httpSession = request.getSession(false);

			// See SEC-776
			if (authentication == null || trustResolver.isAnonymous(authentication)) {
				if (logger.isDebugEnabled()) {
					logger.debug("SecurityContext is empty or contents are anonymous - context will not be stored in HttpSession.");
				}

				if (httpSession != null && authBeforeExecution != null) {
					// SEC-1587 A non-anonymous context may still be in the session
					// SEC-1735 remove if the contextBeforeExecution was not anonymous
					httpSession.removeAttribute(springSecurityContextKey);
				}
				return;
			}

			if (httpSession == null) {
				httpSession = createNewSessionIfAllowed(context);
			}

			// If HttpSession exists, store current SecurityContext but only if it has
			// actually changed in this thread (see SEC-37, SEC-1307, SEC-1528)
			if (httpSession != null) {
				// We may have a new session, so check also whether the context attribute
				// is set SEC-1561
				if (contextChanged(context)
						|| httpSession.getAttribute(springSecurityContextKey) == null) {
					httpSession.setAttribute(springSecurityContextKey, context);

					if (logger.isDebugEnabled()) {
						logger.debug("SecurityContext '" + context
								+ "' stored to HttpSession: '" + httpSession);
					}
				}
			}
		}

		private boolean contextChanged(SecurityContext context) {
			return context != contextBeforeExecution
					|| context.getAuthentication() != authBeforeExecution;
		}

		private HttpSession createNewSessionIfAllowed(SecurityContext context) {
			if (isTransientAuthentication(context.getAuthentication())) {
				return null;
			}

			if (httpSessionExistedAtStartOfRequest) {
				if (logger.isDebugEnabled()) {
					logger.debug("HttpSession is now null, but was not null at start of request; "
							+ "session was invalidated, so do not create a new session");
				}

				return null;
			}

			if (!allowSessionCreation) {
				if (logger.isDebugEnabled()) {
					logger.debug("The HttpSession is currently null, and the "
							+ RsSecurityContextRepository.class.getSimpleName()
							+ " is prohibited from creating an HttpSession "
							+ "(because the allowSessionCreation property is false) - SecurityContext thus not "
							+ "stored for next request");
				}

				return null;
			}
			// Generate a HttpSession only if we need to

			if (contextObject.equals(context)) {
				if (logger.isDebugEnabled()) {
					logger.debug("HttpSession is null, but SecurityContext has not changed from default empty context: ' "
							+ context
							+ "'; not creating HttpSession or storing SecurityContext");
				}

				return null;
			}

			if (logger.isDebugEnabled()) {
				logger.debug("HttpSession being created as SecurityContext is non-default");
			}

			try {
				return request.getSession(true);
			}
			catch (IllegalStateException e) {
				// Response must already be committed, therefore can't create a new
				// session
				logger.warn("Failed to create a session, as response has been committed. Unable to store"
						+ " SecurityContext.");
			}

			return null;
		}
	}

	private boolean isTransientAuthentication(Authentication authentication) {
		return AnnotationUtils.getAnnotation(authentication.getClass(), Transient.class) != null;
	}

	/**
	 * Sets the {@link AuthenticationTrustResolver} to be used. The default is
	 * {@link AuthenticationTrustResolverImpl}.
	 *
	 * @param trustResolver the {@link AuthenticationTrustResolver} to use. Cannot be
	 * null.
	 */
	public void setTrustResolver(AuthenticationTrustResolver trustResolver) {
		Assert.notNull(trustResolver, "trustResolver cannot be null");
		this.trustResolver = trustResolver;
	}
}
