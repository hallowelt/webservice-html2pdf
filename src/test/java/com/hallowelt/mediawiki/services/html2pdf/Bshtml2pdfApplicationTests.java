package com.hallowelt.mediawiki.services.html2pdf;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.FileSystemUtils;

@SpringBootTest
class Html2PdfApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private MainController controller;

	@Test
	void contextLoads() {
		assertNotNull(applicationContext, "The application context should have loaded.");
	}

	@Test
	void exposesGeneratedBuildIdentity() {
		var info = controller.index();
		assertNotNull(info.get("version"));
		assertNotNull(info.get("buildId"));
		assertNotNull(info.get("buildTime"));
		assertNotEquals("unversioned", info.get("buildId"), "Run Maven generate-resources to create build metadata.");
		assertNotEquals("unknown", info.get("buildTime"));
	}

	@Test
	void generatedPdfContainsBuildIdentity() throws Exception {
		rendersTextboxWithAccessibility("textbox-block");
	}

	@Test
	void missingDocumentReturnsServerErrorInsteadOfEmptySuccess() {
		MockHttpServletResponse response = new MockHttpServletResponse();
		controller.renderPDF("test-" + UUID.randomUUID(), "missing-document", false, response);
		assertEquals(500, response.getStatus());
		assertEquals("PDF rendering failed.", response.getErrorMessage());
		assertEquals(0, response.getContentAsByteArray().length);
		assertEquals(controller.index().get("buildId"), response.getHeader("X-Html2PDF-Build-Id"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "textbox-table", "textbox-block" })
	@EnabledIfSystemProperty(named = "html2pdf.textbox-diagnostic", matches = "true")
	void rendersTextboxWithAccessibility(String fixture) throws Exception {
		File tempDirectory = (File) ReflectionTestUtils.getField(controller, "tempPathFile");
		assertNotNull(tempDirectory);
		String wikiId = "textbox-diagnostic-" + UUID.randomUUID();
		Path wikiDirectory = tempDirectory.toPath().resolve(wikiId);
		Path documentDirectory = wikiDirectory.resolve(fixture);
		try {
			Files.createDirectories(documentDirectory);
			Files.copy(Path.of("manual-testing", "data", fixture, fixture + ".html"),
					documentDirectory.resolve(fixture + ".html"));
			MockHttpServletResponse response = new MockHttpServletResponse();
			controller.renderPDF(wikiId, fixture, false, response);
			assertEquals(200, response.getStatus(),
					fixture + " failed: " + response.getErrorMessage() + "; inspect the renderer stack trace");
			assertEquals("application/pdf", response.getContentType());
			org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsByteArray().length > 0);
			assertEquals(controller.index().get("buildId"), response.getHeader("X-Html2PDF-Build-Id"));
			try (PDDocument pdf = Loader.loadPDF(response.getContentAsByteArray())) {
				String producer = pdf.getDocumentInformation().getProducer();
				assertTrue(producer.contains("Html2PDF " + controller.index().get("version")));
				assertTrue(producer.contains("build=" + controller.index().get("buildId")));
				assertTrue(producer.contains("built=" + controller.index().get("buildTime")));
			}
		} finally {
			FileSystemUtils.deleteRecursively(wikiDirectory);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "original", "empty-thead", "tbody-header" })
	@EnabledIfSystemProperty(named = "html2pdf.document-diagnostic", matches = ".+")
	void rendersCapturedDocumentWithAccessibility(String variant) throws Exception {
		Path source = Path.of(System.getProperty("html2pdf.document-diagnostic")).toAbsolutePath();
		org.junit.jupiter.api.Assertions.assertTrue(Files.isRegularFile(source), "Missing captured HTML: " + source);
		File tempDirectory = (File) ReflectionTestUtils.getField(controller, "tempPathFile");
		assertNotNull(tempDirectory);
		String wikiId = "document-diagnostic-" + UUID.randomUUID();
		String token = "captured-" + variant;
		Path wikiDirectory = tempDirectory.toPath().resolve(wikiId);
		Path documentDirectory = wikiDirectory.resolve(token);
		try {
			Files.createDirectories(documentDirectory);
			Path document = documentDirectory.resolve(token + ".html");
			if ( "original".equals( variant ) ) {
				Files.copy(source, document);
			} else {
				org.jsoup.nodes.Document parsed = Jsoup.parse(Files.readString(source),
						source.toUri().toString(), Parser.xmlParser());
				if ( "empty-thead".equals( variant ) || "tbody-header".equals( variant ) ) {
					org.jsoup.nodes.Element table = parsed.selectFirst( "table[orig-id=mwFw]" );
					org.jsoup.nodes.Element thead = table.selectFirst( "thead" );
					org.jsoup.nodes.Element tbody = table.selectFirst( "tbody" );
					org.jsoup.nodes.Element headerRow = thead.selectFirst( "tr" );
					headerRow.remove();
					tbody.prependChild( headerRow );
					if ( "tbody-header".equals( variant ) ) {
						thead.remove();
					}
				}
				Files.writeString(document, parsed.outerHtml());
			}
			for (String assetType : new String[] { "stylesheets", "images", "attachments" }) {
				Path assets = source.getParent().resolve(assetType);
				if (Files.isDirectory(assets)) {
					FileSystemUtils.copyRecursively(assets, documentDirectory.resolve(assetType));
				} else {
					System.out.println("Diagnostic: asset directory not supplied: " + assets);
				}
			}
			MockHttpServletResponse response = new MockHttpServletResponse();
			controller.renderPDF(wikiId, token, false, response);
			assertEquals(200, response.getStatus(),
					token + " failed: " + response.getErrorMessage() + "; inspect the renderer stack trace");
			assertEquals("application/pdf", response.getContentType());
			org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsByteArray().length > 0);
			if ( "original".equals( variant ) ) {
				try ( PDDocument pdf = Loader.loadPDF( response.getContentAsByteArray() ) ) {
					PDStructureNode structureRoot = pdf.getDocumentCatalog().getStructureTreeRoot();
					assertNotNull( structureRoot, "PDF/UA structure tree should be present" );
					assertTrue( containsStructureType( structureRoot, "THead" ), "Table header structure should be retained" );
					assertTrue( containsStructureType( structureRoot, "TH" ), "Table header cells should be retained" );
				}
			}
		} finally {
			FileSystemUtils.deleteRecursively(wikiDirectory);
		}
	}

	private static boolean containsStructureType( PDStructureNode node, String structureType ) {
		for ( Object kid : node.getKids() ) {
			if ( kid instanceof PDStructureElement element ) {
				if ( structureType.equals( element.getStructureType() )
						|| containsStructureType( element, structureType ) ) {
					return true;
				}
			}
		}
		return false;
	}

}
