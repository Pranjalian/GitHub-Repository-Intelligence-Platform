package com.repointelligence.rag;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code rag.*}: indexing, retrieval and Q&A (MVP 4). Indexing and deterministic search need no model;
 * embeddings and answers are AI features configured under {@code ai.*}.
 */
@ConfigurationProperties("rag")
public record RagProperties(@DefaultValue Indexing indexing, @DefaultValue Retrieval retrieval, @DefaultValue Qa qa) {

	/**
	 * @param allowDownload rebuild a deleted workspace from the commit's tarball (one GitHub download) to index it
	 * @param maxFileKb larger files are not chunked (reported as skipped)
	 * @param maxFiles files chunked per snapshot; the rest are skipped and reported
	 * @param maxChunks chunks stored per snapshot; indexing stops there and says so
	 * @param maxChunkLines longer classes, methods and sections are split into parts of this many lines
	 * @param windowLines line-window size for languages without a structural chunker
	 * @param windowOverlap lines shared by consecutive windows
	 * @param excludedFiles file-name globs never indexed (lockfiles, minified and generated files)
	 * @param queueCapacity index jobs waiting for the single indexing thread
	 */
	public record Indexing(@DefaultValue("true") boolean allowDownload, @DefaultValue("256") int maxFileKb,
			@DefaultValue("5000") int maxFiles, @DefaultValue("20000") int maxChunks,
			@DefaultValue("150") int maxChunkLines, @DefaultValue("60") int windowLines,
			@DefaultValue("10") int windowOverlap,
			@DefaultValue({ "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "*.min.js", "*.min.css", "*.map",
					"*.lock" }) List<String> excludedFiles,
			@DefaultValue("20") int queueCapacity) {

		public Indexing {
			excludedFiles = excludedFiles == null ? List.of() : List.copyOf(excludedFiles);
			if (windowOverlap < 0 || windowOverlap >= windowLines) {
				throw new IllegalArgumentException("rag.indexing.window-overlap must be >= 0 and < window-lines");
			}
			if (maxChunkLines < 10) {
				throw new IllegalArgumentException("rag.indexing.max-chunk-lines must be at least 10");
			}
		}

	}

	/**
	 * @param topK chunks handed to the answer (and returned by hybrid retrieval)
	 * @param candidates candidates taken from each source (keyword, symbol, semantic) before fusion
	 * @param rrfK reciprocal-rank-fusion constant: score = sum over sources of 1 / (rrfK + rank)
	 * @param vectorStore {@code auto} (pgvector when the extension is installed, else in-process),
	 * {@code pgvector} or {@code in-process}
	 * @param maxInProcessVectors largest number of vectors ranked in the application for one query; above it
	 * semantic search reports itself unavailable instead of loading everything
	 * @param maxSearchResults results returned by the search endpoint
	 */
	public record Retrieval(@DefaultValue("8") int topK, @DefaultValue("30") int candidates,
			@DefaultValue("60") int rrfK, @DefaultValue("auto") String vectorStore,
			@DefaultValue("50000") int maxInProcessVectors, @DefaultValue("50") int maxSearchResults) {
	}

	/**
	 * @param maxQuestionChars longest question accepted
	 * @param historyTurns prior question/answer pairs summarised into a follow-up (bounded, never the full history)
	 * @param historyAnswerChars characters kept of each prior answer in that summary
	 * @param maxQuestionsPerSnapshot provider calls for Q&A per snapshot (cache hits are free); separate from
	 * the insight budget per analysis run
	 */
	public record Qa(@DefaultValue("2000") int maxQuestionChars, @DefaultValue("3") int historyTurns,
			@DefaultValue("400") int historyAnswerChars, @DefaultValue("50") int maxQuestionsPerSnapshot) {
	}

}
