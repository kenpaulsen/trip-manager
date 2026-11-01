package org.paulsens.trip.util;

import org.paulsens.trip.model.RegistrationOption;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** {@link TicketQr}: inline SVG, no XML prolog (it is pasted into a page), deterministic, blank for nothing. */
public class TicketQrTest {

    @Test
    public void aUrlBecomesOneInlineSvgElement() {
        final String svg = TicketQr.svg("https://acme.example.com/trip/ticket.jsf?trip=t&t=ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        assertTrue(svg.startsWith("<svg"), svg);
        assertTrue(svg.endsWith("</svg>"), svg);
        assertFalse(svg.contains("<?xml"), "pasted inside a page: no prolog");
        assertTrue(svg.contains("viewBox=\"0 0 "), "scalable by CSS: " + svg.substring(0, 120));
        assertFalse(svg.contains("id=\"barcode\""), "a sheet of badges must not repeat an id");
        assertFalse(svg.contains("<desc>"), "the URL is not read aloud");
        assertEquals(TicketQr.svg("https://acme.example.com/x"), TicketQr.svg("https://acme.example.com/x"));
    }

    @Test
    public void nothingToEncodeIsBlank() {
        assertEquals(TicketQr.svg(null), "");
        assertEquals(TicketQr.svg("  "), "");
    }

    @Test(expectedExceptions = java.io.UncheckedIOException.class)
    public void aBrokenStreamIsUnchecked() {
        final uk.org.okapibarcode.backend.QrCode qr = new uk.org.okapibarcode.backend.QrCode();
        qr.setContent("x");
        TicketQr.render(qr, new java.io.OutputStream() {
            @Override
            public void write(final int b) throws java.io.IOException {
                throw new java.io.IOException("broken");
            }
        });
    }

    @Test
    public void noQuestionsMeanNoTypeQuestion() {
        assertNull(RegistrationOption.typeQuestion(null));
    }
}
