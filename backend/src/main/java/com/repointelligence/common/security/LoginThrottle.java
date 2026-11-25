package com.repointelligence.common.security;

import com.repointelligence.common.exception.LoginThrottledException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Slows password guessing: failed logins are counted per client address and per username (two independent keys).
 * After {@code maxFailures} failures within the window a key is locked for the window; while locked every
 * attempt for that key gets 429 with Retry-After, even with the correct password, and refused attempts are not
 * counted (so the lock cannot be extended by hammering). A successful login clears both keys.
 *
 * <p>
 * In memory and per process, which is enough for the single-replica deployment. The number of remembered keys
 * is capped (oldest first) so random usernames cannot exhaust memory. The per-username key is the control that
 * still works when the client address is spoofed or shared; the price is that a stranger can lock the real
 * operator out for one window, which is accepted for a single-user tool.
 */
public class LoginThrottle {

	private final Clock clock;

	private final int maxFailures;

	private final Duration window;

	private final int maxTrackedKeys;

	/** Insertion-ordered, so the oldest key is evicted first when the cap is hit. */
	private final Map<String, Entry> entries = new LinkedHashMap<>();

	public LoginThrottle(AuthProperties.Throttle settings, Clock clock) {
		this.clock = clock;
		this.maxFailures = settings.maxFailures();
		this.window = settings.window();
		this.maxTrackedKeys = settings.maxTrackedKeys();
	}

	/** Seconds until the next attempt is allowed, or 0 when the client may try. */
	public synchronized long retryAfterSeconds(String clientAddress, String username) {
		Instant now = clock.instant();
		long ip = remaining("ip:" + clientAddress, now);
		long user = remaining("user:" + normalize(username), now);
		return Math.max(ip, user);
	}

	public synchronized void recordFailure(String clientAddress, String username) {
		Instant now = clock.instant();
		fail("ip:" + clientAddress, now);
		fail("user:" + normalize(username), now);
	}

	public synchronized void recordSuccess(String clientAddress, String username) {
		entries.remove("ip:" + clientAddress);
		entries.remove("user:" + normalize(username));
	}

	/** Throws {@link LoginThrottledException} when either key is locked. */
	public void checkAllowed(String clientAddress, String username) {
		long retryAfter = retryAfterSeconds(clientAddress, username);
		if (retryAfter > 0) {
			throw new LoginThrottledException(retryAfter);
		}
	}

	synchronized int trackedKeys() {
		return entries.size();
	}

	/** Forgets everything. Tests only (the production code never needs it). */
	synchronized void clear() {
		entries.clear();
	}

	private long remaining(String key, Instant now) {
		Entry entry = entries.get(key);
		if (entry == null) {
			return 0;
		}
		if (entry.lockedUntil != null) {
			if (now.isBefore(entry.lockedUntil)) {
				return Math.max(1, (long) Math.ceil(Duration.between(now, entry.lockedUntil).toMillis() / 1000.0));
			}
			entries.remove(key);
			return 0;
		}
		entry.dropOlderThan(now.minus(window));
		if (entry.failures.isEmpty()) {
			entries.remove(key);
		}
		return 0;
	}

	private void fail(String key, Instant now) {
		Entry entry = entries.remove(key); // re-inserted below, which refreshes its eviction order
		if (entry == null || entry.lockedUntil != null && !now.isBefore(entry.lockedUntil)) {
			entry = new Entry();
		}
		entry.dropOlderThan(now.minus(window));
		entry.failures.addLast(now);
		if (entry.failures.size() >= maxFailures) {
			entry.lockedUntil = now.plus(window);
			entry.failures.clear();
		}
		entries.put(key, entry);
		while (entries.size() > maxTrackedKeys) {
			String oldest = entries.keySet().iterator().next();
			entries.remove(oldest);
		}
	}

	private static String normalize(String username) {
		String value = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
		return value.length() > 100 ? value.substring(0, 100) : value;
	}

	private static final class Entry {

		final Deque<Instant> failures = new ArrayDeque<>();

		Instant lockedUntil;

		void dropOlderThan(Instant cutoff) {
			while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
				failures.pollFirst();
			}
		}

	}

}
