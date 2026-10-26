package org.paulsens.trip.action;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;

/**
 * The raw MIME of one HTML message with one attachment, for SES {@code SendRawEmail}
 * ({@link MailCommands#sendWithAttachment}). Built by hand because the only mail dependency is the SES SDK: a
 * {@code multipart/mixed} body holding the HTML (base64) and the file (base64, 76-column lines), headers in
 * ASCII with the subject and file name RFC 2047 / RFC 2231 encoded so any title survives.
 */
final class RawMime {
    private static final String CRLF = "\r\n";

    private RawMime() {
    }

    static byte[] multipart(final String from, final Collection<String> to, final Collection<String> replyTo,
            final String subject, final String htmlBody, final String fileName, final byte[] attachment,
            final String contentType) {
        final String boundary = "=_trip_" + UUID.randomUUID();
        final Base64.Encoder mime = Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII));
        final StringBuilder out = new StringBuilder();
        out.append("From: ").append(headerSafe(from)).append(CRLF);
        out.append("To: ").append(headerSafe(String.join(", ", to))).append(CRLF);
        if (!replyTo.isEmpty()) {
            out.append("Reply-To: ").append(headerSafe(String.join(", ", replyTo))).append(CRLF);
        }
        out.append("Subject: ").append(encodedWord(subject)).append(CRLF);
        out.append("MIME-Version: 1.0").append(CRLF);
        out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append('"').append(CRLF);
        out.append(CRLF);
        out.append("--").append(boundary).append(CRLF);
        out.append("Content-Type: text/html; charset=UTF-8").append(CRLF);
        out.append("Content-Transfer-Encoding: base64").append(CRLF).append(CRLF);
        out.append(mime.encodeToString((htmlBody == null ? "" : htmlBody).getBytes(StandardCharsets.UTF_8)));
        out.append(CRLF);
        out.append("--").append(boundary).append(CRLF);
        out.append("Content-Type: ").append(contentType).append("; name=\"").append(asciiName(fileName))
                .append('"').append(CRLF);
        out.append("Content-Disposition: attachment; filename=\"").append(asciiName(fileName))
                .append("\"; filename*=UTF-8''")
                .append(URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")).append(CRLF);
        out.append("Content-Transfer-Encoding: base64").append(CRLF).append(CRLF);
        out.append(mime.encodeToString(attachment == null ? new byte[0] : attachment));
        out.append(CRLF);
        out.append("--").append(boundary).append("--").append(CRLF);
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** RFC 2047: the text as one UTF-8 base64 encoded-word when it is not plain ASCII. */
    static String encodedWord(final String text) {
        final String value = (text == null) ? "" : text;
        if (value.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
            return value;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** A header value with line breaks removed (no header injection) and non-ASCII encoded. */
    static String headerSafe(final String value) {
        return encodedWord(value.replaceAll("[\\r\\n]", " "));
    }

    /** The file name in plain ASCII for the old-style parameter; quotes and control characters replaced. */
    static String asciiName(final String fileName) {
        return fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
    }
}
