package org.paulsens.trip.action;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class RawMimeTest {

    @Test
    public void oneHtmlPartAndOneAttachment() {
        final byte[] file = "%PDF-1.4 a report".getBytes(StandardCharsets.US_ASCII);
        final String mime = new String(RawMime.multipart("Trips <trips@example.org>", List.of("t@example.org"),
                List.of("ken@example.org"), "Expense report: Rome", "<p>Hi</p>", "Rome - Expense Report.pdf",
                file, "application/pdf"), StandardCharsets.US_ASCII);
        assertTrue(mime.startsWith("From: Trips <trips@example.org>\r\nTo: t@example.org\r\n"), mime);
        assertTrue(mime.contains("Reply-To: ken@example.org\r\n"));
        assertTrue(mime.contains("Subject: Expense report: Rome\r\n"));
        assertTrue(mime.contains("Content-Type: multipart/mixed; boundary=\"=_trip_"));
        assertTrue(mime.contains("Content-Disposition: attachment; filename=\"Rome - Expense Report.pdf\"; "
                + "filename*=UTF-8''Rome%20-%20Expense%20Report.pdf"));
        assertTrue(mime.contains(Base64.getEncoder().encodeToString(file)), "the file, base64");
        assertTrue(mime.contains(Base64.getEncoder().encodeToString("<p>Hi</p>".getBytes(StandardCharsets.UTF_8))));
        assertTrue(mime.trim().endsWith("--"), "the closing boundary");
    }

    @Test
    public void headersAreEncodedAndCannotBeInjected() {
        final String mime = new String(RawMime.multipart("a@example.org", List.of("b@example.org"), List.of(),
                "Gradnici – Dragićević", null, "Dragićević \"x\".pdf", null,
                "application/pdf"), StandardCharsets.US_ASCII);
        assertFalse(mime.contains("Reply-To"), "no Reply-To header when there is none");
        assertTrue(mime.contains("Subject: =?UTF-8?B?"), "a non-ASCII subject is an encoded word");
        assertTrue(mime.contains("filename=\"Dragi_evi_ 'x'.pdf\""), mime);
        assertEquals(RawMime.headerSafe("a@example.org\r\nBcc: evil@example.org"),
                "a@example.org  Bcc: evil@example.org", "a line break cannot start a new header");
        assertEquals(RawMime.encodedWord(null), "");
    }
}
