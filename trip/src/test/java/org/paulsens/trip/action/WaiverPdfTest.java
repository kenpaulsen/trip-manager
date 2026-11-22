package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverVersion;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** The signed copy of a document, read back as text. */
public class WaiverPdfTest {

    private static final String BODY = "<h2>Odricanje</h2><p>Prihvaćam uvjete putovanja u Šibenik.</p>";

    private static Trip trip() {
        return Trip.builder().id("waiver-pdf-trip").title("Šibenik 2027").build();
    }

    private static WaiverVersion version() {
        return WaiverVersion.builder().number(3).title("Release").bodyHtml(BODY).sha256(WaiverHtml.sha256(BODY))
                .publishedAt(LocalDateTime.of(2026, 10, 27, 9, 0)).build();
    }

    private static WaiverAcceptance signature() {
        return WaiverAcceptance.builder().tripId("waiver-pdf-trip").personId(Person.Id.from("waiver-pdf-kid"))
                .waiverId("w").version(3).title("Release").sha256(WaiverHtml.sha256(BODY))
                .signerId(Person.Id.from("waiver-pdf-parent")).signerName("Ana Đurđević")
                .signerEmail("ana@example.com").subjectName("Šibenik Đurđević").agreed(true)
                .authorityAttested(true).acceptedAt(Instant.parse("2026-11-02T10:15:30Z")).ip("192.0.2.1").build();
    }

    @Test
    public void theEnglishCopyCarriesTheTextAndTheSignatureRecord() throws IOException {
        final String text = String.join("\n", PdfText.pages(WaiverPdf.render(trip(), "CFPW", version(),
                signature())));
        for (final String expected : List.of("Release", "CFPW", "Trip: Šibenik 2027",
                "Version 3, published Oct 27, 2026", "Prihvaćam uvjete putovanja u Šibenik.", "Signature",
                "Traveler", "Šibenik Đurđević", "Signed by", "Ana Đurđević (ana@example.com)", "On behalf of",
                "Agreement", "Authority", "Nov 2, 2026 at 10:15:30 UTC", "Text SHA-256", "Recorded from",
                "WEB, 192.0.2.1",
                "This record was made electronically", "Release, printed ", "Page 1 of 1")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
    }

    /**
     * The frame and the two attestations follow the reader named; the document's text and the names are the
     * record and are drawn as they are, letters like č, ć, đ, š, ž included.
     */
    @Test
    public void aCroatianFrameLeavesTheRecordAsItIs() throws IOException {
        final String text = String.join("\n", PdfText.pages(WaiverPdf.render(trip(), "CFPW", version(),
                signature(), Locale.forLanguageTag("hr"))));
        for (final String expected : List.of("Release", "Putovanje: Šibenik 2027",
                "Verzija 3, objavljena: 27. lis 2026.", "Prihvaćam uvjete putovanja u Šibenik.", "Potpis", "Putnik",
                "Šibenik Đurđević", "Potpisao/la", "Ana Đurđević (ana@example.com)", "U ime osobe", "Suglasnost",
                WaiverCommands.agreeText("Release", Locale.forLanguageTag("hr")), "Ovlaštenje", "Vrijeme potpisa",
                "Nov 2, 2026 at 10:15:30 UTC",
                "SHA-256 teksta", "Zabilježeno s", "WEB, 192.0.2.1", "Ovaj je zapis nastao elektronički",
                "Release, ispisano: ", "Stranica 1 od 1")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        for (final String english : List.of("Signature", "Traveler", "Signed by", "printed", "Page 1")) {
            assertFalse(text.contains(english), "English '" + english + "' in a Croatian frame:\n" + text);
        }
    }

    @Test
    public void aSpanishFrameLeavesTheRecordAsItIs() throws IOException {
        final String text = String.join("\n", PdfText.pages(WaiverPdf.render(trip(), "CFPW", version(),
                signature(), Locale.forLanguageTag("es"))));
        for (final String expected : List.of("Viaje: Šibenik 2027", "Versión 3, publicada el 27 oct 2026",
                "Prihvaćam uvjete putovanja u Šibenik.", "Firma", "Viajero", "Šibenik Đurđević", "Firmado por",
                "En nombre de", WaiverCommands.agreeText("Release", Locale.forLanguageTag("es")), "SHA-256 del texto",
                "Registrado desde",
                "Este registro se creó electrónicamente", "Página 1 de 1")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        assertFalse(text.contains("Signed by"), text);
    }
}
