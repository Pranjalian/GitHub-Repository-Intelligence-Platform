package com.repointelligence.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Checks a username and password against the one configured operator account.
 *
 * <p>
 * Timing: the username comparison is constant-time and a BCrypt comparison against the configured hash runs on
 * every attempt, including when the username is wrong, so response time does not reveal valid usernames. (The
 * plan suggested a dummy hash for the wrong-username case; the real hash has the exact cost the real check has,
 * which a dummy of a different cost would not.)
 */
public class OperatorAuthenticationProvider implements AuthenticationProvider {

	/** BCrypt only reads the first 72 bytes; anything longer cannot be the operator's password. */
	private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

	public static final String ROLE = "ROLE_OPERATOR";

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

	private final byte[] usernameDigest;

	private final String passwordHash;

	public OperatorAuthenticationProvider(AuthProperties properties) {
		this.usernameDigest = digest(properties.username());
		this.passwordHash = properties.passwordHash();
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		String username = authentication.getPrincipal() == null ? "" : authentication.getPrincipal().toString();
		String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();

		boolean usernameMatches = MessageDigest.isEqual(digest(username), usernameDigest);
		boolean passwordFits = password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_PASSWORD_BYTES;
		// Always run one BCrypt comparison, whatever else is wrong.
		boolean passwordMatches = encoder.matches(passwordFits ? password : "", passwordHash);

		if (!(usernameMatches && passwordFits && passwordMatches)) {
			throw new BadCredentialsException("Invalid username or password");
		}
		return UsernamePasswordAuthenticationToken.authenticated(username, null,
				List.of(new SimpleGrantedAuthority(ROLE)));
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

	private static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
