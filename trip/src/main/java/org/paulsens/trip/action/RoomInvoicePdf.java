package org.paulsens.trip.action;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.Text;

import static org.paulsens.trip.action.PdfSheet.BAND_FILL;
import static org.paulsens.trip.action.PdfSheet.BODY;
import static org.paulsens.trip.action.PdfSheet.BOLD;
import static org.paulsens.trip.action.PdfSheet.BOTTOM;
import static org.paulsens.trip.action.PdfSheet.HEAD_FILL;
import static org.paulsens.trip.action.PdfSheet.INK;
import static org.paulsens.trip.action.PdfSheet.MARGIN;
import static org.paulsens.trip.action.PdfSheet.MUTED;
import static org.paulsens.trip.action.PdfSheet.REGULAR;
import static org.paulsens.trip.action.PdfSheet.ROW;
import static org.paulsens.trip.action.PdfSheet.RULE;
import static org.paulsens.trip.action.PdfSheet.SHEET;
import static org.paulsens.trip.action.PdfSheet.SMALL;
import static org.paulsens.trip.action.PdfSheet.TOP;
import static org.paulsens.trip.action.PdfSheet.WIDTH;
import static org.paulsens.trip.action.PdfSheet.clean;
import static org.paulsens.trip.action.PdfSheet.fitted;
import static org.paulsens.trip.action.PdfSheet.load;
import static org.paulsens.trip.action.PdfSheet.widthOf;
import static org.paulsens.trip.action.PdfSheet.wrap;

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
 * <p>Since the 2026-10-25 price/cost split the invoice is the HOTEL'S bill ({@code ReportCommands.roomInvoice}
 * at cost), and a Hotel Operator may download their hotel's part. Under each hotel's name the sheet says whose
 * invoice it is -- "Invoice to {@code org} for {@code trip} room charges at {@code hotel}."
 * ({@link RoomInvoiceHeading}) -- so a page handed to the hotel stands on its own. A BLOCKED invoice (an
 * option without a cost; the servlet answers 409 before drawing one) prints its refusal instead of sections.
 * {@code internal} says whether the reader is one of the trip's own people; since the cost-assumed note went
 * (no cost is assumed any more, owner decision 2026-10-26) nothing is drawn differently for them, but the flag
 * stays on the signature so any future internal-only note needs an explicit {@code true}, never a forgotten
 * argument.
 *
 * <p>The sheet's own words (headings, column titles, totals lines, footers) come from the {@code pdf} bundle in
 * the READER's language. The invoice is only ever drawn for the person downloading it, so the signatures without
 * a {@code Locale} read the request's language; the invoice's values (items, dates, rates, the blocked message)
 * arrive already worded by whoever built it. The "Invoice to ..." line is worded HERE from the heading's parts,
 * not by {@link RoomInvoiceHeading#invoiceLine}, which has no reader to word it for.
 *
 * <p>Pure: an invoice and a heading in, bytes out. {@code RoomInvoicePdfTest} reads the text back out.
 */
public final class RoomInvoicePdf {

    // The sheet's measures, greys and fonts are PdfSheet's (static imports), so the money documents look alike.
    private static final float SUB_ROW = 11f;

    /** The per-option table: Item, Dates, Quantity, Nights, Rate, Amount; numbers right-aligned. */
    private static final float[] OPTION_WIDTHS = {140f, 132f, 80f, 45f, 60f, 75f};
    private static final boolean[] OPTION_RIGHT = {false, false, true, true, true, true};
    /** The per-person table: Person, Dates, Nights, Rate, Amount. Rate is prose here, so left-aligned. */
    private static final float[] PEOPLE_WIDTHS = {135f, 122f, 40f, 160f, 75f};
    private static final boolean[] PEOPLE_RIGHT = {false, false, true, false, true};
    /** The per-option Quantity column, under which a per-room line's guest note sits. */
    private static final int QUANTITY = 2;

    private RoomInvoicePdf() {
    }

    /**
     * The whole document, printed today, in the language of the request asking for it; {@code internal} = one
     * of the trip's own people (see the class note).
     */
    public static byte[] render(final RoomInvoiceHeading heading, final ReportCommands.RoomInvoice invoice,
            final boolean internal) {
        return render(heading, invoice, internal, RequestContext.currentLocale());
    }

    /** The whole document, printed today, for a reader of {@code locale}. */
    public static byte[] render(final RoomInvoiceHeading heading, final ReportCommands.RoomInvoice invoice,
            final boolean internal, final Locale locale) {
        return render(heading, invoice, internal, LocalDate.now(), locale);
    }

    /** The whole document; {@code printed} is named in each page's footer. */
    static byte[] render(final RoomInvoiceHeading heading, final ReportCommands.RoomInvoice invoice,
            final boolean internal, final LocalDate printed) {
        return render(heading, invoice, internal, printed, RequestContext.currentLocale());
    }

    static byte[] render(final RoomInvoiceHeading heading, final ReportCommands.RoomInvoice invoice,
            final boolean internal, final LocalDate printed, final Locale reader) {
        final Locale locale = (reader == null) ? Locale.ENGLISH : reader;
        try (PDDocument doc = new PDDocument()) {
            final Writer out = new Writer(doc, load(doc, REGULAR), load(doc, BOLD),
                    heading == null ? new RoomInvoiceHeading(null, null) : heading, locale);
            if (invoice.isBlocked()) {
                out.newPage(null, null);
                out.paragraph(ReportCommands.blockedMessage(invoice.getMissingCost(), !internal));
            } else if (invoice.getSections().isEmpty()) {
                out.newPage(null, null);
                out.paragraph(Text.forLocale(locale, "pdf.roomInvoice.noRooms"));
            }
            final List<ReportCommands.InvoiceSection> sections = invoice.getSections();
            for (int i = 0; i < sections.size(); i++) {
                out.section(sections.get(i));
                if (i == sections.size() - 1) {
                    out.closing(invoice);
                }
            }
            out.footers(Formats.format(printed, "yMMMd", locale));
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        } catch (final IOException ex) {
            throw new UncheckedIOException("Could not draw the Room Invoice PDF", ex);
        }
    }

    /** {@code "Spring Demo Trip - Room Invoice.pdf"}, with anything a file system would refuse replaced. */
    public static String fileName(final String tripTitle) {
        return PdfSheet.safeTitle(tripTitle) + " - Room Invoice.pdf";
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
        private final RoomInvoiceHeading heading;
        private final String tripTitle;
        private final Locale locale;
        private final Table options;
        private final Table people;
        private PDPageContentStream page;
        private float y;
        /** The table being drawn, whose headings a continuation sheet repeats. */
        private Table table;

        private Writer(final PDDocument doc, final PDFont regular, final PDFont bold,
                final RoomInvoiceHeading heading, final Locale locale) {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
            this.heading = heading;
            this.tripTitle = heading.tripTitle();
            this.locale = locale;
            this.options = new Table(OPTION_WIDTHS, new String[] {Text.forLocale(locale, "common.item"),
                Text.forLocale(locale, "common.dates"), Text.forLocale(locale, "common.quantity"),
                Text.forLocale(locale, "common.nights"), Text.forLocale(locale, "pdf.roomInvoice.rate"),
                Text.forLocale(locale, "common.amount")}, OPTION_RIGHT);
            this.people = new Table(PEOPLE_WIDTHS, new String[] {Text.forLocale(locale, "common.person"),
                Text.forLocale(locale, "common.dates"), Text.forLocale(locale, "common.nights"),
                Text.forLocale(locale, "pdf.roomInvoice.rate"), Text.forLocale(locale, "common.amount")},
                    PEOPLE_RIGHT);
            this.table = options;
        }

        /** One accommodation, from a fresh page: every hotel starts its own sheet. */
        private void section(final ReportCommands.InvoiceSection section) throws IOException {
            newPage(section.getAccommodation(), section.getAccommodation());
            table = options;
            columnHeads();
            for (final ReportCommands.InvoiceOffer group : section.getOffers()) {
                group(section, group);
            }
            ensure(ROW + 4, section.getAccommodation(), true);
            fill(BAND_FILL, ROW);
            row(bold, new String[] {Text.forLocale(locale, "pdf.roomInvoice.subtotal", section.getAccommodation()),
                "", "", "", "", section.getAmount()});
            people(section);
        }

        /**
         * The same money per person, on the same sheet when it fits (no new page of its own): a heading, one
         * row per occupant per stay, and a total equal to the subtotal above.
         */
        private void people(final ReportCommands.InvoiceSection section) throws IOException {
            table = people;
            ensure(22 + ROW * 2 + SUB_ROW, section.getAccommodation(), false);
            y -= 22;
            text(bold, 11f, INK, Text.forLocale(locale, "pdf.roomInvoice.chargesPerPerson"), MARGIN);
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
            fill(BAND_FILL, ROW);
            row(bold, new String[] {Text.forLocale(locale, "common.total"), "", "", "",
                section.getPeopleAmount()});
            if (section.getExtrasCents() != 0L) {
                lines(regular, SMALL, MUTED, Text.forLocale(locale, "pdf.roomInvoice.extrasNote",
                        section.getExtrasAmount(), section.getAmount()), SUB_ROW);
            }
        }

        /** An option's heading (name, pricing, room mix) kept on the same sheet as at least its first line. */
        private void group(final ReportCommands.InvoiceSection section, final ReportCommands.InvoiceOffer group)
                throws IOException {
            final List<String> names = wrapped(bold, BODY, group.getName(), WIDTH - 8);
            final List<String> notes = new ArrayList<>(wrapped(regular, SMALL, group.getPricing(), WIDTH - 8));
            if (group.getRoomMix() != null && !group.getRoomMix().isEmpty()) {
                notes.addAll(wrapped(regular, SMALL, group.getRoomMix(), WIDTH - 8));
            }
            final float headHeight = 6 + names.size() * 12 + notes.size() * SUB_ROW + 5;
            ensure(headHeight + ROW + SUB_ROW, section.getAccommodation(), true);
            fill(BAND_FILL, headHeight);
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
                row(regular, cells(line));
                if (line.isConcession()) {
                    // The note names the guests and the discount: prose, so it reads from the left.
                    y -= SUB_ROW - 2;
                    text(regular, SMALL, MUTED, line.getNote(), MARGIN + 12);
                    y -= 2;
                } else if (!line.getNote().isEmpty()) {
                    y -= SUB_ROW - 2;
                    textRight(regular, SMALL, MUTED, line.getNote(), options.columnRight(QUANTITY));
                    y -= 2;
                }
                rule();
            }
        }

        /**
         * One line's six cells. A concession sits outside the arithmetic, so its nights and rate are blank and
         * its amount is negative; an extra (and a FIXED one has no unit) reads like any other line.
         */
        private static String[] cells(final ReportCommands.InvoiceLine line) {
            if (line.isConcession()) {
                return new String[] {"   " + line.getItem(), line.getDates(), String.valueOf(line.getQuantity()),
                    "", "", line.getAmount()};
            }
            return new String[] {"   " + line.getItem(), line.getDates(),
                (line.getQuantity() + " " + line.getUnit()).trim(), String.valueOf(line.getNights()), line.getRate(),
                line.getAmount()};
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
            textRight(bold, 13f, INK, Text.forLocale(locale, "pdf.roomInvoice.grandTotal", invoice.getAmount()),
                    MARGIN + WIDTH);
            y -= 16 - SUB_ROW;
            lines(regular, SMALL, MUTED, Text.forLocale(locale, "pdf.roomInvoice.guestsInRooms",
                    invoice.getGuests(), invoice.getRooms()), SUB_ROW);
            if (invoice.getUnpriced() > 0) {
                lines(regular, SMALL, MUTED, Text.forLocale(locale, "pdf.roomInvoice.unpriced",
                        invoice.getUnpriced()), SUB_ROW);
            }
        }

        /**
         * A sentence from the left margin, each line {@code step} below the last. English fits one line; a
         * translation may not, so it wraps instead of running off the sheet.
         */
        private void lines(final PDFont font, final float size, final Color color, final String sentence,
                final float step) throws IOException {
            for (final String line : wrapped(font, size, sentence, WIDTH)) {
                y -= step;
                text(font, size, color, line, MARGIN);
            }
        }

        /** Cleaned BEFORE it is measured: the font cannot give a width to a character it has no glyph for. */
        private static List<String> wrapped(final PDFont font, final float size, final String text,
                final float width) throws IOException {
            return wrap(font, size, clean(font, text), width);
        }

        /** A sheet that has no table: its one message, wrapped, from the cursor down. */
        private void paragraph(final String message) throws IOException {
            for (final String line : wrapped(regular, BODY, message, WIDTH)) {
                text(regular, BODY, INK, line, MARGIN);
                y -= ROW - 2;
            }
        }

        /** Whose invoice a hotel's sheet is; no "to" without an organization. */
        private String invoiceLine(final String accommodation) {
            return heading.orgName().isEmpty()
                    ? Text.forLocale(locale, "pdf.roomInvoice.invoiceFor", tripTitle, accommodation)
                    : Text.forLocale(locale, "pdf.roomInvoice.invoiceTo", heading.orgName(), tripTitle,
                            accommodation);
        }

        /**
         * A new sheet with the trip's heading and, for a hotel, its name ({@code title}, "(continued)" on a
         * continuation sheet) and whose invoice it is, naming the hotel plainly ({@code accommodation}).
         */
        private void newPage(final String title, final String accommodation) throws IOException {
            close();
            final PDPage sheet = new PDPage(SHEET);
            doc.addPage(sheet);
            page = new PDPageContentStream(doc, sheet);
            y = TOP - 14;
            text(bold, 14f, INK, Text.forLocale(locale, "pdf.roomInvoice.title", tripTitle), MARGIN);
            if (title != null) {
                y -= 24;
                text(bold, 16f, INK, title, MARGIN);
                y -= 12 - SUB_ROW;
                lines(regular, SMALL, MUTED, invoiceLine(accommodation), SUB_ROW);
            }
            y -= 14;
        }

        /** Room for {@code needed} points, else a continuation sheet for the same hotel. */
        private void ensure(final float needed, final String accommodation, final boolean heads)
                throws IOException {
            if (y - needed >= BOTTOM) {
                return;
            }
            newPage(accommodation == null ? null
                    : Text.forLocale(locale, "pdf.roomInvoice.continued", accommodation), accommodation);
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
                final String cell = clean(font, cells[i]);
                final float size = fitted(font, cell, table.room(cells, i) - 8);
                if (table.right()[i]) {
                    textRight(font, size, INK, cell, table.columnRight(i));
                } else {
                    text(font, size, INK, cell, left);
                }
                left += table.widths()[i];
            }
            y -= 4;
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
            PdfSheet.show(page, font, size, color, text, x, y);
        }

        private void textRight(final PDFont font, final float size, final Color color, final String text,
                final float right) throws IOException {
            final String cleaned = clean(font, text);
            text(font, size, color, cleaned, right - widthOf(font, size, cleaned));
        }

        /** "Page n of m" on every sheet, which only the finished document can know. */
        private void footers(final String printed) throws IOException {
            close();
            PdfSheet.footers(doc, regular, Text.forLocale(locale, "pdf.roomInvoice.printedFooter", tripTitle,
                    printed), locale);
        }

        private void close() throws IOException {
            if (page != null) {
                page.close();
                page = null;
            }
        }
    }
}
