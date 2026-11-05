package com.hallowelt.mediawiki.services.html2pdf;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores the complete input of a failed (or degraded) render as
 * {@code <errorId>.zip}, so that the problem can be reproduced later.
 */
public class ErrorBundleWriter {

	private static final Logger logger = LoggerFactory.getLogger(ErrorBundleWriter.class);

	private static final DateTimeFormatter ID_TIME = DateTimeFormatter
		.ofPattern("yyyyMMdd-HHmmss")
		.withZone(ZoneOffset.UTC);

	private final File bundleDir;
	private final int retentionDays;
	private final int maxBundles;

	public ErrorBundleWriter(File bundleDir, int retentionDays, int maxBundles) {
		this.bundleDir = bundleDir;
		this.retentionDays = retentionDays;
		this.maxBundles = maxBundles;
	}

	/**
	 * Settings (system property / environment variable):
	 * html2pdf.bundle.dir / HTML2PDF_BUNDLE_DIR (default: tmp/html2pdf-bundles, empty = disabled),
	 * html2pdf.bundle.retention-days / HTML2PDF_BUNDLE_RETENTION_DAYS (default: 14),
	 * html2pdf.bundle.max-count / HTML2PDF_BUNDLE_MAX_COUNT (default: 100)
	 */
	public static ErrorBundleWriter fromEnvironment() {
		String dir = setting("html2pdf.bundle.dir", "HTML2PDF_BUNDLE_DIR");
		if (dir == null) {
			dir = System.getProperty("java.io.tmpdir") + "/html2pdf-bundles";
		}
		return new ErrorBundleWriter(
			dir.isBlank() ? null : new File(dir),
			intSetting("html2pdf.bundle.retention-days", "HTML2PDF_BUNDLE_RETENTION_DAYS", 14),
			intSetting("html2pdf.bundle.max-count", "HTML2PDF_BUNDLE_MAX_COUNT", 100));
	}

	private static String setting(String property, String env) {
		String value = System.getProperty(property);
		return value != null ? value : System.getenv(env);
	}

	private static int intSetting(String property, String env, int defaultValue) {
		String value = setting(property, env);
		try {
			return value == null ? defaultValue : Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			logger.warn("Invalid value for " + property + ": " + value);
			return defaultValue;
		}
	}

	public static String newErrorId() {
		return ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * Never throws: a failed capture must not mask the original error.
	 *
	 * @return the bundle file, or null if nothing was written
	 */
	public File write(String errorId, File inputDir, Map<String, String> info, List<Throwable> errors) {
		if (bundleDir == null || inputDir == null || !inputDir.isDirectory()) {
			return null;
		}
		Path tmp = null;
		try {
			Files.createDirectories(bundleDir.toPath());
			tmp = Files.createTempFile(bundleDir.toPath(), "bundle-", ".tmp");
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
				addEntry(zip, "manifest.txt", manifest(errorId, info, errors).getBytes(StandardCharsets.UTF_8));
				Path root = inputDir.toPath();
				try (Stream<Path> files = Files.walk(root)) {
					for (Path file : (Iterable<Path>) files::iterator) {
						if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
							String name = "input/" + root.relativize(file).toString().replace(File.separatorChar, '/');
							zip.putNextEntry(new ZipEntry(name));
							Files.copy(file, zip);
							zip.closeEntry();
						}
					}
				}
			}
			File target = new File(bundleDir, errorId + ".zip");
			Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			cleanup();
			return target;
		} catch (Exception e) {
			logger.error("Could not write error bundle " + errorId, e);
			if (tmp != null) {
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
					// best effort
				}
			}
			return null;
		}
	}

	private static void addEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content);
		zip.closeEntry();
	}

	private static String manifest(String errorId, Map<String, String> info, List<Throwable> errors) {
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("errorId", errorId);
		entries.put("timestamp", Instant.now().toString());
		entries.putAll(info);
		StringBuilder sb = new StringBuilder();
		entries.forEach((key, value) -> sb.append(key).append(": ").append(value).append('\n'));
		int i = 1;
		for (Throwable error : errors) {
			StringWriter trace = new StringWriter();
			error.printStackTrace(new PrintWriter(trace));
			sb.append("\n--- error ").append(i++).append(" ---\n").append(trace);
		}
		return sb.toString();
	}

	private void cleanup() {
		File[] bundles = bundleDir.listFiles((dir, name) -> name.endsWith(".zip"));
		if (bundles == null) {
			return;
		}
		Instant limit = Instant.now().minus(Duration.ofDays(retentionDays));
		java.util.Arrays.sort(bundles, Comparator.comparingLong(File::lastModified));
		int remaining = bundles.length;
		for (File bundle : bundles) {
			boolean expired = Instant.ofEpochMilli(bundle.lastModified()).isBefore(limit);
			if (expired || remaining > maxBundles) {
				if (bundle.delete()) {
					remaining--;
				}
			}
		}
	}
}
