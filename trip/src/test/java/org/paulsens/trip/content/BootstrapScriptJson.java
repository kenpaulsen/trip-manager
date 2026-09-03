package org.paulsens.trip.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.TemplateRecord;

/**
 * Generates the row JSON the private repo's bootstrap scripts embed ({@code install-starter-templates.sh},
 * {@code bootstrap-marketing-page.sh}): a v1 record with empty history, exactly as the DAO would write it,
 * serialized with the DAO's own mapper settings. Test scope on purpose: it is a build-time tool, never
 * application code. Regenerate the scripts from here rather than hand-editing their JSON:
 *
 * <pre>
 *   mvn -q -pl trip test-compile
 *   java --enable-preview -cp trip/target/test-classes:trip/target/classes:$(cat cp.txt) \
 *       org.paulsens.trip.content.BootstrapScriptJson templates &gt; starters.sh
 *   ... BootstrapScriptJson marketing &gt; marketing.sh
 * </pre>
 *
 * <p>The output is the shell fragment between the script's helpers and its {@code put_*} calls: one
 * {@code read -r -d '' NAME <<'JSON'} heredoc per row, then the put lines.
 */
public final class BootstrapScriptJson {

    private static final String INSTALL_AUTHOR = "install-script";

    private BootstrapScriptJson() {
    }

    public static void main(final String[] args) throws JsonProcessingException {
        final String what = args.length == 0 ? "templates" : args[0];
        if ("marketing".equals(what)) {
            printMarketingRows();
        } else {
            printTemplateRows();
        }
    }

    private static ObjectMapper mapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /** Every starter as the installer's v1 row, in {@link StarterTemplates#IDS} order. */
    private static void printTemplateRows() throws JsonProcessingException {
        final ObjectMapper mapper = mapper();
        final StringBuilder puts = new StringBuilder();
        for (final ContentTemplate starter : StarterTemplates.all()) {
            // copy(): the 8-arg compatibility constructor drops the kind fields.
            final ContentTemplate current = starter.copy();
            current.setVersion(1);
            current.setModifiedBy(INSTALL_AUTHOR);
            final String variable = variableName(starter.getId());
            heredoc(variable, mapper.writeValueAsString(new TemplateRecord(starter.getId(), current, List.of())));
            puts.append("put_template \"").append(starter.getId()).append("\" \"$").append(variable).append("\"\n");
        }
        System.out.print(puts);
    }

    /** The marketing page's rows, pinned to v1 of their band templates (a fresh install's version). */
    private static void printMarketingRows() throws JsonProcessingException {
        final ObjectMapper mapper = mapper();
        final StringBuilder puts = new StringBuilder();
        for (final ContentInstance row : MarketingPageBootstrap.rows(id -> 1)) {
            final ContentInstance current = row.copy();
            current.setVersion(1);
            final String variable = "ROW_" + variableName(slotOf(row));
            heredoc(variable, mapper.writeValueAsString(new ContentRecord(row.getId(), current, List.of())));
            puts.append("put_row \"").append(row.getId()).append("\" \"$").append(variable).append("\"\n");
        }
        System.out.print(puts);
    }

    /** A readable variable name per row: the slot title, since the ids are UUIDs. */
    private static String slotOf(final ContentInstance row) {
        return row.getTitle().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String variableName(final String id) {
        return id.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static void heredoc(final String variable, final String json) {
        System.out.println("read -r -d '' " + variable + " <<'JSON' || true");
        System.out.println(json);
        System.out.println("JSON");
        System.out.println();
    }
}
