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
        assertEquals(TicketQr.png(null).length, 0);
        assertEquals(TicketQr.png("  ").length, 0);
    }

    /**
     * The emailed image: a real PNG (the eight-byte signature) whose pixel size is the SVG's intrinsic size, so the
     * two renderings of one ticket are the same symbol at the same scale. No QR decoder is on the test classpath,
     * so the content is pinned through the shared symbol instead ({@code qr}), which both renderers draw.
     */
    @Test
    public void thePngIsARealImageOfTheSameSymbolAsTheSvg() {
        final String url = "https://acme.example.com/trip/ticket.jsf?trip=t&t=ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        final byte[] png = TicketQr.png(url);
        assertEquals(java.util.Arrays.copyOf(png, 8),
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, "the PNG signature");
        final java.awt.image.BufferedImage image = decode(png);
        final uk.org.okapibarcode.backend.QrCode qr = TicketQr.qr(url);
        assertEquals(image.getWidth(), qr.getWidth() * 4, "four pixels per module, quiet zone included");
        assertEquals(image.getHeight(), qr.getHeight() * 4);
        assertTrue(TicketQr.svg(url).contains("viewBox=\"0 0 " + image.getWidth() + " " + image.getHeight() + "\""),
                "the SVG's intrinsic size is the PNG's pixel size");
        // The finder pattern's outer ring starts right after the quiet zone: a dark pixel there, a light one before.
        final int quiet = 4 * 4;
        assertEquals(image.getRGB(quiet, quiet) & 0xFFFFFF, 0x000000, "the first module is ink");
        assertEquals(image.getRGB(quiet - 1, quiet - 1) & 0xFFFFFF, 0xFFFFFF, "the quiet zone is paper");
        assertEquals(TicketQr.png(url), png, "deterministic");
    }

    private static java.awt.image.BufferedImage decode(final byte[] png) {
        try {
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        } catch (final java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @Test(expectedExceptions = java.io.UncheckedIOException.class)
    public void aBrokenImageStreamIsUnchecked() {
        TicketQr.encodePng(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_BYTE_BINARY),
                new java.io.OutputStream() {
                    @Override
                    public void write(final int b) throws java.io.IOException {
                        throw new java.io.IOException("broken");
                    }
                });
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
