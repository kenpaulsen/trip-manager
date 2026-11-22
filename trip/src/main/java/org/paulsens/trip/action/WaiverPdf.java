package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverVersion;

/**
 * The signed copy of one document: the exact version's text, then the signature record -- who signed, for whom,
 * both attestations in the words the signer saw, when (UTC), from where, and the text's SHA-256. Drawn with the
 * money documents' {@link PdfSheet}; the body is flattened to plain text ({@link WaiverHtml#toText}), so headings
 * and lists survive as lines and bullets while styling does not.
 *
 * <p>This is the record of what was signed, so the document's text is never translated: the body is the stored
 * version. The two attestations are {@link WaiverCommands#agreeText} and {@link WaiverCommands#authorityText}
 * made again in the {@code Locale} the caller names, and so is the frame this class draws around them (the title
 * block's labels, the signature record's labels, the closing note, the footer). The one caller,
 * {@code WaiverCommands.signedPdf}, names the trip's language, so the record reads the same for every reader;
 * the signature without a locale draws English.
 */
public final class WaiverPdf {

    /** The signing moment is part of the RECORD, so it reads the same in every language, like the names. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private WaiverPdf() {
    }

    /** "{document} - {traveler}.pdf", safe for a Content-Disposition. */
    public static String fileName(final String title, final String travelerName) {
        return BudgetPdf.safeTitle(title) + " - " + BudgetPdf.safeTitle(travelerName) + ".pdf";
    }

    /** The signed copy with its frame in English. */
    static byte[] render(final Trip trip, final String orgName, final WaiverVersion text,
            final WaiverAcceptance row) throws IOException {
        return render(trip, orgName, text, row, Locale.ENGLISH);
    }

    /** The signed copy with its frame (never the document's text or the attestations) in {@code locale}. */
    static byte[] render(final Trip trip, final String orgName, final WaiverVersion text,
            final WaiverAcceptance row, final Locale locale) throws IOException {
        final String title = (text.getTitle() == null) ? row.getTitle() : text.getTitle();
        final PdfSheet out = new PdfSheet(title, locale);
        out.title(title, List.of(nonNull(orgName),
                Text.forLocale(locale, "pdf.waiver.trip", nonNull(trip.getTitle())), versionLine(text, locale)));
        for (final String paragraph : WaiverHtml.toText(text.getBodyHtml()).split("\n\n")) {
            out.paragraph(paragraph, false);
            out.gap(6);
        }
        out.gap(12);
        out.section(Text.forLocale(locale, "common.signature"), 160);
        out.fields(signatureFields(title, row, locale));
        out.gap(8);
        out.paragraph(Text.forLocale(locale, "pdf.waiver.recordNote"), true);
        return out.finish(LocalDate.now());
    }

    static List<String[]> signatureFields(final String title, final WaiverAcceptance row, final Locale locale) {
        final List<String[]> fields = new ArrayList<>();
        fields.add(new String[] {Text.forLocale(locale, "common.traveler"), nonNull(row.getSubjectName())});
        fields.add(new String[] {Text.forLocale(locale, "pdf.waiver.signedBy"), nonNull(row.getSignerName())
                + (row.getSignerEmail() == null ? "" : " (" + row.getSignerEmail() + ")")});
        if (row.isOnBehalf()) {
            fields.add(new String[] {Text.forLocale(locale, "pdf.waiver.onBehalfOf"),
                nonNull(row.getSubjectName())});
        }
        fields.add(new String[] {Text.forLocale(locale, "pdf.waiver.agreement"),
            WaiverCommands.agreeText(title, locale)});
        fields.add(new String[] {Text.forLocale(locale, "pdf.waiver.authority"),
            WaiverCommands.authorityText(row.getSubjectName(), locale)});
        fields.add(new String[] {Text.forLocale(locale, "pdf.waiver.signedAt"),
            row.getAcceptedAt() == null ? "" : STAMP.format(row.getAcceptedAt())});
        fields.add(new String[] {Text.forLocale(locale, "common.version"),
            String.valueOf(row.getVersion())});
        fields.add(new String[] {Text.forLocale(locale, "pdf.waiver.textHash"), nonNull(row.getSha256())});
        // The client is the stored enum name (WEB, IOS), a code like the address beside it.
        fields.add(new String[] {Text.forLocale(locale, "pdf.waiver.recordedFrom"), row.getClient().name()
                + (row.getIp() == null ? "" : ", " + row.getIp())});
        return fields;
    }

    /** "Version 3, published Oct 27, 2026" for the title block. */
    static String versionLine(final WaiverVersion text, final Locale locale) {
        // The number goes in as text: a number argument would be grouped ("1,000") by the format.
        final String number = String.valueOf(text.getNumber());
        return (text.getPublishedAt() == null) ? Text.forLocale(locale, "pdf.waiver.version", number)
                : Text.forLocale(locale, "pdf.waiver.versionPublished", number,
                        Formats.format(text.getPublishedAt(), "yMMMd", locale));
    }

    private static String nonNull(final String value) {
        return (value == null) ? "" : value;
    }
}
