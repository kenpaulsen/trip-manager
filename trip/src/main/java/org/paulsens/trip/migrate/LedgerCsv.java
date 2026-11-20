package org.paulsens.trip.migrate;

import java.util.ArrayList;
import java.util.List;

/**
 * The CSV the ledger migration writes for the owner and reads back (RFC 4180): a cell holding a comma, a quote, a
 * line break or edge spaces is quoted, a quote inside it doubled. The owner fills one column in a spreadsheet, so
 * typed text is also kept from being run as a formula there.
 */
final class LedgerCsv {
    private LedgerCsv() {
    }

    /** One record, without its line end. */
    static String line(final List<String> cells) {
        return String.join(",", cells.stream().map(LedgerCsv::cell).toList());
    }

    private static String cell(final String value) {
        final String text = value == null ? "" : value;
        final boolean quote = text.contains(",") || text.contains("\"") || text.contains("\n")
                || text.contains("\r") || !text.equals(text.strip());
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    /**
     * Text somebody TYPED (a name, a category, a note), made safe to open in a spreadsheet: one starting with
     * {@code = + - @}, a tab or a carriage return would be evaluated as a formula, so it gets a leading
     * apostrophe. Never applied to a cell this tool computes or reads back (ids, amounts, dates).
     */
    static String typed(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    /** Every record of a CSV text; a quoted cell may hold line breaks, and a spreadsheet's leading BOM is dropped. */
    static List<List<String>> parse(final String csv) {
        // Written as an escape on purpose: the character itself is invisible in an editor and a diff, and a
        // cleanup that stripped it would leave startsWith(""), which drops the first character of every file.
        final String text = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        final List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        final StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean pending = false;
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (quoted) {
                if (ch != '"') {
                    cell.append(ch);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (ch == '"') {
                quoted = true;
                pending = true;
            } else if (ch == ',') {
                record.add(cell.toString());
                cell.setLength(0);
                pending = true;
            } else if (ch == '\n') {
                if (pending || cell.length() > 0) {
                    record.add(cell.toString());
                    records.add(record);
                }
                record = new ArrayList<>();
                cell.setLength(0);
                pending = false;
            } else if (ch != '\r') {
                cell.append(ch);
            }
        }
        if (pending || cell.length() > 0) {
            record.add(cell.toString());
            records.add(record);
        }
        return records;
    }
}
