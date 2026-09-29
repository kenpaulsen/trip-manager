package org.paulsens.trip.action;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The Room Invoice as a PDF: the same {@link ReportCommands.RoomInvoice} the page renders, EACH ACCOMMODATION
 * STARTING A PAGE OF ITS OWN, so a hotel's part can be printed or forwarded to that hotel alone. A hotel whose
 * lines outrun one sheet continues on the next with its name and the column headings repeated; the trip's
 * grand total closes the last page, and every page carries "Page n of m".
 *
 * <p>Liberation Sans is EMBEDDED (subset) rather than using a PDF standard font: those encode WinAnsi only and
 * cannot draw the letters in a name like Dragi&#263;evi&#263;, and a font found on the server would differ
 * between a laptop and the container. The files come from OpenPDF's font jar (see the pom).
 *
 * <p>Pure: an invoice and a title in, bytes out. {@code RoomInvoicePdfTest} reads the text back out.
 */
public final class RoomInvoicePdf {

    private static final String REGULAR = "/liberation/LiberationSans-Regular.ttf";
    private static final String BOLD = "/liberation/LiberationSans-Bold.ttf";
    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ROOT);

    private static final PDRectangle SHEET = PDRectangle.LETTER;
    private static final float MARGIN = 40f;
    private static final float WIDTH = SHEET.getWidth() - 2 * MARGIN;
    private static final float TOP = SHEET.getHeight() - MARGIN;
    /** Room kept at the bottom for the page footer. */
    private static final float BOTTOM = MARGIN + 24f;
    private static final float BODY = 9.5f;
    private static final float SMALL = 8f;
    private static final float ROW = 15f;
    private static final float SUB_ROW = 11f;

    /** The per-option table: Item, Dates, Quantity, Nights, Rate, Amount; numbers right-aligned. */
    private static final Table OPTIONS = new Table(new float[] {140f, 132f, 80f, 45f, 60f, 75f},
            new String[] {"Item", "Dates", "Quantity", "Nights", "Rate", "Amount"},
            new boolean[] {false, false, true, true, true, true});
    /** The per-person table: Person, Dates, Nights, Rate, Amount. Rate is prose here, so left-aligned. */
    private static final Table PEOPLE = new Table(new float[] {135f, 122f, 40f, 160f, 75f},
            new String[] {"Person", "Dates", "Nights", "Rate", "Amount"},
            new boolean[] {false, false, true, false, true});
    /** The per-option Quantity column, under which a per-room line's guest note sits. */
    private static final int QUANTITY = 2;

    private static final Color INK = Color.BLACK;
    private static final Color MUTED = new Color(0x55, 0x55, 0x55);
    private static final Color HEAD_FILL = new Color(0xBB, 0xBB, 0xBB);
    private static final Color GROUP_FILL = new Color(0xF0, 0xF0, 0xF0);
    private static final Color RULE = new Color(0xD8, 0xD8, 0xD8);

    private RoomInvoicePdf() {
    }

    /** The whole document, printed today. */
    public static byte[] render(final String tripTitle, final ReportCommands.RoomInvoice invoice) {
        return render(tripTitle, invoice, LocalDate.now());
    }

    /** The whole document; {@code printed} is named in each page's footer. */
    static byte[] render(final String tripTitle, final ReportCommands.RoomInvoice invoice,
            final LocalDate printed) {
        try (PDDocument doc = new PDDocument()) {
            final Writer out = new Writer(doc, load(doc, REGULAR), load(doc, BOLD), tripTitle);
            if (invoice.getSections().isEmpty()) {
                out.newPage(null);
                out.text(out.regular, BODY, INK, "No rooms are reserved on this trip yet, so there is nothing "
                        + "to invoice.", MARGIN);
            }
            final List<ReportCommands.InvoiceSection> sections = invoice.getSections();
            for (int i = 0; i < sections.size(); i++) {
                out.section(sections.get(i));
                if (i == sections.size() - 1) {
                    out.closing(invoice);
                }
            }
            out.footers(PRINTED.format(printed));
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        } catch (final IOException ex) {
            throw new UncheckedIOException("Could not draw the Room Invoice PDF", ex);
        }
    }

    /** {@code "Spring Demo Trip - Room Invoice.pdf"}, with anything a file system would refuse replaced. */
    public static String fileName(final String tripTitle) {
        final String title = (tripTitle == null || tripTitle.isBlank()) ? "Trip" : tripTitle.trim();
        return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-") + " - Room Invoice.pdf";
    }

    private static PDFont load(final PDDocument doc, final String resource) throws IOException {
        try (InputStream in = RoomInvoicePdf.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Font " + resource + " is not on the classpath (openpdf-fonts-extra)");
            }
            return PDType0Font.load(doc, in);
        }
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

    private static float widthOf(final PDFont font, final float size, final String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    /** A table's shape: column widths (summing to the printable width), headings, and which are numbers. */
    private record Table(float[] widths, String[] heads, boolean[] right) {

        /** A cell's width plus any EMPTY cells after it, which a label such as "subtotal" may run into. */
        private float room(final String[] cells, final int column) {
            float room = widths[column];
            for (int i = column + 1; i < cells.length && (cells[i] == null || cells[i].isEmpty()); i++) {
                room += widths[i];
            }
            return room;
        }

        private float columnRight(final int column) {
            float right = MARGIN;
            for (int i = 0; i <= column; i++) {
                right += widths[i];
            }
            return right - 4;
        }
    }

    /** The drawing cursor: the current page, its content stream, and how far down it we are. */
    private static final class Writer {
        private final PDDocument doc;
        private final PDFont regular;
        private final PDFont bold;
        private final String tripTitle;
        private PDPageContentStream page;
        private float y;
        /** The table being drawn, whose headings a continuation sheet repeats. */
        private Table table = OPTIONS;

        private Writer(final PDDocument doc, final PDFont regular, final PDFont bold, final String tripTitle) {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
            this.tripTitle = (tripTitle == null) ? "" : tripTitle;
        }

        /** One accommodation, from a fresh page: every hotel starts its own sheet. */
        private void section(final ReportCommands.InvoiceSection section) throws IOException {
            newPage(section.getAccommodation());
            table = OPTIONS;
            columnHeads();
            for (final ReportCommands.InvoiceOffer group : section.getOffers()) {
                group(section, group);
            }
            ensure(ROW + 4, section.getAccommodation(), true);
            fill(GROUP_FILL, ROW);
            row(bold, new String[] {section.getAccommodation() + " subtotal", "", "", "", "", section.getAmount()});
            people(section);
        }

        /**
         * The same money per person, on the same sheet when it fits (no new page of its own): a heading, one
         * row per occupant per stay, and a total equal to the subtotal above.
         */
        private void people(final ReportCommands.InvoiceSection section) throws IOException {
            table = PEOPLE;
            ensure(22 + ROW * 2 + SUB_ROW, section.getAccommodation(), false);
            y -= 22;
            text(bold, 11f, INK, "Charges per person", MARGIN);
            y -= 6;
            columnHeads();
            for (final ReportCommands.InvoicePersonLine who : section.getPeople()) {
                final boolean named = section.isSeveralOptions();
                ensure(ROW + (named ? SUB_ROW : 0), section.getAccommodation(), true);
                row(regular, new String[] {who.getName(), who.getDates(), String.valueOf(who.getNights()),
                    who.getRate(), who.getAmount()});
                if (named) {
                    y -= SUB_ROW - 2;
                    text(regular, SMALL, MUTED, who.getOption(), MARGIN + 4);
                    y -= 2;
                }
                rule();
            }
            ensure(ROW + 4, section.getAccommodation(), true);
            fill(GROUP_FILL, ROW);
            row(bold, new String[] {"Total", "", "", "", section.getPeopleAmount()});
        }

        /** An option's heading (name, pricing, room mix) kept on the same sheet as at least its first line. */
        private void group(final ReportCommands.InvoiceSection section, final ReportCommands.InvoiceOffer group)
                throws IOException {
            final List<String> names = wrap(bold, BODY, group.getName(), WIDTH - 8);
            final List<String> notes = new ArrayList<>(wrap(regular, SMALL, group.getPricing(), WIDTH - 8));
            if (group.getRoomMix() != null && !group.getRoomMix().isEmpty()) {
                notes.addAll(wrap(regular, SMALL, group.getRoomMix(), WIDTH - 8));
            }
            final float headHeight = 6 + names.size() * 12 + notes.size() * SUB_ROW + 5;
            ensure(headHeight + ROW + SUB_ROW, section.getAccommodation(), true);
            fill(GROUP_FILL, headHeight);
            y -= 6;
            for (final String name : names) {
                y -= 12;
                text(bold, BODY, INK, name, MARGIN + 4);
            }
            for (final String note : notes) {
                y -= SUB_ROW;
                text(regular, SMALL, MUTED, note, MARGIN + 4);
            }
            y -= 5;
            for (final ReportCommands.InvoiceLine line : group.getLines()) {
                ensure(ROW + SUB_ROW, section.getAccommodation(), true);
                row(regular, new String[] {"   " + line.getItem(), line.getDates(),
                    line.getQuantity() + " " + line.getUnit(), String.valueOf(line.getNights()), line.getRate(),
                    line.getAmount()});
                if (!line.getNote().isEmpty()) {
                    y -= SUB_ROW - 2;
                    textRight(regular, SMALL, MUTED, line.getNote(), OPTIONS.columnRight(QUANTITY));
                    y -= 2;
                }
                rule();
            }
        }

        /** After the last hotel: the trip's total and anything the page's own footnotes say. */
        private void closing(final ReportCommands.RoomInvoice invoice) throws IOException {
            ensure(ROW * 3, null, false);
            y -= 10;
            page.setStrokingColor(MUTED);
            page.setLineWidth(1.2f);
            page.moveTo(MARGIN, y);
            page.lineTo(MARGIN + WIDTH, y);
            page.stroke();
            y -= 18;
            textRight(bold, 13f, INK, "Total: " + invoice.getAmount(), MARGIN + WIDTH);
            y -= 16;
            text(regular, SMALL, MUTED, invoice.getGuests() + (invoice.getGuests() == 1 ? " guest" : " guests")
                    + " in " + invoice.getRooms() + (invoice.getRooms() == 1 ? " room" : " rooms")
                    + ". Active reservations only; cancellation fees are not included.", MARGIN);
            if (invoice.getUnpriced() > 0) {
                y -= SUB_ROW;
                text(regular, SMALL, MUTED, invoice.getUnpriced() + (invoice.getUnpriced() == 1
                        ? " reservation has" : " reservations have")
                        + " no lodging option any more and could not be priced.", MARGIN);
            }
        }

        /** A new sheet with the trip's heading and, for a hotel, its name. */
        private void newPage(final String accommodation) throws IOException {
            close();
            final PDPage sheet = new PDPage(SHEET);
            doc.addPage(sheet);
            page = new PDPageContentStream(doc, sheet);
            y = TOP - 14;
            text(bold, 14f, INK, tripTitle + ": Room Invoice", MARGIN);
            if (accommodation != null) {
                y -= 22;
                text(bold, 16f, INK, accommodation, MARGIN);
            }
            y -= 14;
        }

        /** Room for {@code needed} points, else a continuation sheet for the same hotel. */
        private void ensure(final float needed, final String accommodation, final boolean heads)
                throws IOException {
            if (y - needed >= BOTTOM) {
                return;
            }
            newPage(accommodation == null ? null : accommodation + " (continued)");
            if (heads) {
                columnHeads();
            }
        }

        private void columnHeads() throws IOException {
            fill(HEAD_FILL, ROW + 2);
            row(bold, table.heads());
            y -= 2;
        }

        /** One row of the current table at the cursor, text left-aligned and numbers right-aligned. */
        private void row(final PDFont font, final String[] cells) throws IOException {
            y -= ROW - 4;
            float left = MARGIN + 4;
            for (int i = 0; i < cells.length; i++) {
                final float size = fitted(font, cells[i], table.room(cells, i) - 8);
                if (table.right()[i]) {
                    textRight(font, size, INK, cells[i], table.columnRight(i));
                } else {
                    text(font, size, INK, cells[i], left);
                }
                left += table.widths()[i];
            }
            y -= 4;
        }

        /** The body size, or smaller (never under {@link #SMALL}) so a long cell stays inside its column. */
        private static float fitted(final PDFont font, final String text, final float width) throws IOException {
            final float natural = widthOf(font, BODY, text == null ? "" : text);
            return natural <= width ? BODY : Math.max(SMALL, BODY * width / natural);
        }

        /** A band behind the next {@code height} points (a heading row, an option, the subtotal). */
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

        private void text(final PDFont font, final float size, final Color color, final String text,
                final float x) throws IOException {
            page.beginText();
            page.setFont(font, size);
            page.setNonStrokingColor(color);
            page.newLineAtOffset(x, y);
            page.showText(text == null ? "" : text);
            page.endText();
        }

        private void textRight(final PDFont font, final float size, final Color color, final String text,
                final float right) throws IOException {
            text(font, size, color, text, right - widthOf(font, size, text == null ? "" : text));
        }

        /** "Page n of m" on every sheet, which only the finished document can know. */
        private void footers(final String printed) throws IOException {
            close();
            final int pages = doc.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                try (PDPageContentStream foot = new PDPageContentStream(doc, doc.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    page = foot;
                    y = MARGIN;
                    text(regular, SMALL, MUTED, tripTitle + " room invoice, printed " + printed, MARGIN);
                    textRight(regular, SMALL, MUTED, "Page " + (i + 1) + " of " + pages, MARGIN + WIDTH);
                }
            }
            page = null;
        }

        private void close() throws IOException {
            if (page != null) {
                page.close();
                page = null;
            }
        }
    }
}
