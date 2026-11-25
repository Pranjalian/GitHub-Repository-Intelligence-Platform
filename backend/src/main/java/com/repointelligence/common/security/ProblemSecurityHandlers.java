package com.repointelligence.common.security;

import com.repointelligence.common.exception.AccessForbiddenException;
import com.repointelligence.common.exception.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Turns the two security rejections into the same RFC 9457 problem bodies as every other error, by handing
 * them to the application's exception handling ({@code GlobalExceptionHandler}). No
 * {@code WWW-Authenticate} header is sent, so a browser never shows its own login dialog.
 */
public final class ProblemSecurityHandlers {

	private ProblemSecurityHandlers() {
	}

	/** 401: no valid session. */
	public static AuthenticationEntryPoint entryPoint(HandlerExceptionResolver resolver) {
		return new AuthenticationEntryPoint() {
			@Override
			public void commence(HttpServletRequest request, HttpServletResponse response,
					AuthenticationException ex) {
				resolver.resolveException(request, response, null, new AuthenticationRequiredException());
			}
		};
	}

	/** 403: a failed CSRF check, or any other refused access. */
	public static AccessDeniedHandler accessDeniedHandler(HandlerExceptionResolver resolver) {
		return new AccessDeniedHandler() {
			@Override
			public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) {
				String message = ex instanceof CsrfException
						? "Missing or invalid CSRF token. Reload the page and try again."
						: "Access denied.";
				resolver.resolveException(request, response, null, new AccessForbiddenException(message));
			}
		};
	}

}
