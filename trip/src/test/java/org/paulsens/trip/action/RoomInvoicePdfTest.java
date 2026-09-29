package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The Room Invoice PDF, read back as text: each accommodation on a page of its own, the lines and totals the
 * page shows, the letters the standard PDF fonts cannot draw, and a long invoice continuing onto another sheet.
 */
public class RoomInvoicePdfTest {

    private static final LocalDate PRINTED = LocalDate.of(2026, 9, 29);

    private static ReportCommands.InvoiceLine line(final String item, final int quantity, final String unit,
            final int nights, final String rate, final String amount, final String note) {
        final ReportCommands.InvoiceLine line = new ReportCommands.InvoiceLine();
        line.setItem(item);
        line.setDates("Nov 16 - Nov 28, 2026");
        line.setQuantity(quantity);
        line.setUnit(unit);
        line.setNights(nights);
        line.setRate(rate);
        line.setAmount(amount);
        line.setNote(note);
        return line;
    }

    private static ReportCommands.InvoiceOffer group(final String name, final String mix,
            final ReportCommands.InvoiceLine... lines) {
        final ReportCommands.InvoiceOffer group = new ReportCommands.InvoiceOffer();
        group.setName(name);
        group.setPricing("$50.00 per person per night, single supplement $10.00 per night");
        group.setRoomMix(mix);
        group.getLines().addAll(List.of(lines));
        return group;
    }

    private static ReportCommands.InvoiceSection section(final String hotel, final String amount,
            final ReportCommands.InvoiceOffer... groups) {
        final ReportCommands.InvoiceSection section = new ReportCommands.InvoiceSection();
        section.setAccommodation(hotel);
        section.setAmount(amount);
        section.getOffers().addAll(List.of(groups));
        return section;
    }

    private static ReportCommands.RoomInvoice twoHotels() {
        final ReportCommands.RoomInvoice invoice = new ReportCommands.RoomInvoice();
        invoice.getSections().add(section("Pansion Dragićević", "$3,360.00",
                group("Single / Double room", "3 Double rooms, 1 Single room",
                        line("Lodging", 5, "guests", 12, "$50.00", "$3,000.00", ""),
                        line("Single supplement", 3, "guests", 12, "$10.00", "$360.00", ""))));
        invoice.getSections().add(section("Hotel Split", "$720.00",
                group("Double room, shared", "",
                        line("Lodging", 1, "room", 12, "$60.00", "$720.00", "2 guests"))));
        invoice.setGuests(7);
        invoice.setRooms(5);
        invoice.setAmount("$4,080.00");
        return invoice;
    }

    private static List<String> pages(final byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            final List<String> pages = new ArrayList<>();
            final PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(stripper.getText(doc));
            }
            return pages;
        }
    }

    @Test
    public void eachAccommodationIsAPageOfItsOwn() throws IOException {
        final List<String> pages = pages(RoomInvoicePdf.render("Spring Demo Trip", twoHotels(), PRINTED));
        Assert.assertEquals(pages.size(), 2);
        Assert.assertTrue(pages.get(0).contains("Pansion Dragićević"), pages.get(0));
        Assert.assertFalse(pages.get(0).contains("Hotel Split"), "the second hotel starts its own page");
        Assert.assertTrue(pages.get(1).contains("Hotel Split"), pages.get(1));
        Assert.assertFalse(pages.get(1).contains("Dragi"), "and carries nothing of the first");
    }

    @Test
    public void thePagesCarryTheLinesSubtotalsAndTheTripTotal() throws IOException {
        final List<String> pages = pages(RoomInvoicePdf.render("Spring Demo Trip", twoHotels(), PRINTED));
        final String first = pages.get(0);
        Assert.assertTrue(first.contains("Spring Demo Trip: Room Invoice"), first);
        Assert.assertTrue(first.contains("5 guests"), first);
        Assert.assertTrue(first.contains("$3,000.00"), first);
        Assert.assertTrue(first.contains("Single supplement"), first);
        Assert.assertTrue(first.contains("3 Double rooms, 1 Single room"), first);
        Assert.assertTrue(first.contains("Pansion Dragićević subtotal"), first);
        Assert.assertTrue(first.contains("Page 1 of 2"), first);
        Assert.assertFalse(first.contains("Total: $4,080.00"), "the trip total closes the LAST page");
        final String last = pages.get(1);
        Assert.assertTrue(last.contains("2 guests"), "a per-room line's guest note: " + last);
        Assert.assertTrue(last.contains("Total: $4,080.00"), last);
        Assert.assertTrue(last.contains("7 guests in 5 rooms"), last);
        Assert.assertTrue(last.contains("printed Sep 29, 2026"), last);
        Assert.assertTrue(last.contains("Page 2 of 2"), last);
    }

    @Test
    public void aLongHotelContinuesOnAnotherSheetWithItsHeadingsRepeated() throws IOException {
        final ReportCommands.InvoiceOffer big = group("Every room", "40 Double rooms");
        for (int i = 0; i < 60; i++) {
            big.getLines().add(line("Lodging", i + 1, "guests", 3, "$50.00", "$150.00", ""));
        }
        final ReportCommands.RoomInvoice invoice = new ReportCommands.RoomInvoice();
        invoice.getSections().add(section("Big Hotel", "$9,000.00", big));
        invoice.setUnpriced(2);
        final List<String> pages = pages(RoomInvoicePdf.render("Long Trip", invoice, PRINTED));
        Assert.assertTrue(pages.size() >= 2, "sixty lines cannot fit one sheet");
        Assert.assertTrue(pages.get(1).contains("Big Hotel (continued)"), pages.get(1));
        Assert.assertTrue(pages.get(1).contains("Quantity"), "the column headings repeat");
        Assert.assertTrue(pages.get(pages.size() - 1).contains("2 reservations have no lodging option"));
    }

    @Test
    public void anEmptyInvoiceIsOnePageThatSaysSo() throws IOException {
        final List<String> pages = pages(RoomInvoicePdf.render(null, new ReportCommands.RoomInvoice(), PRINTED));
        Assert.assertEquals(pages.size(), 1);
        Assert.assertTrue(pages.get(0).contains("nothing to invoice"), pages.get(0));
    }

    @Test
    public void todayIsThePrintDateByDefault() throws IOException {
        final String text = pages(RoomInvoicePdf.render("T", twoHotels())).get(0);
        Assert.assertTrue(text.contains("printed "), text);
    }

    @Test
    public void theFileNameIsSafeForAnyFileSystem() {
        Assert.assertEquals(RoomInvoicePdf.fileName("Spring: A/B \"Trip\""), "Spring- A-B -Trip- - Room Invoice.pdf");
        Assert.assertEquals(RoomInvoicePdf.fileName(" "), "Trip - Room Invoice.pdf");
        Assert.assertEquals(RoomInvoicePdf.fileName(null), "Trip - Room Invoice.pdf");
    }

    @Test
    public void wrappingBreaksBetweenWordsAndKeepsAnOverlongWordWhole() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            final PDType0Font font = PDType0Font.load(doc,
                    RoomInvoicePdf.class.getResourceAsStream("/liberation/LiberationSans-Regular.ttf"));
            Assert.assertEquals(RoomInvoicePdf.wrap(font, 10f, "one two three", 1000f), List.of("one two three"));
            Assert.assertEquals(RoomInvoicePdf.wrap(font, 10f, "one two three", 30f),
                    List.of("one", "two", "three"));
            Assert.assertEquals(RoomInvoicePdf.wrap(font, 10f, "Supercalifragilistic", 5f),
                    List.of("Supercalifragilistic"));
            Assert.assertEquals(RoomInvoicePdf.wrap(font, 10f, null, 50f), List.of(""));
        }
    }
}
