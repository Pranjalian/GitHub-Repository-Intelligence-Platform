package com.repointelligence.common.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.repointelligence.common.exception.InvalidCredentialsException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three auth endpoints. They answer even when {@code auth.enabled=false}: {@code session} reports
 * {@code authRequired=false} so the SPA shows no login UI; {@code login} is then 404 and {@code logout} a no-op.
 * Every response is {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Single-user login (MVP 8). Open even when authentication is required.")
public class AuthController {

	private final AuthProperties properties;

	private final AuthenticationManager authenticationManager;

	private final SecurityContextRepository securityContextRepository;

	private final CsrfTokenRepository csrfTokenRepository;

	private final LoginThrottle throttle;

	public AuthController(AuthProperties properties, AuthenticationManager authenticationManager,
			SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository,
			LoginThrottle throttle) {
		this.properties = properties;
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
		this.csrfTokenRepository = csrfTokenRepository;
		this.throttle = throttle;
	}

	/** The current state of the caller's session. Always 200, so the SPA can decide what to show. */
	@GetMapping("/session")
	@Operation(summary = "Is a login required, and is the caller signed in?",
			description = "Always 200, never 401. Sets the XSRF-TOKEN cookie when a login is required.")
	public ResponseEntity<SessionDTO> session(CsrfToken csrfToken) {
		if (!properties.enabled()) {
			return noStore(new SessionDTO(false, false, null));
		}
		csrfToken.getToken(); // reading the deferred token writes the XSRF-TOKEN cookie
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken
				|| !authentication.isAuthenticated()) {
			return noStore(new SessionDTO(true, false, null));
		}
		return noStore(new SessionDTO(true, true, authentication.getName()));
	}

	@PostMapping("/login")
	@Operation(summary = "Sign in",
			description = "Needs the X-XSRF-TOKEN header like any POST. 401 for any credential failure (one message), "
					+ "429 with Retry-After when throttled. Starts a new session id.")
	@ApiResponse(responseCode = "401", description = "Invalid username or password")
	@ApiResponse(responseCode = "429", description = "Too many failed attempts; see Retry-After")
	public ResponseEntity<SessionDTO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http,
			HttpServletResponse response) {
		if (!properties.enabled()) {
			throw new ResourceNotFoundException("Authentication is not enabled on this server.");
		}
		String client = http.getRemoteAddr();
		throttle.checkAllowed(client, request.username());

		Authentication authenticated;
		try {
			authenticated = authenticationManager
				.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
		}
		catch (AuthenticationException ex) {
			throttle.recordFailure(client, request.username());
			throw new InvalidCredentialsException();
		}
		throttle.recordSuccess(client, request.username());

		// Session fixation: never reuse a session id that existed before the login.
		HttpSession previous = http.getSession(false);
		if (previous != null) {
			previous.invalidate();
		}
		HttpSession session = http.getSession(true);
		session.setMaxInactiveInterval((int) Math.min(Integer.MAX_VALUE, properties.session().idleTimeout().toSeconds()));
		SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
		context.setAuthentication(authenticated);
		SecurityContextHolder.getContextHolderStrategy().setContext(context);
		securityContextRepository.saveContext(context, http, response);

		return noStore(new SessionDTO(true, true, authenticated.getName()));
	}

	@PostMapping("/logout")
	@Operation(summary = "Sign out", description = "Invalidates the server-side session and clears the cookies. Idempotent.")
	public ResponseEntity<Void> logout(HttpServletRequest http, HttpServletResponse response) {
		if (properties.enabled()) {
			HttpSession session = http.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			SecurityContextHolder.clearContext();
			csrfTokenRepository.saveToken(null, http, response);
			ResponseCookie expired = ResponseCookie.from("REPOINTEL_SESSION", "")
				.path("/")
				.maxAge(0)
				.httpOnly(true)
				.sameSite("Lax")
				.build();
			response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
		}
		return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
	}

	/** Body of {@code GET /session} and {@code POST /login}. {@code username} is only present when signed in. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionDTO(boolean authRequired, boolean authenticated, String username) {
	}

	public record LoginRequest(@NotBlank @Size(max = 256) String username, @NotBlank @Size(max = 1024) String password) {

		/** Never print the password, even by accident. */
		@Override
		public String toString() {
			return "LoginRequest[username=" + username + ", password=<hidden>]";
		}

	}

}
