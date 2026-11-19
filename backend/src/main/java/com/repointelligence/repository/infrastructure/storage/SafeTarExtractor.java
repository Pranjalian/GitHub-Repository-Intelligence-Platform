package com.repointelligence.repository.infrastructure.storage;

import com.repointelligence.common.exception.AnalysisLimitException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * Extracts a GitHub tar.gz into a directory, treating the archive as hostile.
 *
 * <ul>
 * <li>Path traversal ({@code ..}) and absolute entry names reject the whole archive; anything that
 * would resolve outside the destination is refused.
 * <li>Only regular files and directories are ever created. Symbolic links, hard links, devices and
 * FIFOs are recorded as skipped and never created, so nothing can point outside the workspace.
 * <li>Entry count, per-file size and total decompressed size are enforced while streaming, so a
 * decompression bomb is cut off long before it fills the disk.
 * <li>Binary files are hashed but not written. Excluded directories are skipped entirely.
 * </ul>
 */
@Component
public class SafeTarExtractor {

	private static final int SNIFF_BYTES = 1024;

	private static final int BUFFER_SIZE = 16 * 1024;

	private static final Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3",
			"COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7",
			"LPT8", "LPT9");

	private final AntPathMatcher matcher = new AntPathMatcher("/");

	public ExtractionResult extract(Path archive, Path destination, ExtractionLimits limits) throws IOException {
		Path root = destination.toAbsolutePath().normalize();
		Files.createDirectories(root);
		Map<String, ExtractedEntry> catalog = new LinkedHashMap<>();
		long totalBytes = 0;
		int entryCount = 0;
		int excluded = 0;
		String archiveRoot = null;

		try (InputStream in = Files.newInputStream(archive);
				GzipCompressorInputStream gzip = new GzipCompressorInputStream(in);
				TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
			TarArchiveEntry entry;
			while ((entry = tar.getNextEntry()) != null) {
				if (entry.isGlobalPaxHeader()) {
					continue;
				}
				if (++entryCount > limits.maxEntries()) {
					throw new AnalysisLimitException("Repository archive has more than " + limits.maxEntries()
							+ " entries (limit derived from repository.ingestion.max-file-count).");
				}
				String name = entry.getName();
				if (name.startsWith("/") || name.startsWith("\\")) {
					throw new UnsafeArchiveException("Archive contains an absolute path and was rejected.");
				}
				String[] parts = name.split("/", -1);
				String top = parts[0];
				if (top.isEmpty() || top.equals("..") || top.equals(".") || top.indexOf('\\') >= 0 || top.indexOf(':') >= 0) {
					throw new UnsafeArchiveException("Archive has an unsafe top-level entry and was rejected.");
				}
				if (archiveRoot == null) {
					archiveRoot = top;
				}
				else if (!archiveRoot.equals(top)) {
					throw new UnsafeArchiveException("Archive does not have a single top-level directory.");
				}
				List<String> segments = relativeSegments(parts);
				if (segments.isEmpty()) {
					// The top-level directory itself.
					continue;
				}
				String relative = String.join("/", segments);

				byte flag = entry.getLinkFlag();
				boolean directory = entry.isDirectory();
				boolean regular = flag == TarConstants.LF_NORMAL || flag == TarConstants.LF_OLDNORM;
				if (directory) {
					continue;
				}
				if (isExcluded(segments, relative, limits)) {
					excluded++;
					long skipped = regular ? entry.getSize() : 0;
					totalBytes = addBytes(totalBytes, skipped, limits);
					drain(tar, skipped);
					continue;
				}
				if (!regular) {
					catalog.put(relative, new ExtractedEntry(relative, 0, null, false, describeNonRegular(entry)));
					continue;
				}
				if (!safeSegments(segments)) {
					totalBytes = addBytes(totalBytes, entry.getSize(), limits);
					drain(tar, entry.getSize());
					catalog.put(relative, new ExtractedEntry(relative, entry.getSize(), null, false,
							"unsafe file name"));
					continue;
				}
				if (!catalog.containsKey(relative) && catalog.size() >= limits.maxFileCount()) {
					throw new AnalysisLimitException("Repository has more than " + limits.maxFileCount()
							+ " files (limit: repository.ingestion.max-file-count).");
				}
				long size = entry.getSize();
				totalBytes = addBytes(totalBytes, size, limits);
				Path target = root.resolve(relative).normalize();
				if (!target.startsWith(root)) {
					throw new UnsafeArchiveException("Archive entry resolves outside the workspace and was rejected.");
				}
				catalog.put(relative, readEntry(tar, entry, relative, target, limits));
			}
		}
		return new ExtractionResult(new ArrayList<>(catalog.values()), totalBytes, excluded);
	}

	/** Returns the entry's path below the top-level directory, rejecting traversal. */
	private static List<String> relativeSegments(String[] parts) {
		List<String> segments = new ArrayList<>();
		for (int i = 1; i < parts.length; i++) {
			String part = parts[i];
			if (part.equals("..")) {
				throw new UnsafeArchiveException("Archive contains a path traversal entry and was rejected.");
			}
			if (part.isEmpty() || part.equals(".")) {
				continue;
			}
			segments.add(part);
		}
		return segments;
	}

	/** File names that could escape or misbehave on a real file system (backslash, drive letters, devices). */
	private static boolean safeSegments(List<String> segments) {
		for (String segment : segments) {
			if (segment.endsWith(".") || segment.endsWith(" ")) {
				return false;
			}
			for (int i = 0; i < segment.length(); i++) {
				char c = segment.charAt(i);
				if (c < 0x20 || "\\:*?\"<>|".indexOf(c) >= 0) {
					return false;
				}
			}
			int dot = segment.indexOf('.');
			String stem = (dot < 0 ? segment : segment.substring(0, dot)).toUpperCase(Locale.ROOT);
			if (WINDOWS_RESERVED.contains(stem)) {
				return false;
			}
		}
		return true;
	}

	private boolean isExcluded(List<String> segments, String relative, ExtractionLimits limits) {
		boolean inExcludedDirectory = false;
		for (int i = 0; i < segments.size() - 1; i++) {
			if (limits.excludedDirectories().contains(segments.get(i))) {
				inExcludedDirectory = true;
				break;
			}
		}
		return inExcludedDirectory && !matchesException(relative, limits.excludedExceptions());
	}

	private boolean matchesException(String relative, List<String> exceptions) {
		for (String pattern : exceptions) {
			if (matcher.match(pattern, relative)) {
				return true;
			}
		}
		return false;
	}

	private static String describeNonRegular(TarArchiveEntry entry) {
		if (entry.isSymbolicLink()) {
			return "symbolic link";
		}
		if (entry.isLink()) {
			return "hard link";
		}
		return "unsupported entry type";
	}

	private static long addBytes(long total, long add, ExtractionLimits limits) {
		long next = total + Math.max(add, 0);
		if (next > limits.maxTotalBytes()) {
			throw new AnalysisLimitException("Repository expands to more than " + (limits.maxTotalBytes() / (1024 * 1024))
					+ " MB (limit: repository.ingestion.max-extracted-size-mb).");
		}
		return next;
	}

	private static void drain(InputStream in, long bytes) throws IOException {
		long remaining = bytes;
		byte[] buffer = new byte[BUFFER_SIZE];
		while (remaining > 0) {
			int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (read < 0) {
				throw new IOException("Archive ended unexpectedly");
			}
			remaining -= read;
		}
	}

	private ExtractedEntry readEntry(TarArchiveInputStream tar, TarArchiveEntry entry, String relative, Path target,
			ExtractionLimits limits) throws IOException {
		long size = entry.getSize();
		MessageDigest digest = sha256();
		byte[] first = tar.readNBytes((int) Math.min(size, SNIFF_BYTES));
		digest.update(first);
		boolean binary = containsNul(first);
		boolean tooLarge = size > limits.maxFileBytes();
		boolean write = !binary && !tooLarge;
		long copied = first.length;

		OutputStream out = null;
		try {
			if (write) {
				Files.createDirectories(target.getParent());
				out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
				out.write(first);
			}
			byte[] buffer = new byte[BUFFER_SIZE];
			while (copied < size) {
				int read = tar.read(buffer, 0, (int) Math.min(buffer.length, size - copied));
				if (read < 0) {
					throw new IOException("Archive ended unexpectedly");
				}
				digest.update(buffer, 0, read);
				if (out != null) {
					out.write(buffer, 0, read);
				}
				copied += read;
			}
		}
		finally {
			if (out != null) {
				out.close();
			}
		}
		String hash = HexFormat.of().formatHex(digest.digest());
		if (tooLarge) {
			return new ExtractedEntry(relative, size, hash, false,
					"exceeds max-file-size-kb (" + (limits.maxFileBytes() / 1024) + " KB)");
		}
		return new ExtractedEntry(relative, size, hash, binary, null);
	}

	private static boolean containsNul(byte[] bytes) {
		for (byte b : bytes) {
			if (b == 0) {
				return true;
			}
		}
		return false;
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
