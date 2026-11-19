package com.repointelligence.common.exception;

import com.repointelligence.common.logging.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Produces RFC 9457 {@link ProblemDetail} responses for every error. Each body carries
 * {@code timestamp} and {@code requestId}; unexpected errors expose neither a message nor a stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApi(ApiException ex, HttpServletRequest request) {
		ProblemDetail problem = problem(ex.getStatus(), ex.getMessage(), request);
		HttpHeaders headers = new HttpHeaders();
		if (ex instanceof UpstreamRateLimitException rateLimit) {
			Long retryAfter = rateLimit.retryAfterSeconds(Instant.now());
			if (retryAfter != null) {
				headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
				problem.setProperty("retryAfterSeconds", retryAfter);
			}
			if (rateLimit.getResetAt() != null) {
				problem.setProperty("resetAt", rateLimit.getResetAt().toString());
			}
		}
		if (ex instanceof SubmissionRateLimitException submissionLimit) {
			headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(submissionLimit.getRetryAfterSeconds()));
			problem.setProperty("retryAfterSeconds", submissionLimit.getRetryAfterSeconds());
		}
		return new ResponseEntity<>(problem, headers, ex.getStatus());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
		String requestId = currentRequestId();
		log.error("Unhandled exception for {} {} (requestId={})", request.getMethod(), request.getRequestURI(),
				requestId, ex);
		ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR,
				"An unexpected error occurred. Quote the request id when reporting it.", request);
		return new ResponseEntity<>(problem, HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed.", request);
		List<String> details = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> error.getField() + ": " + error.getDefaultMessage())
			.toList();
		problem.setProperty("details", details);
		return new ResponseEntity<>(problem, headers, HttpStatus.BAD_REQUEST);
	}

	/** Framework-raised MVC exceptions (bad JSON, wrong method, missing resource, ...). */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		HttpStatus status = HttpStatus.valueOf(statusCode.value());
		ProblemDetail problem = body instanceof ProblemDetail existing ? existing : ProblemDetail.forStatus(status);
		if (status.is4xxClientError()) {
			// Framework detail messages for 4xx are safe (they describe the request, not server internals).
			if (problem.getDetail() == null) {
				problem.setDetail(status.getReasonPhrase());
			}
		}
		else {
			log.error("Framework exception ({}) (requestId={})", status, currentRequestId(), ex);
			problem.setDetail("An unexpected error occurred. Quote the request id when reporting it.");
		}
		decorate(problem, requestPath(request));
		return new ResponseEntity<>(problem, headers, statusCode);
	}

	private ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		decorate(problem, request.getRequestURI());
		return problem;
	}

	private ProblemDetail problem(HttpStatus status, String detail, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		decorate(problem, requestPath(request));
		return problem;
	}

	private void decorate(ProblemDetail problem, String path) {
		problem.setInstance(URI.create(path == null ? "/" : path));
		problem.setProperty("timestamp", Instant.now().toString());
		problem.setProperty("requestId", currentRequestId());
	}

	private static String requestPath(WebRequest request) {
		if (request instanceof ServletWebRequest servletRequest) {
			return servletRequest.getRequest().getRequestURI();
		}
		return null;
	}

	private static String currentRequestId() {
		String requestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
		return requestId != null ? requestId : UUID.randomUUID().toString();
	}

}
