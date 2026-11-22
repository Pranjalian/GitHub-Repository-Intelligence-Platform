package com.repointelligence.rag.indexing;

import com.repointelligence.rag.chunking.ChunkDraft;
import com.repointelligence.rag.chunking.ChunkType;
import java.util.UUID;

/** A stored chunk of one snapshot (content already secret-scrubbed). */
public record StoredChunk(UUID id, String filePath, String language, String fileType, String modulePath,
		String className, String methodName, ChunkType chunkType, String section, int startLine, int endLine,
		String content, String contentHash, boolean redacted) {

	public ChunkDraft draft() {
		return new ChunkDraft(filePath, language, fileType, modulePath, className, methodName, chunkType, section,
				startLine, endLine, content);
	}

	/** Short display name: the method, else the type's simple name, else the section. */
	public String label() {
		String type = className == null ? null : className.substring(className.lastIndexOf('.') + 1);
		if (methodName != null) {
			return (type == null ? "" : type + ".") + methodName;
		}
		if (type != null) {
			return type;
		}
		return section;
	}

}
