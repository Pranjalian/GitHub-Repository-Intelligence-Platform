package com.repointelligence.support;

import com.repointelligence.ai.provider.DataHandling;
import com.repointelligence.ai.provider.EmbeddingHealth;
import com.repointelligence.ai.provider.EmbeddingModelService;
import com.repointelligence.ai.provider.EmbeddingPurpose;
import com.repointelligence.ai.provider.EmbeddingResponse;
import com.repointelligence.ai.provider.ProviderHealth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic stand-in for an embedding model in tests: feature hashing of identifier parts
 * (camelCase and underscores split, lower-cased) into a fixed number of dimensions, L2-normalised. It is
 * lexical, not semantic, so it tests the pipeline (batching, caching by hash, model versions, vector
 * search, fusion), not embedding quality. Model name and version can be switched to simulate a model change.
 */
public class HashingEmbedder implements EmbeddingModelService {

	public static final String ID = "hashing";

	private static final Pattern WORD = Pattern.compile("[A-Za-z][a-z0-9]*|[0-9]+");

	private final int dimensions;

	private volatile String model = "hash-embed";

	private volatile String version = "v1";

	private volatile boolean up = true;

	private final List<String> inputs = new CopyOnWriteArrayList<>();

	private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();

	public HashingEmbedder(int dimensions) {
		this.dimensions = dimensions;
	}

	public void use(String newModel, String newVersion) {
		this.model = newModel;
		this.version = newVersion;
	}

	public void setUp(boolean value) {
		this.up = value;
	}

	public void reset() {
		inputs.clear();
		batchSizes.clear();
		use("hash-embed", "v1");
		up = true;
	}

	/** Every text embedded since the last reset, documents and queries. */
	public List<String> inputs() {
		return List.copyOf(inputs);
	}

	public List<Integer> batchSizes() {
		return List.copyOf(batchSizes);
	}

	@Override
	public String getProviderId() {
		return ID;
	}

	@Override
	public DataHandling dataHandling() {
		return DataHandling.LOCAL;
	}

	@Override
	public String embeddingModel() {
		return model;
	}

	@Override
	public EmbeddingHealth embeddingHealth() {
		return up ? new EmbeddingHealth(ProviderHealth.up("ok", Instant.now()), model, version)
				: new EmbeddingHealth(ProviderHealth.down("stand-in is down", Instant.now()), model, null);
	}

	@Override
	public EmbeddingResponse embed(List<String> texts, EmbeddingPurpose purpose) {
		inputs.addAll(texts);
		batchSizes.add(texts.size());
		List<float[]> vectors = new ArrayList<>();
		for (String text : texts) {
			vectors.add(vector(text));
		}
		return EmbeddingResponse.ok(model, vectors, null);
	}

	public float[] vector(String text) {
		float[] v = new float[dimensions];
		Matcher m = WORD.matcher(text.replaceAll("([a-z0-9])([A-Z])", "$1 $2"));
		while (m.find()) {
			String word = m.group().toLowerCase(Locale.ROOT);
			if (word.length() < 2) {
				continue;
			}
			// the version changes the space, so vectors of two versions are really different
			int h = Math.floorMod((version + ":" + word).hashCode(), dimensions);
			v[h] += 1f;
		}
		double norm = 0;
		for (float f : v) {
			norm += f * f;
		}
		if (norm > 0) {
			float n = (float) Math.sqrt(norm);
			for (int i = 0; i < v.length; i++) {
				v[i] /= n;
			}
		}
		return v;
	}

}
