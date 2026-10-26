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
 * page shows, the letters the standard PDF fonts cannot draw, a long invoice continuing onto another sheet,
 * the extra and concession rows, and the cost-assumed note that only an internal reader gets.
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

    private static ReportCommands.InvoiceLine extra(final String label, final int quantity, final String unit,
            final String rate, final String amount) {
        final ReportCommands.InvoiceLine line = line(label, quantity, unit, 1, rate, amount, "");
        line.setKind(ReportCommands.LineKind.EXTRA);
        return line;
    }

    private static ReportCommands.InvoiceLine concession(final String amount, final String note) {
        final ReportCommands.InvoiceLine line = line("Concession", 1, "", 0, "", amount, note);
        line.setKind(ReportCommands.LineKind.CONCESSION);
        line.setCents(-1L);
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

    private static ReportCommands.InvoicePersonLine who(final String name, final String rate, final String amount) {
        final ReportCommands.InvoicePersonLine line = new ReportCommands.InvoicePersonLine();
        line.setName(name);
        line.setOption("Single / Double room");
        line.setDates("Nov 16 - Nov 28, 2026");
        line.setNights(12);
        line.setRate(rate);
        line.setAmount(amount);
        return line;
    }

    private static ReportCommands.InvoiceSection section(final String hotel, final String amount,
            final ReportCommands.InvoiceOffer... groups) {
        final ReportCommands.InvoiceSection section = new ReportCommands.InvoiceSection();
        section.setAccommodation(hotel);
        section.setAmount(amount);
        section.setPeopleAmount(amount);
        section.getOffers().addAll(List.of(groups));
        return section;
    }

    private static ReportCommands.RoomInvoice twoHotels() {
        final ReportCommands.RoomInvoice invoice = new ReportCommands.RoomInvoice();
        invoice.getSections().add(section("Pansion Dragićević", "$3,360.00",
                group("Single / Double room", "3 Double rooms, 1 Single room",
                        line("Lodging", 5, "guests", 12, "$50.00", "$3,000.00", ""),
                        line("Single supplement", 3, "guests", 12, "$10.00", "$360.00", ""))));
        invoice.getSections().get(0).getPeople().addAll(List.of(
                who("Joe Smith", "$50.00 + $10.00 single supplement", "$720.00"),
                who("Kevin Paulsen", "$50.00", "$600.00")));
        invoice.getSections().get(0).setSeveralOptions(true);
        invoice.getSections().add(section("Hotel Split", "$720.00",
                group("Double room, shared", "",
                        line("Lodging", 1, "room", 12, "$60.00", "$720.00", "2 guests"))));
        invoice.getSections().get(1).getPeople().add(
                who("Dave Robinson", "Share of a room priced by night, varies by night", "$480.00"));
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
        final List<String> pages = pages(RoomInvoicePdf.render("Spring Demo Trip", twoHotels(), false, PRINTED));
        Assert.assertEquals(pages.size(), 2);
        Assert.assertTrue(pages.get(0).contains("Pansion Dragićević"), pages.get(0));
        Assert.assertFalse(pages.get(0).contains("Hotel Split"), "the second hotel starts its own page");
        Assert.assertTrue(pages.get(1).contains("Hotel Split"), pages.get(1));
        Assert.assertFalse(pages.get(1).contains("Dragi"), "and carries nothing of the first");
    }

    @Test
    public void thePagesCarryTheLinesSubtotalsAndTheTripTotal() throws IOException {
        final List<String> pages = pages(RoomInvoicePdf.render("Spring Demo Trip", twoHotels(), false, PRINTED));
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
    public void eachHotelCarriesItsPerPersonTableOnItsOwnPages() throws IOException {
        final List<String> pages = pages(RoomInvoicePdf.render("Spring Demo Trip", twoHotels(), false, PRINTED));
        final String first = pages.get(0);
        Assert.assertTrue(first.contains("Charges per person"), first);
        Assert.assertTrue(first.contains("Person"), first);
        Assert.assertTrue(first.contains("Joe Smith"), first);
        Assert.assertTrue(first.contains("$50.00 + $10.00 single supplement"), first);
        Assert.assertTrue(first.indexOf("subtotal") < first.indexOf("Charges per person"),
                "the per-person table follows the per-option table");
        Assert.assertTrue(first.lastIndexOf("Single / Double room") > first.indexOf("Charges per person"),
                "with several options, each person row names its own");
        Assert.assertFalse(first.contains("Dave Robinson"), "a person's row stays with their own hotel");
        final String last = pages.get(1);
        Assert.assertTrue(last.contains("Dave Robinson"), last);
        Assert.assertTrue(last.contains("varies by night"), "a long rate still prints, just smaller: " + last);
        Assert.assertEquals(pages.size(), 2, "no page of its own for the per-person table");
    }

    @Test
    public void aLongPerPersonTableContinuesWithItsOwnHeadings() throws IOException {
        final ReportCommands.RoomInvoice invoice = twoHotels();
        for (int i = 0; i < 70; i++) {
            invoice.getSections().get(0).getPeople().add(who("Guest " + i, "$50.00", "$600.00"));
        }
        final List<String> pages = pages(RoomInvoicePdf.render("Big Trip", invoice, false, PRINTED));
        Assert.assertTrue(pages.get(1).contains("Pansion Dragićević (continued)"), pages.get(1));
        Assert.assertTrue(pages.get(1).contains("Person"), "the per-person headings repeat, not the option ones");
        Assert.assertFalse(pages.get(1).contains("Quantity"), pages.get(1));
        Assert.assertTrue(pages.get(pages.size() - 1).contains("Hotel Split"), "the next hotel still starts a page");
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
        final List<String> pages = pages(RoomInvoicePdf.render("Long Trip", invoice, false, PRINTED));
        Assert.assertTrue(pages.size() >= 2, "sixty lines cannot fit one sheet");
        Assert.assertTrue(pages.get(1).contains("Big Hotel (continued)"), pages.get(1));
        Assert.assertTrue(pages.get(1).contains("Quantity"), "the column headings repeat");
        Assert.assertTrue(pages.get(pages.size() - 1).contains("2 reservations have no lodging option"));
    }

    @Test
    public void anEmptyInvoiceIsOnePageThatSaysSo() throws IOException {
        final List<String> pages = pages(RoomInvoicePdf.render(null, new ReportCommands.RoomInvoice(), false, PRINTED));
        Assert.assertEquals(pages.size(), 1);
        Assert.assertTrue(pages.get(0).contains("nothing to invoice"), pages.get(0));
    }

    @Test
    public void todayIsThePrintDateByDefault() throws IOException {
        final String text = pages(RoomInvoicePdf.render("T", twoHotels())).get(0);
        Assert.assertTrue(text.contains("printed "), text);
        Assert.assertTrue(pages(RoomInvoicePdf.render("T", twoHotels(), true)).get(0).contains("printed "));
    }

    @Test
    public void everyPageSaysItIsTheHotelsCost() throws IOException {
        final String external = pages(RoomInvoicePdf.render("T", twoHotels(), false, PRINTED)).get(1);
        Assert.assertTrue(external.contains("Hotel cost: what the hotels charge for the trip's rooms."), external);
        Assert.assertFalse(external.contains("Finances pages"), "an operator is not told where our books are");
        final String internal = pages(RoomInvoicePdf.render("T", twoHotels(), true, PRINTED)).get(0);
        Assert.assertTrue(internal.contains("Participant billing is on the Finances pages."), internal);
    }

    @Test
    public void extraAndConcessionRowsAreDrawn() throws IOException {
        final ReportCommands.RoomInvoice invoice = twoHotels();
        final ReportCommands.InvoiceOffer group = invoice.getSections().get(1).getOffers().get(0);
        group.getLines().add(extra("City tax", 12, "guest-nights", "$2.50", "$30.00"));
        group.getLines().add(extra("Coupon", 1, "", "-$20.00", "-$20.00"));
        group.getLines().add(concession("-$72.00", "Ada Lovelace, Bob Byrne: 10% discount -- comped the guide"));
        invoice.getSections().get(1).setExtrasCents(1_000L);
        invoice.getSections().get(1).setExtrasAmount("$10.00");
        final String page = pages(RoomInvoicePdf.render("Spring Demo Trip", invoice, false, PRINTED)).get(1);
        Assert.assertTrue(page.contains("City tax"), page);
        Assert.assertTrue(page.contains("12 guest-nights"), page);
        Assert.assertTrue(page.contains("$30.00"), page);
        Assert.assertTrue(page.contains("Coupon") && page.contains("-$20.00"), page);
        Assert.assertTrue(page.contains("Concession") && page.contains("-$72.00"), page);
        Assert.assertTrue(page.contains("Ada Lovelace, Bob Byrne: 10% discount -- comped the guide"), page);
        Assert.assertTrue(page.contains("Plus $10.00 in hotel extras"), "the people table says what it leaves out");
    }

    @Test
    public void theCostAssumedNoteIsDrawnOnlyForAnInternalReader() throws IOException {
        final ReportCommands.RoomInvoice invoice = twoHotels();
        invoice.setAnyCostAssumed(true);
        invoice.getSections().get(0).getOffers().get(0).setCostAssumed(true);
        final List<String> internal = pages(RoomInvoicePdf.render("Spring Demo Trip", invoice, true, PRINTED));
        Assert.assertTrue(internal.get(0).contains("Single / Double room *"), "the flagged option: " + internal.get(0));
        Assert.assertTrue(internal.get(1).contains("* Cost assumed from the participant price: no hotel cost has "
                + "been entered for this lodging option."), internal.get(1));
        Assert.assertFalse(internal.get(1).contains("Double room, shared *"), "the other option is not flagged");
        final List<String> external = pages(RoomInvoicePdf.render("Spring Demo Trip", invoice, false, PRINTED));
        Assert.assertFalse(external.get(0).contains("Single / Double room *"), external.get(0));
        Assert.assertFalse(external.get(1).contains("Cost assumed"), "an operator never sees the flag");
        final List<String> unsaid = pages(RoomInvoicePdf.render("Spring Demo Trip", invoice));
        Assert.assertFalse(unsaid.get(1).contains("Cost assumed"), "a caller that does not say is external");
        final ReportCommands.RoomInvoice plain = twoHotels();
        Assert.assertFalse(pages(RoomInvoicePdf.render("T", plain, true, PRINTED)).get(1).contains("Cost assumed"),
                "no note when nothing is assumed");
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
