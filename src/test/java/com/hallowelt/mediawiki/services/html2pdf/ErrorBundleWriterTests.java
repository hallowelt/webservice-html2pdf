package com.hallowelt.mediawiki.services.html2pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorBundleWriterTests {

	@TempDir
	Path tmp;

	private File inputDir() throws Exception {
		Path input = Files.createDirectories(tmp.resolve("input/doc1/styles"));
		Files.writeString(tmp.resolve("input/doc1/doc1.html"), "<html/>");
		Files.writeString(input.resolve("a.css"), "p{}");
		return tmp.resolve("input/doc1").toFile();
	}

	@Test
	void writesInputFilesAndManifest() throws Exception {
		ErrorBundleWriter writer = new ErrorBundleWriter(tmp.resolve("bundles").toFile(), 14, 100);
		File bundle = writer.write("id1", inputDir(), Map.of("wikiId", "w"), List.of(new RuntimeException("boom")));

		assertNotNull(bundle);
		assertEquals("id1.zip", bundle.getName());
		Set<String> names = new HashSet<>();
		try (ZipFile zip = new ZipFile(bundle)) {
			zip.stream().forEach(e -> names.add(e.getName()));
			String manifest = new String(
				zip.getInputStream(zip.getEntry("manifest.txt")).readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(manifest.contains("errorId: id1"));
			assertTrue(manifest.contains("wikiId: w"));
			assertTrue(manifest.contains("boom"));
		}
		assertTrue(names.containsAll(Set.of("manifest.txt", "input/doc1.html", "input/styles/a.css")));
	}

	@Test
	void returnsNullInsteadOfThrowingWhenInputIsMissingOrDisabled() throws Exception {
		assertNull(new ErrorBundleWriter(tmp.resolve("bundles").toFile(), 14, 100)
			.write("id", tmp.resolve("missing").toFile(), Map.of(), List.of()));
		assertNull(new ErrorBundleWriter(null, 14, 100).write("id", inputDir(), Map.of(), List.of()));
	}

	@Test
	void keepsOnlyMaxCountNewestBundles() throws Exception {
		File dir = tmp.resolve("bundles").toFile();
		ErrorBundleWriter writer = new ErrorBundleWriter(dir, 14, 2);
		List<String> ids = List.of("a", "b", "c");
		for (int i = 0; i < ids.size(); i++) {
			File bundle = writer.write(ids.get(i), inputDir(), Map.of(), List.of());
			bundle.setLastModified(System.currentTimeMillis() - (ids.size() - i) * 1000L);
		}
		File[] left = dir.listFiles((d, n) -> n.endsWith(".zip"));
		assertEquals(2, left.length);
		assertTrue(!new File(dir, "a.zip").exists());
	}
}
