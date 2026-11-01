package org.paulsens.trip.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import uk.org.okapibarcode.backend.QrCode;
import uk.org.okapibarcode.graphics.Color;
import uk.org.okapibarcode.output.SvgRenderer;

/**
 * A QR code as inline SVG markup, rendered in Java by okapibarcode (the engine {@code p:barcode} wraps). The ticket
 * page and the batch badges print a ticket's QR inline rather than through {@code p:barcode}, because that component
 * streams its image through a session-backed dynamic-resource mapping: the ticket page is anonymous and transient,
 * and must not mint a session for whoever holds the link ({@code docs/checkin.md}).
 */
public final class TicketQr {
    /** Pixels per module; the page scales the SVG with CSS, so this only sets the intrinsic size. */
    private static final double MAGNIFICATION = 4;
    /** The four-module quiet zone the QR specification asks for; scanners miss codes printed edge to edge. */
    private static final int QUIET_ZONE = 4;

    private TicketQr() {
    }

    /** The QR code for {@code content} as one {@code <svg>} element (no XML prolog), or "" for blank content. */
    public static String svg(final String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        final QrCode qr = new QrCode();
        // M: a badge on a lanyard gets creased and scuffed, and M still fits a ticket URL in a small version.
        qr.setPreferredEccLevel(QrCode.EccLevel.M);
        qr.setQuietZoneHorizontal(QUIET_ZONE);
        qr.setQuietZoneVertical(QUIET_ZONE);
        qr.setContent(content);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        render(qr, out);
        return scalable(out.toString(StandardCharsets.UTF_8).trim(), qr.getWidth() * MAGNIFICATION,
                qr.getHeight() * MAGNIFICATION);
    }

    /** The renderer declares IOException for any stream; an in-memory one never throws, a broken one is unchecked. */
    static void render(final QrCode qr, final OutputStream out) {
        try {
            new SvgRenderer(out, MAGNIFICATION, Color.WHITE, Color.BLACK, false).render(qr);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The renderer's markup made fit for inlining many times on one page: a {@code viewBox}, so CSS can size it
     * (without one a resized SVG crops instead of scaling); no fixed {@code id="barcode"}, which a sheet of badges
     * would repeat; and no {@code <desc>}, which repeats the encoded URL for screen readers to spell out.
     */
    static String scalable(final String svg, final double width, final double height) {
        return svg.replaceFirst("<svg ", "<svg viewBox=\"0 0 " + Math.round(width) + " " + Math.round(height)
                        + "\" class=\"ticketQr\" role=\"img\" aria-label=\"Ticket QR code\" ")
                .replaceFirst("\\s*<desc>[^<]*</desc>", "")
                .replace(" id=\"barcode\"", "");
    }
}
