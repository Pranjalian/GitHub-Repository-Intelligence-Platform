package com.repointelligence.common.security;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single place that masks secrets. Used by logging, finding evidence and (later) AI prompts.
 * Matched values are replaced with {@value #MASK}; key names and surrounding syntax are preserved.
 */
public final class SecretRedactor {

	public static final String MASK = "***REDACTED***";

	private static final String SECRET_KEYS = "[\\w.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credentials?)";

	/** Whole-match secrets: the entire match is replaced. */
	private static final List<Pattern> WHOLE_MATCH = List.of(
			// PEM private key blocks (also truncated blocks without an END marker)
			Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----(?:[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----|[\\s\\S]*$)"),
			// GitHub tokens
			Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"), Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
			// AWS access key ids
			Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
			// Slack and Stripe style tokens
			Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"), Pattern.compile("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{16,}\\b"),
			// JSON Web Tokens
			Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
			// BCrypt hashes (the operator's AUTH_PASSWORD_HASH)
			Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}"));

	/**
	 * Cookie and CSRF headers: the whole header value is masked. ({@code Authorization} is handled by
	 * {@link #AUTH_SCHEME}, which keeps the scheme.)
	 */
	private static final Pattern SENSITIVE_HEADER = Pattern.compile(
			"(?i)(?<key>\\b(?:Set-Cookie|Cookie|Proxy-Authorization|X-XSRF-TOKEN|X-CSRF-TOKEN)\\s*[:=]\\s*)(?<value>[^\\r\\n]+)");

	/** Session and CSRF cookies by name, wherever they appear (a header, a URL, a message). */
	private static final Pattern SESSION_COOKIE = Pattern.compile(
			"(?i)(?<key>\\b(?:REPOINTEL_SESSION|JSESSIONID|XSRF-TOKEN)\\s*=\\s*)(?<value>[^\\s;,\"']+)");

	/** "Bearer xxx" / "Basic xxx": keep the scheme, mask the credential. */
	private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)(\\s+)[A-Za-z0-9._~+/=-]{8,}");

	/** key=value, key: value, "key": "value" (JSON/YAML/properties): keep key and quoting, mask the value. */
	private static final Pattern KEY_VALUE = Pattern.compile(
			"(?i)(?<key>[\"']?" + SECRET_KEYS + "[\"']?\\s*[:=]\\s*)(?<quote>[\"']?)(?<value>[^\\s\"',;&}\\]]+)");

	private SecretRedactor() {
	}

	public static String redact(String input) {
		if (input == null || input.isEmpty()) {
			return input;
		}
		String result = input;
		for (Pattern pattern : WHOLE_MATCH) {
			result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(MASK));
		}
		result = SENSITIVE_HEADER.matcher(result).replaceAll(match -> Matcher.quoteReplacement(match.group("key") + MASK));
		result = SESSION_COOKIE.matcher(result).replaceAll(match -> Matcher.quoteReplacement(match.group("key") + MASK));
		result = AUTH_SCHEME.matcher(result).replaceAll("$1$2" + Matcher.quoteReplacement(MASK));
		return KEY_VALUE.matcher(result).replaceAll(match -> {
			String value = match.group("value");
			String replacement = MASK.equals(value) ? value : MASK;
			return Matcher.quoteReplacement(match.group("key") + match.group("quote") + replacement);
		});
	}

}
