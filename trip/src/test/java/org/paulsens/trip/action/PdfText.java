package org.paulsens.trip.action;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;

/** Test helpers for the PDF documents: each page's text, and a small real PDF to use as a receipt. */
final class PdfText {

    private PdfText() {
    }

    /** The text of every page, one entry per page. */
    static List<String> pages(final byte[] pdf) throws IOException {
        final List<String> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            final PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(stripper.getText(doc));
            }
        }
        return pages;
    }

    /** A real PDF of {@code count} pages, each saying "{@code text} page n". */
    static byte[] pdf(final int count, final String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= count; i++) {
                final PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                    out.beginText();
                    out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    out.newLineAtOffset(72, 400);
                    out.showText(text + " page " + i);
                    out.endText();
                }
            }
            final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        }
    }
}
