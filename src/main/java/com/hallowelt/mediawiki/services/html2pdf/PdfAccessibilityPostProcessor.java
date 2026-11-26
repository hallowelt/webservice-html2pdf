package com.hallowelt.mediawiki.services.html2pdf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repairs PDF/UA issues in the tagged PDF produced by openhtmltopdf.
 *
 * <p>openhtmltopdf renders {@code <a download="...">} as a {@code FileAttachment}
 * annotation, but tags it inside a {@code Link} structure element. PDF/UA-1
 * (ISO 14289-1, 7.18.1) only allows Link annotations there; every other annotation
 * must be nested in an {@code Annot} structure element (veraPDF rule 7.18.1-1, PAC
 * "Annotation not within an 'Annot' structure element"). See
 * https://github.com/openhtmltopdf/openhtmltopdf/issues/66.
 *
 * <p>For every such annotation this post-processor
 * <ul>
 *   <li>retags the enclosing {@code Link} element as {@code Annot} when it only
 *       references non-link annotations, or otherwise moves the annotation
 *       reference into a new {@code Annot} child element and updates the
 *       structure parent tree accordingly;</li>
 *   <li>sets the annotation's {@code /Contents} from the element's alternate
 *       description (or the attachment file name) if it is missing.</li>
 * </ul>
 */
final class PdfAccessibilityPostProcessor {

	private static final Logger logger = LoggerFactory.getLogger(PdfAccessibilityPostProcessor.class);

	private PdfAccessibilityPostProcessor() {
	}

	/**
	 * Applies the fixes to {@code pdfFile} in place.
	 *
	 * @return the number of annotations that were fixed
	 */
	static int process(File pdfFile) throws IOException {
		int fixed;
		File output = File.createTempFile("a11y-", ".pdf", pdfFile.getParentFile());
		try {
			// Stream data is cached in temp files, so large embedded attachments do not end up on the heap
			try (PDDocument document = Loader.loadPDF(pdfFile, IOUtils.createTempFileOnlyStreamCache())) {
				fixed = process(document);
				if (fixed > 0) {
					document.save(output);
				}
			}
			if (fixed > 0) {
				Files.move(output.toPath(), pdfFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(output.toPath());
		}
		return fixed;
	}

	/**
	 * Applies the fixes to an already loaded document.
	 *
	 * @return the number of annotations that were fixed
	 */
	static int process(PDDocument document) throws IOException {
		PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
		if (root == null) {
			return 0;
		}
		List<PDStructureElement> elements = new ArrayList<>();
		collectElements(root, elements);

		int fixed = 0;
		for (PDStructureElement element : elements) {
			fixed += fixElement(root, element);
		}
		if (fixed > 0) {
			logger.info("PDF/UA post-processing: nested " + fixed + " annotation(s) in 'Annot' structure elements");
		}
		return fixed;
	}

	private static int fixElement(PDStructureTreeRoot root, PDStructureElement element) throws IOException {
		if ("Annot".equals(element.getStructureType())) {
			int fixed = 0;
			for (Object kid : element.getKids()) {
				if (kid instanceof PDObjectReference ref && ref.getReferencedObject() instanceof PDAnnotation annotation) {
					fixed += ensureContents(annotation, element) ? 1 : 0;
				}
			}
			return fixed;
		}

		List<PDObjectReference> misplaced = new ArrayList<>();
		boolean hasOtherKids = false;
		for (Object kid : element.getKids()) {
			if (kid instanceof PDObjectReference ref && requiresAnnotTag(ref.getReferencedObject())) {
				misplaced.add(ref);
			} else {
				hasOtherKids |= kid instanceof PDObjectReference;
			}
		}
		if (misplaced.isEmpty()) {
			return 0;
		}

		if ("Link".equals(element.getStructureType()) && !hasOtherKids) {
			// The element only exists for this annotation (and its visible text): retag it in place.
			element.setStructureType("Annot");
			for (PDObjectReference ref : misplaced) {
				ensureContents((PDAnnotation) ref.getReferencedObject(), element);
			}
			return misplaced.size();
		}

		// Mixed content (e.g. a Link element that also holds a real link annotation):
		// move each misplaced annotation into its own Annot child element.
		for (PDObjectReference ref : misplaced) {
			PDAnnotation annotation = (PDAnnotation) ref.getReferencedObject();
			PDStructureElement annot = new PDStructureElement("Annot", element);
			annot.setPage(element.getPage());
			if (element.getAlternateDescription() != null) {
				annot.setAlternateDescription(element.getAlternateDescription());
			}
			element.removeKid(ref);
			annot.appendKid(ref);
			element.appendKid(annot);
			replaceParentTreeEntry(root, annotation.getStructParent(), annot.getCOSObject());
			ensureContents(annotation, annot);
		}
		return misplaced.size();
	}

	/**
	 * Link annotations belong in a Link element and widgets in a Form element; every other
	 * annotation type must be nested in an Annot element.
	 */
	private static boolean requiresAnnotTag(Object referenced) {
		return referenced instanceof PDAnnotation
			&& !(referenced instanceof PDAnnotationLink)
			&& !(referenced instanceof PDAnnotationWidget);
	}

	private static boolean ensureContents(PDAnnotation annotation, PDStructureElement element) {
		String contents = annotation.getContents();
		if (contents != null && !contents.isBlank()) {
			return false;
		}
		String description = element.getAlternateDescription();
		if ((description == null || description.isBlank())
				&& annotation instanceof PDAnnotationFileAttachment attachment) {
			try {
				if (attachment.getFile() != null) {
					description = attachment.getFile().getFile();
				}
			} catch (IOException e) {
				logger.debug("Could not read file specification of attachment annotation", e);
			}
		}
		if (description == null || description.isBlank()) {
			description = "Attachment";
		}
		annotation.setContents(description);
		return true;
	}

	private static void collectElements(PDStructureNode node, List<PDStructureElement> elements) {
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				elements.add(element);
				collectElements(element, elements);
			}
		}
	}

	/**
	 * Points the parent tree entry for {@code key} (an annotation's /StructParent) at a
	 * new structure element. Works on the raw number tree so nested /Kids are supported.
	 */
	private static void replaceParentTreeEntry(PDStructureTreeRoot root, int key, COSDictionary newParent) {
		if (key < 0) {
			return;
		}
		COSBase tree = root.getCOSObject().getDictionaryObject(COSName.PARENT_TREE);
		if (!(tree instanceof COSDictionary treeDict) || !replaceInNumberTree(treeDict, key, newParent)) {
			logger.warn("PDF/UA post-processing: no parent tree entry for StructParent " + key);
		}
	}

	private static boolean replaceInNumberTree(COSDictionary node, int key, COSDictionary value) {
		COSArray nums = node.getCOSArray(COSName.NUMS);
		if (nums != null) {
			for (int i = 0; i + 1 < nums.size(); i += 2) {
				if (nums.getObject(i) instanceof COSNumber number && number.intValue() == key) {
					nums.set(i + 1, value);
					return true;
				}
			}
		}
		COSArray kids = node.getCOSArray(COSName.KIDS);
		if (kids != null) {
			for (int i = 0; i < kids.size(); i++) {
				COSBase kid = kids.getObject(i);
				if (kid instanceof COSDictionary kidDict && inLimits(kidDict, key)
						&& replaceInNumberTree(kidDict, key, value)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean inLimits(COSDictionary node, int key) {
		COSArray limits = node.getCOSArray(COSName.LIMITS);
		if (limits == null || limits.size() < 2) {
			return true;
		}
		return limits.getObject(0) instanceof COSNumber low && limits.getObject(1) instanceof COSNumber high
			&& key >= low.intValue() && key <= high.intValue();
	}
}
