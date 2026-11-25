package com.repointelligence.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Keeps the login in the server-side HTTP session and enforces the two lifetimes with the application
 * {@link Clock} (so tests can move time): an <b>idle</b> timeout (a request renews it) and an <b>absolute</b>
 * lifetime counted from login (nothing renews it). An expired session is invalidated and the request continues
 * as anonymous, which the filter chain answers with 401. The servlet container's own inactivity timeout is set
 * to the same idle value at login as a backstop.
 */
public class SessionLifetimeSecurityContextRepository implements SecurityContextRepository {

	static final String LOGIN_AT = SessionLifetimeSecurityContextRepository.class.getName() + ".LOGIN_AT";

	static final String LAST_SEEN = SessionLifetimeSecurityContextRepository.class.getName() + ".LAST_SEEN";

	private final HttpSessionSecurityContextRepository delegate = new HttpSessionSecurityContextRepository();

	private final SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();

	private final AuthProperties.Session lifetimes;

	private final Clock clock;

	public SessionLifetimeSecurityContextRepository(AuthProperties.Session lifetimes, Clock clock) {
		this.lifetimes = lifetimes;
		this.clock = clock;
	}

	@Override
	public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
		return new LazyContext(() -> load(request));
	}

	/** Legacy entry point of the interface; the filter chain uses {@link #loadDeferredContext}. */
	@Override
	@SuppressWarnings("deprecation")
	public SecurityContext loadContext(org.springframework.security.web.context.HttpRequestResponseHolder holder) {
		return load(holder.getRequest());
	}

	/** Loads once, on first use, so requests that never ask who is calling do not touch the session. */
	private static final class LazyContext implements DeferredSecurityContext {

		private final Supplier<SecurityContext> loader;

		private SecurityContext context;

		LazyContext(Supplier<SecurityContext> loader) {
			this.loader = loader;
		}

		@Override
		public synchronized SecurityContext get() {
			if (context == null) {
				context = loader.get();
			}
			return context;
		}

		@Override
		public boolean isGenerated() {
			return false;
		}

	}

	private SecurityContext load(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return strategy.createEmptyContext();
		}
		Instant now = clock.instant();
		Instant loginAt = (Instant) session.getAttribute(LOGIN_AT);
		Instant lastSeen = (Instant) session.getAttribute(LAST_SEEN);
		if (loginAt == null || lastSeen == null) {
			// not a session created by a login: never trust it
			return strategy.createEmptyContext();
		}
		if (!now.isBefore(loginAt.plus(lifetimes.absoluteTimeout()))
				|| !now.isBefore(lastSeen.plus(lifetimes.idleTimeout()))) {
			session.invalidate();
			return strategy.createEmptyContext();
		}
		session.setAttribute(LAST_SEEN, now);
		return delegate.loadDeferredContext(request).get();
	}

	@Override
	public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
		delegate.saveContext(context, request, response);
		Authentication authentication = context.getAuthentication();
		HttpSession session = request.getSession(false);
		if (session != null && authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)) {
			Instant now = clock.instant();
			if (session.getAttribute(LOGIN_AT) == null) {
				session.setAttribute(LOGIN_AT, now);
			}
			session.setAttribute(LAST_SEEN, now);
		}
	}

	@Override
	public boolean containsContext(HttpServletRequest request) {
		return delegate.containsContext(request);
	}

}
