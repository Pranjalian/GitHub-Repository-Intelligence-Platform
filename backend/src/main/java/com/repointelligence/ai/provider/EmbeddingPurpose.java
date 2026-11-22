package com.repointelligence.ai.provider;

/** What a text is embedded for; some providers use different task types for stored chunks and queries. */
public enum EmbeddingPurpose {

	/** A stored chunk of repository content. */
	DOCUMENT,
	/** A search query or question. */
	QUERY

}
