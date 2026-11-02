package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A read-only export of an older deployment's tables (docs/migrations/conference-import.md), one file per table as
 * {@code aws dynamodb scan --output json} writes it: {@code {"Items": [...]}}. Rows are kept as Jackson TREES, never
 * read through today's model classes here: an older row carries fields today's classes would silently drop (a trip's
 * {@code admissionOptions}, for one), and the planner must see every one of them.
 *
 * @param trips         each trip's {@code content} JSON
 * @param people        each person's {@code content} JSON
 * @param registrations each registration's {@code content} JSON
 * @param transactions  each transaction's {@code content} JSON
 * @param privileges    each privilege's {@code content} JSON ({@code name}, {@code description}, {@code people})
 * @param bindings      the binding rows, as stored ({@code id1}, {@code id1_type}, {@code id2}, {@code id2_type})
 */
public record LegacyExport(List<ObjectNode> trips, List<ObjectNode> people, List<ObjectNode> registrations,
        List<ObjectNode> transactions, List<ObjectNode> privileges, List<Binding> bindings) {

    /** The table files an export directory holds; {@code privs.json} and {@code bindings.json} may be absent. */
    public static final List<String> FILES = List.of("trips.json", "people.json", "registrations.json",
            "transactions.json", "privs.json", "bindings.json");

    /** One stored binding row. */
    public record Binding(String id1, String id1Type, String id2, String id2Type) {
    }

    /** Reads an export directory written by the account's export script. */
    public static LegacyExport read(final Path dir, final ObjectMapper mapper) throws IOException {
        return new LegacyExport(contents(dir.resolve("trips.json"), mapper),
                contents(dir.resolve("people.json"), mapper),
                contents(dir.resolve("registrations.json"), mapper),
                contents(dir.resolve("transactions.json"), mapper),
                contents(dir.resolve("privs.json"), mapper),
                bindings(dir.resolve("bindings.json"), mapper));
    }

    /** Every item's {@code content} string, parsed. A missing file is an empty table. */
    static List<ObjectNode> contents(final Path file, final ObjectMapper mapper) throws IOException {
        final List<ObjectNode> rows = new ArrayList<>();
        for (final JsonNode item : items(file, mapper)) {
            final JsonNode content = item.path("content").path("S");
            if (content.isTextual()) {
                rows.add((ObjectNode) mapper.readTree(content.asText()));
            }
        }
        return rows;
    }

    private static List<Binding> bindings(final Path file, final ObjectMapper mapper) throws IOException {
        final List<Binding> rows = new ArrayList<>();
        for (final JsonNode item : items(file, mapper)) {
            rows.add(new Binding(text(item, "id1"), text(item, "id1_type"), text(item, "id2"), text(item, "id2_type")));
        }
        return rows;
    }

    private static List<JsonNode> items(final Path file, final ObjectMapper mapper) throws IOException {
        final List<JsonNode> items = new ArrayList<>();
        if (!Files.exists(file)) {
            return items;
        }
        mapper.readTree(file.toFile()).path("Items").forEach(items::add);
        return items;
    }

    private static String text(final JsonNode item, final String attr) {
        return item.path(attr).path("S").asText(null);
    }
}
