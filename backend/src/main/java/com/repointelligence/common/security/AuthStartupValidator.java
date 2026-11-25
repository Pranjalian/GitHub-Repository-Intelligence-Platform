package com.repointelligence.common.security;

import jakarta.annotation.PostConstruct;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Fail closed: with {@code auth.enabled=true} the application refuses to start unless the account is
 * configured, so a deployment can never come up "protected" by a login nobody can pass, or open by mistake.
 * Messages name the variable, never its value.
 */
@Component
public class AuthStartupValidator {

	/** BCrypt as written by {@code htpasswd -B}, Spring and most libraries: $2a$/$2b$/$2y$, cost, 53 characters. */
	static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$.{53}$");

	private final AuthProperties properties;

	public AuthStartupValidator(AuthProperties properties) {
		this.properties = properties;
	}

	@PostConstruct
	void validate() {
		if (!properties.enabled()) {
			return;
		}
		if (properties.username().isBlank()) {
			throw new IllegalStateException(
					"AUTH_ENABLED is true but AUTH_USERNAME is missing or blank. Set AUTH_USERNAME, or set AUTH_ENABLED=false for local use.");
		}
		String hash = properties.passwordHash();
		if (hash.isBlank()) {
			throw new IllegalStateException(
					"AUTH_ENABLED is true but AUTH_PASSWORD_HASH is missing. Set it to a BCrypt hash (cost 12); see the README, Authentication.");
		}
		if (!BCRYPT.matcher(hash).matches()) {
			throw new IllegalStateException(
					"AUTH_PASSWORD_HASH is not a valid BCrypt hash (expected $2a$/$2b$/$2y$, a two-digit cost and 60 characters in total). "
							+ "In compose files and .env write every $ as $$; see the README, Authentication.");
		}
	}

}
