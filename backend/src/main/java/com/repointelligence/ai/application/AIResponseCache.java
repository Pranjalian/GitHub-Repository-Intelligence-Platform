package com.repointelligence.ai.application;

import com.repointelligence.ai.domain.AICacheEntry;
import com.repointelligence.ai.infrastructure.persistence.AICacheEntryRepository;
import com.repointelligence.ai.provider.AIProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Validated answers keyed by {provider, model, prompt version, commit SHA, prompt hash}. The prompt is
 * hashed after secret scrubbing. Expired entries are ignored on read and purged hourly.
 */
@Service
public class AIResponseCache {

	private final AICacheEntryRepository entries;

	private final AIProperties properties;

	private final Clock clock;

	public AIResponseCache(AICacheEntryRepository entries, AIProperties properties, Clock clock) {
		this.entries = entries;
		this.properties = properties;
		this.clock = clock;
	}

	public static String key(String provider, String model, String promptVersion, String commitSha, String system,
			String user, String schemaJson) {
		String inputHash = sha256(system + "\u0000" + user + "\u0000" + (schemaJson == null ? "" : schemaJson));
		return sha256(String.join("\u0000", provider, model == null ? "" : model, promptVersion, commitSha, inputHash));
	}

	public Optional<AICacheEntry> lookup(String key) {
		if (!properties.cache().enabled()) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		return entries.findById(key).filter(entry -> entry.getExpiresAt().isAfter(now));
	}

	public void store(String key, String provider, String model, String promptVersion, String feature, String commitSha,
			String content, Object structured, String answeredModel) {
		if (!properties.cache().enabled()) {
			return;
		}
		Instant now = clock.instant();
		entries.save(new AICacheEntry(key, provider, model, promptVersion, feature, commitSha, content, structured,
				answeredModel, now, now.plus(properties.cache().ttl())));
	}

	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	public void purgeExpired() {
		entries.deleteExpired(clock.instant());
	}

	static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
