package com.repointelligence.rag.chunking;

/** The unit a chunk represents. */
public enum ChunkType {

	/** A Java type's declaration with its fields and other non-method members (methods are separate chunks). */
	CLASS,
	/** One Java method or constructor, with its Javadoc. */
	METHOD,
	/** A top-level section of a YAML or properties file. */
	CONFIG_SECTION,
	/** A Markdown section under one heading. */
	DOC_SECTION,
	/**
	 * A fixed window of lines, for languages without a structural chunker (or a Java file that did not parse).
	 * Labelled as such, because a window may cut a construct in half.
	 */
	LINE_WINDOW

}
