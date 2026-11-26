package com.repointelligence.common.security;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Server-side enforcement of the login (MVP 8). The filter chain differs by one switch:
 * <ul>
 * <li>{@code auth.enabled=false} (default): permit-all, no CSRF, no session, no added headers: the application
 * behaves exactly as before MVP 8.</li>
 * <li>{@code auth.enabled=true}: everything except the health check and the three auth endpoints needs a valid
 * session; state-changing requests also need the CSRF header; failures are 401/403 problem bodies.</li>
 * </ul>
 * CSRF uses the cookie-to-header pattern: the readable {@code XSRF-TOKEN} cookie is copied by the SPA into the
 * {@code X-XSRF-TOKEN} header. The plain (non-masked) token handler is used because the cookie holds the raw
 * token and the API serves JSON, not HTML pages that embed the token (the BREACH masking targets those).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class SecurityConfig {

	static final String CSRF_COOKIE = "XSRF-TOKEN";

	static final String CSRF_HEADER = "X-XSRF-TOKEN";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AuthProperties properties,
			AuthStartupValidator validator, SecurityContextRepository securityContextRepository,
			CsrfTokenRepository csrfTokenRepository,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		if (!properties.enabled()) {
			return http.csrf(csrf -> csrf.disable())
				.headers(headers -> headers.disable())
				.requestCache(cache -> cache.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
				.build();
		}
		return http
			.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
			// No default security headers: nginx and Vercel already send the CSP, X-Frame-Options and nosniff (a second,
			// duplicate X-Frame-Options value is invalid), and Cache-Control is set per response (no-store on /auth/*).
			.headers(headers -> headers.disable())
			.requestCache(cache -> cache.disable())
			.logout(logout -> logout.disable())
			.securityContext(context -> context.securityContextRepository(securityContextRepository))
			.sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.exceptionHandling(handling -> handling.authenticationEntryPoint(ProblemSecurityHandlers.entryPoint(exceptionResolver))
				.accessDeniedHandler(ProblemSecurityHandlers.accessDeniedHandler(exceptionResolver)))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
				.permitAll()
				.requestMatchers("/api/v1/auth/session", "/api/v1/auth/login", "/api/v1/auth/logout", "/error")
				.permitAll()
				// Everything else, including /api/**, other actuator endpoints, Swagger and unknown paths: a session.
				.anyRequest()
				.authenticated())
			.build();
	}

	@Bean
	SecurityContextRepository securityContextRepository(AuthProperties properties, Clock clock) {
		return new SessionLifetimeSecurityContextRepository(properties.session(), clock);
	}

	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieName(CSRF_COOKIE);
		repository.setHeaderName(CSRF_HEADER);
		repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").path("/"));
		return repository;
	}

	@Bean
	AuthenticationManager authenticationManager(AuthProperties properties) {
		ProviderManager manager = new ProviderManager(new OperatorAuthenticationProvider(properties));
		manager.setEraseCredentialsAfterAuthentication(true);
		return manager;
	}

	@Bean
	LoginThrottle loginThrottle(AuthProperties properties, Clock clock) {
		return new LoginThrottle(properties.throttle(), clock);
	}

}
