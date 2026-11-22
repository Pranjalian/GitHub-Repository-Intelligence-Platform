package com.repointelligence.rag.chunking;

import com.repointelligence.rag.RagProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Splits one file into meaningful chunks: Java by type and method (syntax tree), YAML and properties by
 * top-level section, Markdown by heading, everything else into overlapping line windows labelled
 * {@link ChunkType#LINE_WINDOW}. Anything longer than {@code rag.indexing.max-chunk-lines} is split into
 * numbered parts. Deterministic: the same text always gives the same chunks in the same order. The input
 * is already secret-scrubbed, and nothing in a file is ever executed or evaluated.
 */
@Component
public class CodeChunker {

	private final int maxLines;

	private final int windowLines;

	private final int windowOverlap;

	@org.springframework.beans.factory.annotation.Autowired
	public CodeChunker(RagProperties properties) {
		this(properties.indexing().maxChunkLines(), properties.indexing().windowLines(),
				properties.indexing().windowOverlap());
	}

	CodeChunker(int maxLines, int windowLines, int windowOverlap) {
		this.maxLines = maxLines;
		this.windowLines = windowLines;
		this.windowOverlap = windowOverlap;
	}

	/** The chunks of a file, in line order; empty for an empty or all-blank file. */
	public List<ChunkDraft> chunk(SourceFile file) {
		String[] lines = LineWindows.lines(file.text());
		int count = LineWindows.count(lines);
		if (count == 0 || LineWindows.blank(lines, 1, count)) {
			return List.of();
		}
		String name = file.path().substring(file.path().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
		List<ChunkDraft> result = new ArrayList<>();
		if (name.endsWith(".java")) {
			List<JavaChunker.Unit> units = JavaChunker.chunk(file.text());
			if (units != null) {
				for (JavaChunker.Unit unit : units) {
					add(result, file, unit.type(), unit.className(), unit.methodName(), unit.section(), unit.piece());
				}
				return result;
			}
			return windows(file, lines, count, "line window (the file did not parse as Java)");
		}
		List<TextChunkers.Section> sections = null;
		ChunkType type = null;
		if (name.endsWith(".yml") || name.endsWith(".yaml")) {
			sections = TextChunkers.yaml(lines, count);
			type = ChunkType.CONFIG_SECTION;
		}
		else if (name.endsWith(".properties")) {
			sections = TextChunkers.properties(lines, count);
			type = ChunkType.CONFIG_SECTION;
		}
		else if (name.endsWith(".md") || name.endsWith(".markdown")) {
			sections = TextChunkers.markdown(lines, count);
			type = ChunkType.DOC_SECTION;
		}
		if (sections == null || sections.isEmpty()) {
			return windows(file, lines, count, "line window");
		}
		for (TextChunkers.Section section : sections) {
			add(result, file, type, null, null, section.label(), Piece.ofLines(lines, section.start(), section.end()));
		}
		return result;
	}

	private List<ChunkDraft> windows(SourceFile file, String[] lines, int count, String label) {
		List<ChunkDraft> result = new ArrayList<>();
		for (int[] window : LineWindows.windows(count, windowLines, windowOverlap)) {
			if (LineWindows.blank(lines, window[0], window[1])) {
				continue;
			}
			result.add(new ChunkDraft(file.path(), file.language(), file.fileType(), file.modulePath(), null, null,
					ChunkType.LINE_WINDOW, label + ", lines " + window[0] + "-" + window[1], window[0], window[1],
					LineWindows.join(lines, window[0], window[1])));
		}
		return result;
	}

	private void add(List<ChunkDraft> result, SourceFile file, ChunkType type, String className, String methodName,
			String section, Piece piece) {
		if (piece.size() == 0 || piece.isBlank()) {
			return;
		}
		List<Piece> parts = piece.size() > maxLines ? piece.split(maxLines) : List.of(piece);
		for (int i = 0; i < parts.size(); i++) {
			Piece part = parts.get(i);
			if (part.isBlank()) {
				continue;
			}
			String label = parts.size() == 1 ? section
					: (section == null ? "" : section + ", ") + "part " + (i + 1) + " of " + parts.size();
			result.add(new ChunkDraft(file.path(), file.language(), file.fileType(), file.modulePath(), className,
					methodName, type, label, part.firstLine(), part.lastLine(), part.content()));
		}
	}

}
