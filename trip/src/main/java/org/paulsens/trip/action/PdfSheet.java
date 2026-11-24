package org.paulsens.trip.action;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.Text;

/**
 * A plain report sheet for the trip's money documents ({@link BudgetPdf}, {@link ExpenseReportPdf}): US Letter,
 * a title on the first page and a short one on each continuation page, section headings, tables whose column
 * headings repeat after a page break, wrapped paragraphs, label/value lines, whole-page images, other PDFs'
 * pages appended, and "Page n of m" footers. {@code RoomInvoicePdf} lays out its own sheet (its tables nest
 * notes under their rows) but draws with this class's measures, greys, fonts and static primitives, so the two
 * cannot drift: its own copy of {@link #show} once skipped {@link #clean}, and one emoji failed an invoice.
 *
 * <p>Liberation Sans is EMBEDDED, so names like Dragi&#263;evi&#263; draw. A character
 * the font has no glyph for prints as "?" rather than failing the whole document, and line breaks in typed text
 * become separate lines. Text arrives already formatted ("$1,440.00"): this class draws, it does not compute.
 *
 * <p>The few words the sheet itself writes (the footer, "Page n of m") come from the {@code pdf} bundle in the
 * READER's language, which the document's own class names: a sheet may be mailed or kept, so the language of
 * the request that happens to draw it is not assumed here. A translated heading or label is often longer than
 * the English one: a number column's heading wraps once shrinking is not enough, and the label column of
 * {@link #fields} widens to its longest label.
 *
 * <p>Use: build, draw, then {@link #finish}, which writes the footers and returns the bytes. One instance per
 * document; not thread-safe.
 */
final class PdfSheet {

    static final float MARGIN = 40f;
    static final PDRectangle SHEET = PDRectangle.LETTER;
    static final float WIDTH = SHEET.getWidth() - 2 * MARGIN;
    static final float BODY = 9.5f;
    static final float SMALL = 8f;

    static final String REGULAR = "/liberation/LiberationSans-Regular.ttf";
    static final String BOLD = "/liberation/LiberationSans-Bold.ttf";
    static final float TOP = SHEET.getHeight() - MARGIN;
    /** Room kept at the bottom for the page footer. */
    static final float BOTTOM = MARGIN + 24f;
    static final float ROW = 15f;
    private static final float LINE = 12f;
    /** The step between the lines of a wrapped table cell. */
    private static final float WRAPPED = 11f;
    static final Color INK = Color.BLACK;
    static final Color MUTED = new Color(0x55, 0x55, 0x55);
    private static final Color NEGATIVE = new Color(0xA3, 0x3A, 0x2E);
    static final Color HEAD_FILL = new Color(0xBB, 0xBB, 0xBB);
    /** The light band behind a total row, and behind an invoice's option heading. */
    static final Color BAND_FILL = new Color(0xF0, 0xF0, 0xF0);
    static final Color RULE = new Color(0xD8, 0xD8, 0xD8);
    /** The label column of {@link #fields}: its usual width, the most it grows to, and the gap it keeps. */
    private static final float LABELS = 150f;
    private static final float WIDEST_LABELS = 250f;
    private static final float LABEL_GAP = 8f;

    /**
     * A table's shape: columns sized in proportion to {@code weights} across the printable width, their
     * headings, and which are numbers (right-aligned).
     */
    record Table(float[] widths, String[] heads, boolean[] right) {

        static Table of(final String[] heads, final float[] weights, final boolean[] right) {
            float sum = 0f;
            for (final float weight : weights) {
                sum += weight;
            }
            final float[] widths = new float[weights.length];
            for (int i = 0; i < weights.length; i++) {
                widths[i] = WIDTH * weights[i] / sum;
            }
            return new Table(widths, heads, right);
        }

        float columnRight(final int column) {
            float right = MARGIN;
            for (int i = 0; i <= column; i++) {
                right += widths[i];
            }
            return right - 4;
        }
    }

    private final PDDocument doc;
    private final PDFont regular;
    private final PDFont bold;
    private final String shortTitle;
    private final Locale locale;
    /** Appended PDFs stay open until the document is saved: their pages are shared, not copied. */
    private final List<PDDocument> sources = new ArrayList<>();
    private PDPageContentStream page;
    private float y;

    /**
     * A new document whose continuation pages and footers are labelled {@code shortTitle}, with the sheet's own
     * words in the reader's {@code locale}.
     */
    PdfSheet(final String shortTitle, final Locale locale) throws IOException {
        this.doc = new PDDocument();
        this.regular = load(doc, REGULAR);
        this.bold = load(doc, BOLD);
        this.shortTitle = (shortTitle == null) ? "" : shortTitle;
        this.locale = (locale == null) ? Locale.ENGLISH : locale;
    }

    /** One of the embedded Liberation Sans faces ({@link #REGULAR}, {@link #BOLD}), loaded into {@code doc}. */
    static PDFont load(final PDDocument doc, final String resource) throws IOException {
        try (InputStream in = PdfSheet.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Font " + resource + " is not on the classpath (openpdf-fonts-extra)");
            }
            return PDType0Font.load(doc, in);
        }
    }

    // ================================================================== drawing

    /** The first page: a large title, then muted lines under it (blank ones skipped). */
    void title(final String title, final List<String> subtitles) throws IOException {
        newPage(false);
        text(bold, 16f, INK, title, MARGIN);
        for (final String line : subtitles) {
            if (line != null && !line.isBlank()) {
                y -= LINE;
                text(regular, BODY, MUTED, line, MARGIN);
            }
        }
        y -= 8;
    }

    /** A section heading, kept on the same page as at least {@code keepWith} points after it. */
    void section(final String heading, final float keepWith) throws IOException {
        ensure(26 + keepWith, null);
        y -= 20;
        text(bold, 12f, INK, heading, MARGIN);
        y -= 6;
    }

    /** {@link #section(String, float)} kept with a table's heading and first row. */
    void section(final String heading) throws IOException {
        section(heading, ROW * 2 + 4);
    }

    /**
     * A table: the headings in a grey band, one row per entry with a hairline under it, and an optional bold
     * total row on a light band. A text cell too long for its column wraps onto more lines (the row grows); a
     * number cell shrinks to fit instead. A row whose LAST cell starts with "-" (a negative amount) draws that
     * cell in red. Headings repeat on a continuation page.
     */
    void table(final Table table, final List<String[]> rows, final String[] total) throws IOException {
        heads(table);
        for (final String[] row : rows) {
            final List<List<String>> lines = layout(table, regular, row, false);
            ensure(heightOf(lines), table);
            draw(table, regular, lines, false);
            rule();
        }
        if (total != null) {
            final List<List<String>> lines = layout(table, bold, total, false);
            ensure(heightOf(lines) + 2, table);
            fill(BAND_FILL, heightOf(lines));
            draw(table, bold, lines, false);
        }
        y -= 4;
    }

    /**
     * Label/value lines, the value wrapped beside the label column. The column is {@link #LABELS} wide unless a
     * label needs more (a translated one often does), up to {@link #WIDEST_LABELS}; a label longer than that
     * shrinks to fit rather than run into its value.
     */
    void fields(final List<String[]> pairs) throws IOException {
        final float labelWidth = labelWidth(pairs);
        for (final String[] pair : pairs) {
            final List<String> lines = wrapAll(regular, BODY, pair[1], WIDTH - labelWidth);
            ensure(LINE * lines.size() + 2, null);
            y -= LINE;
            final String label = clean(bold, pair[0]);
            text(bold, fitted(bold, label, labelWidth - LABEL_GAP), INK, label, MARGIN);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    y -= LINE;
                }
                text(regular, BODY, INK, lines.get(i), MARGIN + labelWidth);
            }
        }
        y -= 4;
    }

    private float labelWidth(final List<String[]> pairs) throws IOException {
        float width = LABELS;
        for (final String[] pair : pairs) {
            width = Math.max(width, widthOf(bold, BODY, clean(bold, pair[0])) + LABEL_GAP);
        }
        return Math.min(width, WIDEST_LABELS);
    }

    /** A wrapped paragraph; {@code muted} draws it small and grey (a note). */
    void paragraph(final String text, final boolean muted) throws IOException {
        final float size = muted ? SMALL : BODY;
        for (final String line : wrapAll(regular, size, text, WIDTH)) {
            ensure(LINE, null);
            y -= muted ? SMALL + 3 : LINE;
            text(regular, size, muted ? MUTED : INK, line, MARGIN);
        }
    }

    /** A label and an amount on one line, right-aligned and large: a document's bottom line. */
    void bigTotal(final String label, final String amount, final boolean negative) throws IOException {
        ensure(26, null);
        y -= 20;
        text(bold, 13f, INK, label, MARGIN);
        textRight(bold, 13f, negative ? NEGATIVE : INK, amount, MARGIN + WIDTH);
        y -= 4;
    }

    /** Vertical space. */
    void gap(final float points) {
        y -= points;
    }

    /** A page of its own for one image: a caption, then the image scaled to fit (never enlarged, never cropped). */
    void imagePage(final String caption, final byte[] imageBytes, final boolean jpeg) throws IOException {
        newPage(true);
        y -= 4;
        text(bold, 11f, INK, caption, MARGIN);
        y -= 10;
        final PDImageXObject image;
        try {
            image = jpeg ? JPEGFactory.createFromByteArray(doc, imageBytes)
                    : LosslessFactory.createFromImage(doc, decode(imageBytes));
        } catch (final IOException | IllegalArgumentException ex) {
            y -= LINE;
            text(regular, BODY, MUTED, Text.forLocale(locale, "pdf.shared.receiptImageNotDrawn"), MARGIN);
            return;
        }
        final float roomHeight = y - BOTTOM;
        final float scale = Math.min(1f, Math.min(WIDTH / image.getWidth(), roomHeight / image.getHeight()));
        final float w = image.getWidth() * scale;
        final float h = image.getHeight() * scale;
        page.drawImage(image, MARGIN, y - h, w, h);
        y -= h;
    }

    private static BufferedImage decode(final byte[] bytes) throws IOException {
        final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Not a readable image");
        }
        return image;
    }

    /**
     * Appends every page of another PDF, writing {@code stamp} across the top of its first page (on a white band,
     * so it reads over the document's own margin); false (nothing appended) when it cannot be read, is
     * encrypted, or has no pages. The source stays open until {@link #finish}, which owns closing it.
     */
    boolean appendPdf(final byte[] pdf, final String stamp) throws IOException {
        final PDDocument source;
        try {
            source = Loader.loadPDF(pdf);
        } catch (final IOException ex) {
            return false;
        }
        sources.add(source);
        if (source.isEncrypted() || source.getNumberOfPages() == 0) {
            return false;
        }
        closePage();
        PDPage first = null;
        for (final PDPage sourcePage : source.getPages()) {
            final PDPage imported = doc.importPage(sourcePage);
            first = (first == null) ? imported : first;
        }
        try (PDPageContentStream top = new PDPageContentStream(doc, first, PDPageContentStream.AppendMode.APPEND,
                true, true)) {
            page = top;
            final PDRectangle box = first.getMediaBox();
            y = box.getUpperRightY() - 18;
            page.setNonStrokingColor(Color.WHITE);
            page.addRect(box.getLowerLeftX(), y - 6, box.getWidth(), 22);
            page.fill();
            text(bold, 10f, INK, stamp, box.getLowerLeftX() + MARGIN);
        }
        page = null;
        return true;
    }

    /** Footers on every page ("{@code shortTitle}, printed {date}" and "Page n of m"), then the bytes. */
    byte[] finish(final LocalDate printed) throws IOException {
        try {
            closePage();
            footers(doc, regular, Text.forLocale(locale, "pdf.shared.printedFooter", shortTitle,
                    Formats.format(printed, "yMMMd", locale)), locale);
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        } finally {
            for (final PDDocument source : sources) {
                source.close();
            }
            doc.close();
        }
    }

    /**
     * The footer of every sheet of a finished document: {@code left} at the left margin and "Page n of m", which
     * only the finished document can know, at the right. Every content stream must be closed first.
     */
    static void footers(final PDDocument doc, final PDFont font, final String left, final Locale locale)
            throws IOException {
        final int pages = doc.getNumberOfPages();
        for (int i = 0; i < pages; i++) {
            try (PDPageContentStream foot = new PDPageContentStream(doc, doc.getPage(i),
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                show(foot, font, SMALL, MUTED, left, MARGIN, MARGIN);
                // Page numbers go in as text: a number argument would be grouped ("1,000") by the format.
                final String number = clean(font, Text.forLocale(locale, "pdf.shared.pageOf", String.valueOf(i + 1),
                        String.valueOf(pages)));
                show(foot, font, SMALL, MUTED, number, MARGIN + WIDTH - widthOf(font, SMALL, number), MARGIN);
            }
        }
    }

    // ================================================================== plumbing

    private void newPage(final boolean continuation) throws IOException {
        closePage();
        final PDPage sheet = new PDPage(SHEET);
        doc.addPage(sheet);
        page = new PDPageContentStream(doc, sheet);
        y = TOP - 14;
        if (continuation) {
            text(bold, 10f, MUTED, shortTitle, MARGIN);
            y -= 16;
        }
    }

    /** Room for {@code needed} points, else a continuation page (repeating {@code table}'s headings if any). */
    private void ensure(final float needed, final Table table) throws IOException {
        if (page != null && y - needed >= BOTTOM) {
            return;
        }
        newPage(true);
        if (table != null) {
            heads(table);
        }
    }

    private void heads(final Table table) throws IOException {
        ensure(ROW * 2 + 2, null);
        final List<List<String>> lines = layout(table, bold, table.heads(), true);
        fill(HEAD_FILL, heightOf(lines) + 2);
        draw(table, bold, lines, true);
        y -= 2;
    }

    /**
     * Each cell's lines: a text cell wrapped to its column, a number cell as one line. A number column's
     * HEADING ({@code heads}) is words, not a number: one too long for its column even at the smallest size (a
     * translated one) wraps instead of running into the column on its left.
     */
    private static List<List<String>> layout(final Table table, final PDFont font, final String[] cells,
            final boolean heads) throws IOException {
        final List<List<String>> lines = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            final String cell = clean(font, cells[i]);
            final float width = table.widths()[i] - 8;
            if (!table.right()[i]) {
                lines.add(wrap(font, BODY, cell, width));
            } else if (heads && widthOf(font, SMALL, cell) > width) {
                lines.add(wrap(font, SMALL, cell, width));
            } else {
                lines.add(List.of(cell));
            }
        }
        return lines;
    }

    /** A row's height: one {@link #ROW}, plus a line for every wrapped line past the first. */
    private static float heightOf(final List<List<String>> lines) {
        int most = 1;
        for (final List<String> cell : lines) {
            most = Math.max(most, cell.size());
        }
        return ROW + (most - 1) * WRAPPED;
    }

    /**
     * One row at the cursor. In the heading row ({@code heads}) a single word wider than its column shrinks
     * (never under {@link #SMALL}) instead of overhanging into the next heading; a body cell is left alone.
     */
    private void draw(final Table table, final PDFont font, final List<List<String>> lines, final boolean heads)
            throws IOException {
        y -= ROW - 4;
        final float top = y;
        float left = MARGIN + 4;
        for (int i = 0; i < lines.size(); i++) {
            final List<String> cell = lines.get(i);
            if (table.right()[i]) {
                final boolean negative = i == lines.size() - 1 && cell.get(0).startsWith("-");
                // More than one line only for a wrapped heading (see layout).
                for (int k = 0; k < cell.size(); k++) {
                    y = top - k * WRAPPED;
                    textRight(font, fitted(font, cell.get(k), table.widths()[i] - 8), negative ? NEGATIVE : INK,
                            cell.get(k), table.columnRight(i));
                }
            } else {
                for (int k = 0; k < cell.size(); k++) {
                    y = top - k * WRAPPED;
                    text(font, heads ? fitted(font, cell.get(k), table.widths()[i] - 8) : BODY, INK, cell.get(k),
                            left);
                }
            }
            left += table.widths()[i];
        }
        y = top - (heightOf(lines) - ROW) - 4;
    }

    /** The body size, or smaller (never under {@link #SMALL}) so a long cell stays inside its column. */
    static float fitted(final PDFont font, final String text, final float width) throws IOException {
        final float natural = widthOf(font, BODY, text);
        return natural <= width ? BODY : Math.max(SMALL, BODY * width / natural);
    }

    private void fill(final Color color, final float height) throws IOException {
        page.setNonStrokingColor(color);
        page.addRect(MARGIN, y - height, WIDTH, height);
        page.fill();
    }

    private void rule() throws IOException {
        page.setStrokingColor(RULE);
        page.setLineWidth(0.5f);
        page.moveTo(MARGIN, y);
        page.lineTo(MARGIN + WIDTH, y);
        page.stroke();
    }

    private void text(final PDFont font, final float size, final Color color, final String text, final float x)
            throws IOException {
        show(page, font, size, color, text, x, y);
    }

    /** One run of text at a point of {@code page}, {@link #clean cleaned} first: THE way text reaches a sheet. */
    static void show(final PDPageContentStream page, final PDFont font, final float size, final Color color,
            final String text, final float x, final float y) throws IOException {
        page.beginText();
        page.setFont(font, size);
        page.setNonStrokingColor(color);
        page.newLineAtOffset(x, y);
        page.showText(clean(font, text));
        page.endText();
    }

    private void textRight(final PDFont font, final float size, final Color color, final String text,
            final float right) throws IOException {
        final String cleaned = clean(font, text);
        text(font, size, color, cleaned, right - widthOf(font, size, cleaned));
    }

    private void closePage() {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        page = null;
    }

    /** Paragraphs (split on line breaks) each wrapped to {@code width}. */
    private List<String> wrapAll(final PDFont font, final float size, final String text, final float width)
            throws IOException {
        final List<String> lines = new ArrayList<>();
        for (final String paragraph : (text == null ? "" : text).split("\\R")) {
            lines.addAll(wrap(font, size, clean(font, paragraph), width));
        }
        return lines;
    }

    /** Words wrapped to {@code width}; a single word longer than the line is left to overhang. */
    static List<String> wrap(final PDFont font, final float size, final String text, final float width)
            throws IOException {
        final List<String> lines = new ArrayList<>();
        final StringBuilder line = new StringBuilder();
        for (final String word : (text == null ? "" : text.trim()).split("\\s+")) {
            final String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && widthOf(font, size, candidate) > width) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        lines.add(line.toString());
        return lines;
    }

    static float widthOf(final PDFont font, final float size, final String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    /**
     * Text the font can draw: control characters (line breaks included) become spaces and a character with no
     * glyph becomes "?", so one stray emoji in a note cannot fail the whole document.
     */
    static String clean(final PDFont font, final String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp)) {
                out.append(' ');
                continue;
            }
            final String ch = new String(Character.toChars(cp));
            out.append(encodable(font, ch) ? ch : "?");
        }
        return out.toString();
    }

    /** {@code "Spring Demo Trip"} as part of a file name: anything a file system would refuse replaced. */
    static String safeTitle(final String tripTitle) {
        final String title = (tripTitle == null || tripTitle.isBlank()) ? "Trip" : tripTitle.trim();
        return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-");
    }

    static String nonNull(final String text) {
        return (text == null) ? "" : text;
    }

    private static boolean encodable(final PDFont font, final String ch) {
        try {
            font.encode(ch);
            return true;
        } catch (final IOException | IllegalArgumentException ex) {
            return false;
        }
    }
}
