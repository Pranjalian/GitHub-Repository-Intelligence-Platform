package com.repointelligence.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

/**
 * Builds tar.gz fixtures shaped like GitHub tarballs (a single top-level {@code owner-repo-sha/}
 * directory), including hostile ones: traversal, absolute paths, links, devices and bombs.
 */
public class TarGzBuilder {

	private final String root;

	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

	private final TarArchiveOutputStream tar;

	private final GZIPOutputStream gzip;

	public TarGzBuilder() {
		this("octocat-hello-0123456");
	}

	public TarGzBuilder(String root) {
		this.root = root;
		try {
			this.gzip = new GZIPOutputStream(bytes);
			this.tar = new TarArchiveOutputStream(gzip);
			tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
			if (root != null) {
				directory(root + "/");
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public TarGzBuilder dir(String path) {
		return directory(root + "/" + path + "/");
	}

	public TarGzBuilder file(String path, String content) {
		return rawFile(root + "/" + path, content.getBytes(StandardCharsets.UTF_8));
	}

	public TarGzBuilder file(String path, byte[] content) {
		return rawFile(root + "/" + path, content);
	}

	/** A file whose full entry name is given verbatim (no top-level directory added). */
	public TarGzBuilder rawFile(String fullName, byte[] content) {
		try {
			TarArchiveEntry entry = new TarArchiveEntry(fullName, true);
			entry.setSize(content.length);
			tar.putArchiveEntry(entry);
			tar.write(content);
			tar.closeArchiveEntry();
			return this;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public TarGzBuilder symlink(String path, String target) {
		return link(path, TarConstants.LF_SYMLINK, target);
	}

	public TarGzBuilder hardlink(String path, String target) {
		return link(path, TarConstants.LF_LINK, target);
	}

	public TarGzBuilder characterDevice(String path) {
		return link(path, TarConstants.LF_CHR, "");
	}

	public TarGzBuilder fifo(String path) {
		return link(path, TarConstants.LF_FIFO, "");
	}

	private TarGzBuilder link(String path, byte flag, String target) {
		try {
			TarArchiveEntry entry = new TarArchiveEntry(root + "/" + path, flag);
			entry.setLinkName(target);
			tar.putArchiveEntry(entry);
			tar.closeArchiveEntry();
			return this;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private TarGzBuilder directory(String fullName) {
		try {
			tar.putArchiveEntry(new TarArchiveEntry(fullName, true));
			tar.closeArchiveEntry();
			return this;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public byte[] build() {
		try {
			tar.finish();
			tar.close();
			return bytes.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public Path writeTo(Path file) {
		try {
			Files.write(file, build());
			return file;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
