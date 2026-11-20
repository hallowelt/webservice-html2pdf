package com.hallowelt.mediawiki.services.html2pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.FileSystemUtils;

@SpringBootTest
class PdfAccessibilityTests {

	/**
	 * When set (e.g. {@code -Dhtml2pdf.pdf-output-dir=target/validation-pdfs}), every rendered
	 * fixture is written to this directory, so it can be checked with veraPDF (see CI workflow).
	 */
	private static final String OUTPUT_DIR_PROPERTY = "html2pdf.pdf-output-dir";

	@Autowired
	private MainController controller;

	/** Every sample document in manual-testing/data that is checked in (large-doc is generated). */
	@ParameterizedTest
	@ValueSource(strings = { "a11y-attachments", "doc1", "textbox-block", "textbox-table" })
	void rendersFixtureAsTaggedPdfA(String fixture) throws Exception {
		byte[] bytes = renderFixture(fixture);
		try (PDDocument pdf = Loader.loadPDF(bytes)) {
			assertFalse(pdf.getDocumentCatalog().getOutputIntents().isEmpty(),
					"PDF/A requires an OutputIntent (veraPDF 6.2.4.3-2)");
			assertNotNull(pdf.getDocumentCatalog().getStructureTreeRoot(),
					"PDF/UA structure tree should be present (no fallback to untagged rendering)");
		}
	}

	@Test
	void attachmentsAreTaggedForPdfUaAndPdfA() throws Exception {
		try (PDDocument pdf = Loader.loadPDF(renderFixture("a11y-attachments"))) {
			PDStructureTreeRoot root = pdf.getDocumentCatalog().getStructureTreeRoot();
			List<PDObjectReference> misplaced = new ArrayList<>();
			List<PDAnnotation> attachments = new ArrayList<>();
			collectAnnotations(root, null, attachments, misplaced);
			assertEquals(2, attachments.size(), "Both attachment links should be tagged");
			assertTrue(misplaced.isEmpty(), "Non-link annotations must be nested in 'Annot' (veraPDF 7.18.1-1)");

			for (PDAnnotation annotation : attachments) {
				assertNotNull(annotation.getContents(), "Attachment annotations need /Contents");
				var spec = (PDComplexFileSpecification) ((PDAnnotationFileAttachment) annotation).getFile();
				assertNotNull(spec.getCOSObject().getCOSName(COSName.getPDFName("AFRelationship")),
						"Embedded files need an AFRelationship (veraPDF 6.8-3)");
			}
		}
	}

	/**
	 * Renders {@code manual-testing/data/<fixture>/} through the controller, like a client that
	 * uploaded the document and its assets, and returns the PDF.
	 */
	private byte[] renderFixture(String fixture) throws Exception {
		File tempDirectory = (File) ReflectionTestUtils.getField(controller, "tempPathFile");
		assertNotNull(tempDirectory);
		String wikiId = "a11y-" + UUID.randomUUID();
		Path wikiDirectory = tempDirectory.toPath().resolve(wikiId);
		try {
			FileSystemUtils.copyRecursively(Path.of("manual-testing", "data", fixture), wikiDirectory.resolve(fixture));
			Files.deleteIfExists(wikiDirectory.resolve(fixture).resolve(fixture + ".pdf"));
			MockHttpServletResponse response = new MockHttpServletResponse();
			controller.renderPDF(wikiId, fixture, false, response);
			assertEquals(200, response.getStatus(), fixture + ": rendering failed: " + response.getErrorMessage());
			byte[] bytes = response.getContentAsByteArray();

			String outputDir = System.getProperty(OUTPUT_DIR_PROPERTY);
			if (outputDir != null && !outputDir.isBlank()) {
				Path output = Path.of(outputDir);
				Files.createDirectories(output);
				Files.write(output.resolve(fixture + ".pdf"), bytes);
			}
			return bytes;
		} finally {
			FileSystemUtils.deleteRecursively(wikiDirectory);
		}
	}

	@Test
	void movesAttachmentOutOfLinkWithOtherAnnotations() throws Exception {
		try (PDDocument pdf = new PDDocument()) {
			PDPage page = new PDPage();
			pdf.addPage(page);

			PDAnnotationLink link = new PDAnnotationLink();
			link.setStructParent(0);
			PDAnnotationFileAttachment attachment = new PDAnnotationFileAttachment();
			attachment.setStructParent(1);
			page.getAnnotations().add(link);
			page.getAnnotations().add(attachment);

			PDStructureTreeRoot root = new PDStructureTreeRoot();
			pdf.getDocumentCatalog().setStructureTreeRoot(root);
			PDStructureElement document = new PDStructureElement("Document", root);
			root.appendKid(document);
			PDStructureElement linkElement = new PDStructureElement("Link", document);
			linkElement.setPage(page);
			linkElement.setAlternateDescription("Report.pdf");
			document.appendKid(linkElement);
			PDObjectReference linkRef = new PDObjectReference();
			linkRef.setReferencedObject(link);
			PDObjectReference attachmentRef = new PDObjectReference();
			attachmentRef.setReferencedObject(attachment);
			linkElement.appendKid(linkRef);
			linkElement.appendKid(attachmentRef);

			COSDictionary parentTree = new COSDictionary();
			COSArray nums = new COSArray();
			nums.add(COSInteger.get(0));
			nums.add(linkElement.getCOSObject());
			nums.add(COSInteger.get(1));
			nums.add(linkElement.getCOSObject());
			parentTree.setItem(COSName.NUMS, nums);
			root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);

			assertEquals(1, PdfAccessibilityPostProcessor.process(pdf));

			assertEquals("Link", linkElement.getStructureType(), "Element with a link annotation stays a Link");
			PDStructureElement annot = linkElement.getKids().stream()
					.filter(PDStructureElement.class::isInstance).map(PDStructureElement.class::cast)
					.findFirst().orElseThrow();
			assertEquals("Annot", annot.getStructureType());
			assertSame(attachment.getCOSObject(),
					((PDObjectReference) annot.getKids().get(0)).getReferencedObject().getCOSObject());
			assertSame(annot.getCOSObject(), nums.getObject(3), "Parent tree must point to the new Annot element");
			assertSame(linkElement.getCOSObject(), nums.getObject(1));
			assertEquals("Report.pdf", attachment.getContents());

			assertEquals(0, PdfAccessibilityPostProcessor.process(pdf), "Post-processing must be idempotent");
		}
	}

	/**
	 * Collects all file attachment annotations and every non-link annotation reference that
	 * is not nested in an 'Annot' element.
	 */
	private static void collectAnnotations(PDStructureNode node, String type, List<PDAnnotation> attachments,
			List<PDObjectReference> misplaced) throws Exception {
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				collectAnnotations(element, element.getStructureType(), attachments, misplaced);
			} else if (kid instanceof PDObjectReference ref && ref.getReferencedObject() instanceof PDAnnotation a) {
				if (a instanceof PDAnnotationFileAttachment) {
					attachments.add(a);
				}
				if (!(a instanceof PDAnnotationLink) && !"Annot".equals(type)) {
					misplaced.add(ref);
				}
			}
		}
	}
}
