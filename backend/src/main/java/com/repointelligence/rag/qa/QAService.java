package com.repointelligence.rag.qa;

import com.repointelligence.ai.application.AIGateway;
import com.repointelligence.ai.application.AIUsageService;
import com.repointelligence.ai.application.GatewayRequest;
import com.repointelligence.ai.application.GatewayResult;
import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.ai.prompt.PromptTemplate;
import com.repointelligence.ai.prompt.PromptTemplateRegistry;
import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIProviderRouter;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.RoutingContext;
import com.repointelligence.ai.provider.RoutingDecision;
import com.repointelligence.common.exception.BadRequestException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.security.SecretRedactor;
import com.repointelligence.rag.RagProperties;
import com.repointelligence.rag.api.IndexStatusDTO;
import com.repointelligence.rag.api.QuestionAnswerDTO;
import com.repointelligence.rag.api.QuestionRequestDTO;
import com.repointelligence.rag.api.SearchResultDTO;
import com.repointelligence.rag.indexing.ChunkStore;
import com.repointelligence.rag.indexing.SnapshotIndex;
import com.repointelligence.rag.indexing.SnapshotIndexRepository;
import com.repointelligence.rag.indexing.SnapshotIndexService;
import com.repointelligence.rag.indexing.StoredChunk;
import com.repointelligence.rag.retrieval.ChunkFilter;
import com.repointelligence.rag.retrieval.QueryTerms;
import com.repointelligence.rag.retrieval.RagScope;
import com.repointelligence.rag.retrieval.RagScopes;
import com.repointelligence.rag.retrieval.Retrieval;
import com.repointelligence.rag.retrieval.RetrievalService;
import com.repointelligence.rag.retrieval.RetrievedChunk;
import com.repointelligence.rag.retrieval.Snippets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repository Q&A over one snapshot: question, hybrid retrieval, a bounded prompt through the {@link AIGateway}
 * (so every MVP 3 guardrail applies: secret scrub, token budget, cache, call budget, schema validation, usage
 * record), then an answer whose citations are checked against the chunks that were actually sent.
 * <ul>
 * <li>Nothing retrieved: an explicit "not enough evidence" answer that says what was searched; no model call.</li>
 * <li>The model says the evidence is insufficient, or cites no retrieved chunk: INSUFFICIENT_EVIDENCE, never a
 * confident-looking answer without sources.</li>
 * <li>No model available: UNAVAILABLE with the reason and deterministic search results for the question's words.</li>
 * <li>Follow-ups: a bounded summary of the last turns ({@code rag.qa.history-turns}), never the full history.</li>
 * </ul>
 */
@Service
public class QAService {

	public static final String FEATURE = "answer-question";

	static final String QUESTION = "QUESTION";

	static final String CONVERSATION = "CONVERSATION";

	static final String CHUNK = "CHUNK";

	static final int SNIPPET_LINES = 12;

	private static final Pattern INLINE_REF = Pattern.compile("\\[(E[0-9]+)]");

	private final RagScopes scopes;

	private final SnapshotIndexRepository indexes;

	private final SnapshotIndexService indexService;

	private final RetrievalService retrieval;

	private final ChunkStore chunks;

	private final PromptTemplateRegistry templates;

	private final AIGateway gateway;

	private final AIProviderRouter router;

	private final AIProperties ai;

	private final AIUsageService usage;

	private final ConversationRepository conversations;

	private final ConversationMessageRepository messages;

	private final RagProperties properties;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public QAService(RagScopes scopes, SnapshotIndexRepository indexes, SnapshotIndexService indexService,
			RetrievalService retrieval, ChunkStore chunks, PromptTemplateRegistry templates, AIGateway gateway,
			AIProviderRouter router, AIProperties ai, AIUsageService usage, ConversationRepository conversations,
			ConversationMessageRepository messages, RagProperties properties, TransactionTemplate transactions,
			Clock clock) {
		this.scopes = scopes;
		this.indexes = indexes;
		this.indexService = indexService;
		this.retrieval = retrieval;
		this.chunks = chunks;
		this.templates = templates;
		this.gateway = gateway;
		this.router = router;
		this.ai = ai;
		this.usage = usage;
		this.conversations = conversations;
		this.messages = messages;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** One earlier question and the answer it got. */
	record Turn(String question, String answer, String status) {
	}

	/** Everything that makes up an answer before it is stored and returned. */
	private record Outcome(String status, boolean aiGenerated, String answer, boolean insufficient, String clarifying,
			List<String> missing, List<QuestionAnswerDTO.SourceDTO> sources, List<QuestionAnswerDTO.SourceDTO> retrieved,
			String searched, List<SearchResultDTO> searchResults, GatewayResult result, String reason,
			List<String> warnings, List<String> limitations) {
	}

	public QuestionAnswerDTO ask(UUID repositoryId, UUID snapshotId, QuestionRequestDTO request) {
		String question = request.question() == null ? "" : request.question().strip();
		if (question.isEmpty()) {
			throw new BadRequestException("question must not be empty.");
		}
		if (question.length() > properties.qa().maxQuestionChars()) {
			throw new BadRequestException("question must be at most " + properties.qa().maxQuestionChars() + " characters.");
		}
		RagScope scope = scopes.resolve(repositoryId, snapshotId);
		UUID conversationId = request.conversationId();
		if (conversationId != null) {
			conversations.findByIdAndRepositoryIdAndSnapshotId(conversationId, repositoryId, snapshotId)
				.orElseThrow(() -> new ResourceNotFoundException("Conversation not found for this snapshot."));
		}
		IndexStatusDTO indexStatus = indexService.status(repositoryId, snapshotId);
		SnapshotIndex index = indexes.findBySnapshotIdAndRepositoryId(snapshotId, repositoryId).orElse(null);
		PromptTemplate template = templates.forFeature(FEATURE);
		if (index == null || !index.getStatus().searchable()) {
			return new QuestionAnswerDTO(conversationId, null, question, "NOT_INDEXED", false, null, false, null, List.of(),
					List.of(), List.of(), null, List.of(), null, null, template.promptVersion(), false, false,
					"Questions are answered from the snapshot's index; index this snapshot first. Symbol search works without it.",
					List.of(), List.of(), scope.commitSha(), null, indexStatus);
		}
		boolean hosted = scope.isPublic() || ai.allowHostedForPrivateRepositories();
		List<Turn> history = conversationId == null ? List.of() : history(conversationId);
		Outcome outcome = answer(scope, index, template, question, history, request.provider(), hosted);
		return store(scope, conversationId, question, template, outcome, indexStatus);
	}

	private Outcome answer(RagScope scope, SnapshotIndex index, PromptTemplate template, String question,
			List<Turn> history, String provider, boolean hosted) {
		RoutingDecision decision = router.route(new RoutingContext(FEATURE, provider, hosted));
		if (!decision.routed()) {
			// no model: the deterministic search still answers "where is ..." for the question's words
			List<SearchResultDTO> results = deterministic(scope, question);
			return new Outcome(AIStatus.UNAVAILABLE.name(), false, null, false, null, List.of(), List.of(), List.of(), null,
					results, null, "Conversational answers are unavailable: " + decision.unavailableReason()
							+ ". Deterministic search results for the question's words are shown instead.",
					List.of(), List.of());
		}
		// a follow-up is searched together with the previous question, so "where is it tested?" still finds "it"
		String retrievalQuery = history.isEmpty() ? question : question + "\n" + history.get(history.size() - 1).question();
		Retrieval found = retrieval.retrieve(scope, index, retrievalQuery, ChunkFilter.NONE, properties.retrieval().topK(),
				hosted);
		if (found.isEmpty()) {
			return new Outcome("INSUFFICIENT_EVIDENCE", false,
					"No evidence for this question was found in " + scope.owner() + "/" + scope.repo() + " at commit "
							+ scope.commitSha().substring(0, 7) + ". Searched: " + found.searched() + ".",
					true, "Could you name a class, method, file or configuration key that the question is about?",
					List.of("No indexed chunk matched the question."), List.of(), List.of(), found.searched(), List.of(), null,
					null, List.of(), List.of());
		}

		List<EvidenceBlock> evidence = new ArrayList<>();
		evidence.add(new EvidenceBlock(null, QUESTION, null, null, null, question, null, false));
		if (!history.isEmpty()) {
			evidence.add(new EvidenceBlock(null, CONVERSATION, null, null, null, summary(history), null, false));
		}
		Map<String, RetrievedChunk> byRef = new LinkedHashMap<>();
		for (RetrievedChunk candidate : found.chunks()) {
			StoredChunk chunk = candidate.chunk();
			evidence.add(new EvidenceBlock(null, CHUNK, chunk.filePath(), chunk.startLine(), chunk.endLine(),
					chunk.content(), scope.url(chunk.filePath(), chunk.startLine(), chunk.endLine()), false));
			// the gateway numbers blocks E1..En in this order
			byRef.put("E" + evidence.size(), candidate);
		}
		UUID snapshotId = scope.snapshotId();
		int limit = properties.qa().maxQuestionsPerSnapshot();
		GatewayRequest.CallBudget budget = new GatewayRequest.CallBudget("qa:" + snapshotId, limit,
				() -> usage.callsForSnapshotFeature(snapshotId, FEATURE),
				"Q&A call budget reached: at most " + limit
						+ " provider calls per snapshot (rag.qa.max-questions-per-snapshot). Cached answers are still served.");
		GatewayResult result = gateway.execute(new GatewayRequest(template, evidence, scope.repositoryId(), snapshotId,
				scope.runId(), scope.commitSha(), provider, hosted, budget));

		Set<String> sent = new LinkedHashSet<>();
		result.evidence().forEach(block -> sent.add(block.id()));
		List<QuestionAnswerDTO.SourceDTO> retrieved = new ArrayList<>();
		byRef.forEach((ref, candidate) -> {
			if (sent.contains(ref) || result.evidence().isEmpty()) {
				retrieved.add(source(scope, ref, candidate, question));
			}
		});
		List<String> limitations = new ArrayList<>();
		int notSent = (int) byRef.keySet().stream().filter(ref -> !sent.contains(ref)).count();
		if (result.status() == AIStatus.OK && notSent > 0) {
			limitations.add(notSent + " retrieved chunk(s) did not fit the input token budget and were not sent.");
		}
		if (result.withheldLines() > 0) {
			limitations.add(result.withheldLines() + " line(s) were withheld because a possible secret was detected.");
		}
		if (!found.semantic()) {
			limitations.add("Semantic search was not used (" + found.semanticReason() + "); chunks were found by keyword and symbol search.");
		}
		if (result.status() != AIStatus.OK) {
			return new Outcome(result.status().name(), false, null, false, null, List.of(), List.of(), retrieved,
					found.searched(), List.of(), result, result.detail(), result.warnings(), limitations);
		}

		Map<?, ?> structured = result.structured() instanceof Map<?, ?> map ? map : Map.of();
		List<String> warnings = new ArrayList<>(result.warnings());
		Citations citations = citations(structured, byRef.keySet(), sent, warnings);
		boolean insufficient = Boolean.TRUE.equals(structured.get("insufficientEvidence"));
		String answer = citations.answer();
		boolean aiGenerated = true;
		if (!insufficient && citations.refs().isEmpty()) {
			// a repository-specific answer without a single valid source is not shown as if it were grounded
			insufficient = true;
			aiGenerated = false;
			answer = "The model answered without citing any of the retrieved sources, so its answer is not shown as a "
					+ "grounded answer. Searched: " + found.searched() + ".";
			warnings.add("The answer cited no retrieved chunk and was withheld.");
		}
		List<QuestionAnswerDTO.SourceDTO> sources = citations.refs()
			.stream()
			.map(ref -> source(scope, ref, byRef.get(ref), question))
			.toList();
		String clarifying = structured.get("clarifyingQuestion") instanceof String text && !text.isBlank() ? text : null;
		List<String> missing = new ArrayList<>();
		if (structured.get("missingEvidence") instanceof List<?> list) {
			list.forEach(item -> missing.add(String.valueOf(item)));
		}
		return new Outcome(insufficient ? "INSUFFICIENT_EVIDENCE" : AIStatus.OK.name(), aiGenerated, answer, insufficient,
				clarifying, missing, sources, retrieved, found.searched(), List.of(), result, null, warnings, limitations);
	}

	/** Valid chunk citations in order of appearance, and the answer with invalid inline citations marked. */
	record Citations(String answer, List<String> refs) {
	}

	/**
	 * Keeps only citations of CHUNK blocks that were sent: evidenceRefs naming no block were already dropped by the
	 * gateway; here inline [E<n>] markers naming no sent chunk are replaced by [?], and citations of the question
	 * or conversation blocks are not sources. Each removal is reported as a warning.
	 */
	static Citations citations(Map<?, ?> structured, Set<String> chunkRefs, Set<String> sent, List<String> warnings) {
		String answer = structured.get("answer") instanceof String text ? text : "";
		Set<String> refs = new LinkedHashSet<>();
		Set<String> rejected = new LinkedHashSet<>();
		Matcher matcher = INLINE_REF.matcher(answer);
		StringBuilder cleaned = new StringBuilder();
		while (matcher.find()) {
			String ref = matcher.group(1);
			if (chunkRefs.contains(ref) && sent.contains(ref)) {
				refs.add(ref);
				matcher.appendReplacement(cleaned, Matcher.quoteReplacement(matcher.group()));
			}
			else {
				rejected.add(ref);
				matcher.appendReplacement(cleaned, "[?]");
			}
		}
		matcher.appendTail(cleaned);
		if (structured.get("evidenceRefs") instanceof List<?> list) {
			for (Object item : list) {
				String ref = String.valueOf(item);
				if (chunkRefs.contains(ref) && sent.contains(ref)) {
					refs.add(ref);
				}
				else {
					rejected.add(ref);
				}
			}
		}
		for (String ref : rejected) {
			warnings.add("The answer cited " + ref + ", which is not a retrieved source that was sent; the citation was "
					+ "removed.");
		}
		return new Citations(cleaned.toString(), List.copyOf(refs));
	}

	private QuestionAnswerDTO.SourceDTO source(RagScope scope, String ref, RetrievedChunk candidate, String question) {
		StoredChunk chunk = candidate.chunk();
		List<String> words = QueryTerms.of(question).highlightWords();
		String snippet = words.isEmpty() ? head(chunk.content()) : Snippets.around(chunk.content(), words);
		return new QuestionAnswerDTO.SourceDTO(ref, chunk.id(), chunk.filePath(), chunk.startLine(), chunk.endLine(),
				chunk.chunkType().name(), chunk.label(), snippet,
				scope.url(chunk.filePath(), chunk.startLine(), chunk.endLine()),
				Math.round(candidate.score() * 10000) / 10000.0);
	}

	private static String head(String content) {
		String[] lines = content.split("\n", -1);
		return String.join("\n", java.util.Arrays.asList(lines).subList(0, Math.min(lines.length, SNIPPET_LINES)));
	}

	/** Keyword hits for the question's meaningful words, best first (no model involved). */
	private List<SearchResultDTO> deterministic(RagScope scope, String question) {
		QueryTerms terms = QueryTerms.of(question);
		String any = terms.anyOf();
		if (any == null) {
			return List.of();
		}
		List<ChunkStore.Hit> hits = chunks.keyword(scope.snapshotId(), scope.repositoryId(), any, ChunkFilter.NONE,
				properties.retrieval().topK());
		Map<UUID, StoredChunk> byId = chunks.byIds(scope.snapshotId(), scope.repositoryId(),
				hits.stream().map(ChunkStore.Hit::id).toList());
		List<SearchResultDTO> results = new ArrayList<>();
		for (ChunkStore.Hit hit : hits) {
			StoredChunk chunk = byId.get(hit.id());
			if (chunk != null) {
				results.add(new SearchResultDTO("CHUNK", chunk.id(), null, null, chunk.label(), chunk.className(), null,
						chunk.filePath(), chunk.startLine(), chunk.endLine(), chunk.language(), chunk.modulePath(),
						chunk.chunkType().name(), chunk.section(), Math.round(hit.score() * 10000) / 10000.0,
						Snippets.around(chunk.content(), terms.highlightWords()),
						scope.url(chunk.filePath(), chunk.startLine(), chunk.endLine()), chunk.redacted()));
			}
		}
		return results;
	}

	/** The last {@code rag.qa.history-turns} question/answer pairs, oldest first. */
	List<Turn> history(UUID conversationId) {
		int turns = properties.qa().historyTurns();
		if (turns <= 0) {
			return List.of();
		}
		List<ConversationMessage> recent = new ArrayList<>(
				messages.findByConversationIdOrderBySeqDesc(conversationId, PageRequest.of(0, turns * 2)));
		Collections.reverse(recent);
		List<Turn> result = new ArrayList<>();
		String pendingQuestion = null;
		for (ConversationMessage message : recent) {
			if (ConversationMessage.USER.equals(message.getRole())) {
				pendingQuestion = message.getContent();
			}
			else if (pendingQuestion != null) {
				result.add(new Turn(pendingQuestion, message.getContent(), message.getStatus()));
				pendingQuestion = null;
			}
		}
		return result;
	}

	/** A bounded summary of earlier turns: each question and answer cut to a fixed length. */
	String summary(List<Turn> history) {
		int answerChars = properties.qa().historyAnswerChars();
		StringBuilder out = new StringBuilder("Earlier in this conversation (oldest first):");
		for (Turn turn : history) {
			out.append("\nQ: ").append(cut(turn.question(), 300));
			String answer = turn.answer() == null || turn.answer().isBlank() ? "(no answer: " + turn.status() + ")"
					: cut(turn.answer(), answerChars);
			out.append("\nA: ").append(answer);
		}
		return out.toString();
	}

	private static String cut(String text, int max) {
		String flat = text.replaceAll("\\s+", " ").strip();
		return flat.length() <= max ? flat : flat.substring(0, max) + "...";
	}

	private QuestionAnswerDTO store(RagScope scope, UUID conversationId, String question, PromptTemplate template,
			Outcome outcome, IndexStatusDTO indexStatus) {
		Instant now = clock.instant();
		String redactedQuestion = SecretRedactor.redact(question);
		UUID[] ids = transactions.execute(tx -> {
			Conversation conversation = conversationId == null
					? conversations.save(new Conversation(scope.repositoryId(), scope.snapshotId(), redactedQuestion, now))
					: conversations.findById(conversationId).orElseThrow();
			conversation.touch(now);
			int seq = messages.findByConversationIdOrderBySeqDesc(conversation.getId(), PageRequest.of(0, 1))
				.stream()
				.findFirst()
				.map(ConversationMessage::getSeq)
				.orElse(0);
			messages.save(new ConversationMessage(conversation.getId(), seq + 1, ConversationMessage.USER, redactedQuestion, now));
			String content = outcome.answer() != null ? outcome.answer() : outcome.reason() == null ? "" : outcome.reason();
			ConversationMessage answer = new ConversationMessage(conversation.getId(), seq + 2, ConversationMessage.ASSISTANT,
					SecretRedactor.redact(content), now);
			List<Map<String, Object>> cited = new ArrayList<>();
			for (QuestionAnswerDTO.SourceDTO source : outcome.sources()) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("ref", source.ref());
				entry.put("filePath", source.filePath());
				entry.put("startLine", source.startLine());
				entry.put("endLine", source.endLine());
				cited.add(entry);
			}
			GatewayResult result = outcome.result();
			answer.answer(outcome.status(), outcome.aiGenerated(), cited.isEmpty() ? null : cited,
					result == null ? null : result.provider(), result == null ? null : result.model(),
					template.promptVersion());
			messages.save(answer);
			return new UUID[] { conversation.getId(), answer.getId() };
		});
		GatewayResult result = outcome.result();
		return new QuestionAnswerDTO(ids[0], ids[1], question, outcome.status(), outcome.aiGenerated(), outcome.answer(),
				outcome.insufficient(), outcome.clarifying(), outcome.missing(), outcome.sources(), outcome.retrieved(),
				outcome.searched(), outcome.searchResults(), result == null ? null : result.provider(),
				result == null ? null : result.model(), template.promptVersion(), result != null && result.fromCache(),
				result != null && result.fallback(), outcome.reason(), outcome.warnings(), outcome.limitations(),
				scope.commitSha(), result == null ? null : result.generatedAt(), indexStatus);
	}

}
