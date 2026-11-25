package com.repointelligence.common.security;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code auth.*}: the single operator account and the login rules (MVP 8). With {@code enabled=false}
 * (the default) the application is open and none of the other values is used; with {@code enabled=true}
 * the backend rejects every {@code /api/**} call that has no valid session, and refuses to start unless the
 * account is configured (see {@link AuthStartupValidator}).
 *
 * <p>
 * {@code passwordHash} is a secret-adjacent value: {@link #toString()} never prints it.
 *
 * @param enabled turns the login on
 * @param username the one account name ({@code AUTH_USERNAME})
 * @param passwordHash BCrypt hash of the password ({@code AUTH_PASSWORD_HASH}); the password itself is never
 * configured anywhere
 * @param session session lifetimes
 * @param throttle failed-login throttling
 */
@ConfigurationProperties("auth")
public record AuthProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String username,
		@DefaultValue("") String passwordHash, @DefaultValue Session session, @DefaultValue Throttle throttle) {

	/**
	 * @param idleTimeout the session ends after this long without a request
	 * @param absoluteTimeout the session ends this long after login, however active it is
	 */
	public record Session(@DefaultValue("PT2H") Duration idleTimeout, @DefaultValue("PT12H") Duration absoluteTimeout) {
	}

	/**
	 * @param maxFailures failures per key (client address, username) that cause a lockout
	 * @param window the lockout lasts this long; failures older than this are forgotten
	 * @param maxTrackedKeys cap on remembered keys, so unknown usernames cannot grow memory without bound
	 */
	public record Throttle(@Min(1) @DefaultValue("5") int maxFailures, @DefaultValue("PT15M") Duration window,
			@Min(100) @DefaultValue("10000") int maxTrackedKeys) {
	}

	@Override
	public String toString() {
		return "AuthProperties[enabled=" + enabled + ", username=" + (username.isBlank() ? "<unset>" : "<set>")
				+ ", passwordHash=" + (passwordHash.isBlank() ? "<unset>" : "<set>") + ", session=" + session
				+ ", throttle=" + throttle + "]";
	}

}
