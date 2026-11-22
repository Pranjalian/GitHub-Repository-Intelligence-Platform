package com.repointelligence.rag.qa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One turn of a conversation: the question (USER) or the answer (ASSISTANT). Content is secret-redacted. */
@Entity
@Table(name = "conversation_message")
public class ConversationMessage {

	public static final String USER = "USER";

	public static final String ASSISTANT = "ASSISTANT";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "conversation_id", nullable = false, updatable = false)
	private UUID conversationId;

	@Column(name = "seq", nullable = false)
	private int seq;

	@Column(name = "role", nullable = false)
	private String role;

	@Column(name = "content", nullable = false, columnDefinition = "text")
	private String content;

	@Column(name = "status")
	private String status;

	@Column(name = "ai_generated", nullable = false)
	private boolean aiGenerated;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "sources")
	private List<Map<String, Object>> sources;

	@Column(name = "provider")
	private String provider;

	@Column(name = "model")
	private String model;

	@Column(name = "prompt_version")
	private String promptVersion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ConversationMessage() {
	}

	public ConversationMessage(UUID conversationId, int seq, String role, String content, Instant at) {
		this.conversationId = conversationId;
		this.seq = seq;
		this.role = role;
		this.content = content;
		this.createdAt = at;
	}

	public void answer(String newStatus, boolean generated, List<Map<String, Object>> newSources, String newProvider,
			String newModel, String newPromptVersion) {
		this.status = newStatus;
		this.aiGenerated = generated;
		this.sources = newSources;
		this.provider = newProvider;
		this.model = newModel;
		this.promptVersion = newPromptVersion;
	}

	public UUID getId() {
		return id;
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public int getSeq() {
		return seq;
	}

	public String getRole() {
		return role;
	}

	public String getContent() {
		return content;
	}

	public String getStatus() {
		return status;
	}

	public boolean isAiGenerated() {
		return aiGenerated;
	}

	public List<Map<String, Object>> getSources() {
		return sources == null ? List.of() : sources;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
