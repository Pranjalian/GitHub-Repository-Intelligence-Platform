package com.repointelligence.rag.chunking;

/**
 * A chunk before it is stored. Lines are 1-based and inclusive and refer to the file at the snapshot's
 * commit. For {@link ChunkType#CLASS} chunks the content leaves out the member bodies that are chunks of
 * their own (marked with a comment line), so the line range spans the whole type.
 *
 * @param section YAML key, Markdown heading, part number or window label; null when not applicable
 */
public record ChunkDraft(String filePath, String language, String fileType, String modulePath, String className,
		String methodName, ChunkType chunkType, String section, int startLine, int endLine, String content) {

	ChunkDraft withSection(String newSection, int newStart, int newEnd, String newContent) {
		return new ChunkDraft(filePath, language, fileType, modulePath, className, methodName, chunkType, newSection,
				newStart, newEnd, newContent);
	}

	/**
	 * The text that is embedded and hashed: a short metadata header (path, kind, names) and the content. The
	 * header lets a vector carry where the code lives, which plain code often does not say.
	 */
	public String indexedText() {
		StringBuilder header = new StringBuilder("File: ").append(filePath);
		if (language != null) {
			header.append(" (").append(language).append(')');
		}
		header.append("\nKind: ").append(chunkType);
		if (className != null) {
			header.append("\nType: ").append(className);
		}
		if (methodName != null) {
			header.append("\nMember: ").append(methodName);
		}
		if (section != null) {
			header.append("\nSection: ").append(section);
		}
		return header.append("\n\n").append(content).toString();
	}

}
