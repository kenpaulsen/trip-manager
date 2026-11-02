package org.paulsens.trip.util;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import uk.org.okapibarcode.backend.QrCode;
import uk.org.okapibarcode.graphics.Color;
import uk.org.okapibarcode.output.Java2DRenderer;
import uk.org.okapibarcode.output.SvgRenderer;

/**
 * A QR code rendered in Java by okapibarcode (the engine {@code p:barcode} wraps): as inline SVG markup for the
 * ticket page and the batch badges, and as a PNG for the confirmation emails ({@code web/TicketQrServlet}). The pages
 * print a ticket's QR inline rather than through {@code p:barcode}, because that component streams its image
 * through a session-backed dynamic-resource mapping: the ticket page is anonymous and transient, and must not mint
 * a session for whoever holds the link ({@code docs/checkin.md}). Mail clients render no inline SVG, so the email
 * carries an {@code <img>} of the PNG instead; both come from the same {@link #qr(String) symbol}.
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
        final QrCode qr = qr(content);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        render(qr, out);
        return scalable(out.toString(StandardCharsets.UTF_8).trim(), qr.getWidth() * MAGNIFICATION,
                qr.getHeight() * MAGNIFICATION);
    }

    /**
     * The same QR code as PNG bytes (black on white, {@link #MAGNIFICATION} pixels per module, the quiet zone
     * included), or an empty array for blank content.
     */
    public static byte[] png(final String content) {
        if (content == null || content.isBlank()) {
            return new byte[0];
        }
        final QrCode qr = qr(content);
        final int width = (int) Math.round(qr.getWidth() * MAGNIFICATION);
        final int height = (int) Math.round(qr.getHeight() * MAGNIFICATION);
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        final Graphics2D graphics = image.createGraphics();
        try {
            new Java2DRenderer(graphics, MAGNIFICATION, Color.WHITE, Color.BLACK).render(qr);
        } finally {
            graphics.dispose();
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodePng(image, out);
        return out.toByteArray();
    }

    /** The symbol both renderers draw: ECC M, the quiet zone, the content. */
    static QrCode qr(final String content) {
        final QrCode qr = new QrCode();
        // M: a badge on a lanyard gets creased and scuffed, and M still fits a ticket URL in a small version.
        qr.setPreferredEccLevel(QrCode.EccLevel.M);
        qr.setQuietZoneHorizontal(QUIET_ZONE);
        qr.setQuietZoneVertical(QUIET_ZONE);
        qr.setContent(content);
        return qr;
    }

    /** The renderer declares IOException for any stream; an in-memory one never throws, a broken one is unchecked. */
    static void render(final QrCode qr, final OutputStream out) {
        try {
            new SvgRenderer(out, MAGNIFICATION, Color.WHITE, Color.BLACK, false).render(qr);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** ImageIO declares IOException for any stream; an in-memory one never throws, a broken one is unchecked. */
    static void encodePng(final BufferedImage image, final OutputStream out) {
        try {
            ImageIO.write(image, "png", out);
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
