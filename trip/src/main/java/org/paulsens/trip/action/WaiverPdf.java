package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverVersion;

/**
 * The signed copy of one document: the exact version's text, then the signature record -- who signed, for whom,
 * both attestations in the words the signer saw, when (UTC), from where, and the text's SHA-256. Drawn with the
 * money documents' {@link PdfSheet}; the body is flattened to plain text ({@link WaiverHtml#toText}), so headings
 * and lists survive as lines and bullets while styling does not.
 */
public final class WaiverPdf {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter PUBLISHED = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ROOT);

    private WaiverPdf() {
    }

    /** "{document} - {traveler}.pdf", safe for a Content-Disposition. */
    public static String fileName(final String title, final String travelerName) {
        return BudgetPdf.safeTitle(title) + " - " + BudgetPdf.safeTitle(travelerName) + ".pdf";
    }

    static byte[] render(final Trip trip, final String orgName, final WaiverVersion text,
            final WaiverAcceptance row) throws IOException {
        final String title = (text.getTitle() == null) ? row.getTitle() : text.getTitle();
        final PdfSheet out = new PdfSheet(title);
        out.title(title, List.of(nonNull(orgName), "Trip: " + nonNull(trip.getTitle()),
                "Version " + versionLine(text)));
        for (final String paragraph : WaiverHtml.toText(text.getBodyHtml()).split("\n\n")) {
            out.paragraph(paragraph, false);
            out.gap(6);
        }
        out.gap(12);
        out.section("Signature", 160);
        out.fields(signatureFields(title, row));
        out.gap(8);
        out.paragraph("This record was made electronically when the signer ticked both statements above. The "
                + "hash identifies the exact text agreed to.", true);
        return out.finish(LocalDate.now());
    }

    static List<String[]> signatureFields(final String title, final WaiverAcceptance row) {
        final List<String[]> fields = new ArrayList<>();
        fields.add(new String[] {"Traveler", nonNull(row.getSubjectName())});
        fields.add(new String[] {"Signed by", nonNull(row.getSignerName())
                + (row.getSignerEmail() == null ? "" : " (" + row.getSignerEmail() + ")")});
        if (row.isOnBehalf()) {
            fields.add(new String[] {"On behalf of", nonNull(row.getSubjectName())});
        }
        fields.add(new String[] {"Agreement", WaiverCommands.agreeText(title)});
        fields.add(new String[] {"Authority", WaiverCommands.authorityText(row.getSubjectName())});
        fields.add(new String[] {"Signed", row.getAcceptedAt() == null ? "" : STAMP.format(row.getAcceptedAt())});
        fields.add(new String[] {"Version", String.valueOf(row.getVersion())});
        fields.add(new String[] {"Text SHA-256", nonNull(row.getSha256())});
        fields.add(new String[] {"Recorded from", row.getClient().name()
                + (row.getIp() == null ? "" : ", " + row.getIp())});
        return fields;
    }

    /** "3, published Oct 27, 2026" for the title block. */
    static String versionLine(final WaiverVersion text) {
        return text.getNumber() + (text.getPublishedAt() == null ? ""
                : ", published " + PUBLISHED.format(text.getPublishedAt()));
    }

    private static String nonNull(final String value) {
        return (value == null) ? "" : value;
    }
}
