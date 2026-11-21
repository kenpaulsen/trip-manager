package org.paulsens.trip.migrate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The migration that rewrites stored page HTML to its cleaned form ({@link HtmlCleanMigration}, medjugorje#158),
 * held to the owner's rule: a unit with any difference is written only after the owner approved exactly that
 * difference, an apply stops at the first unit that is not cleared, and nothing but the cleaned fields changes.
 */
public class HtmlCleanMigrationTest {
    private static final String TRIP_SAME = "11111111-1111-4111-8111-111111111111";
    private static final String TRIP_RESPELLED = "22222222-2222-4222-8222-222222222222";
    private static final String TRIP_LOOKS_DIFFERENT = "33333333-3333-4333-8333-333333333333";
    private static final String ORG = "44444444-4444-4444-8444-444444444444";
    private static final String PAGE = "page:org:" + ORG + ":home";
    private static final String RESPELLED = "<P class='b a'>Tom & Jerry<BR></P>";
    private static final String RESPELLED_CLEAN = "<p class=\"b a\">Tom &amp; Jerry<br /></p>";

    private static final ContentTemplate TEXT_TEMPLATE = new ContentTemplate("text", 1, "Text", "",
            "<div>{{body}}</div><p>{{caption}}</p>",
            List.of(new Placeholder("body", Placeholder.Type.RICH_TEXT, "Body", null, false),
                    new Placeholder("caption", Placeholder.Type.TEXT, "Caption", null, false)), null, null);

    /** Templates and site addresses only: the rows themselves come from the text store. */
    private static final HtmlClean.Store LOOKUPS = new HtmlClean.Store() {
        @Override
        public List<Trip> trips() {
            return List.of();
        }

        @Override
        public List<ContentRecord> contentRecords() {
            return List.of();
        }

        @Override
        public List<TodoItem> todos(final String tripId) {
            return List.of();
        }

        @Override
        public Optional<ContentTemplate> template(final String templateId, final int version) {
            return "text".equals(templateId) ? Optional.of(TEXT_TEMPLATE) : Optional.empty();
        }

        @Override
        public String siteBase(final String orgId) {
            return "https://acme.example";
        }
    };

    private final ObjectMapper mapper = DAO.getInstance().getMapper();
    private HtmlCleanCli.MemoryRows rows;
    private Map<String, Map<String, String>> original;
    private Path dir;
    private String printed;

    @BeforeMethod
    public void storeRows() throws IOException {
        FakeData.initFakeData();
        final Map<String, Map<String, String>> seed = new LinkedHashMap<>();
        HtmlCleanMigration.TABLES.forEach(table -> seed.put(table, new LinkedHashMap<>()));
        seed.get("trips").put(TRIP_SAME, json(trip(TRIP_SAME, "Alpha", "<p>Welcome</p>")));
        seed.get("trips").put(TRIP_RESPELLED, json(trip(TRIP_RESPELLED, "Bravo", RESPELLED)));
        seed.get("trips").put(TRIP_LOOKS_DIFFERENT, json(trip(TRIP_LOOKS_DIFFERENT, "Charlie", RESPELLED)));
        seed.get("todo_items").put(TRIP_RESPELLED + "/todo1", json(TodoItem.builder().tripId(TRIP_RESPELLED)
                .dataId(DataId.from("todo1")).description("Passport").moreDetails(RESPELLED).build()));
        seed.get("content").put("hero", json(new ContentRecord("hero",
                instance("hero", "<b onclick=\"x()\">Hi</b>", "<p>new</p><script>x()</script>", 2),
                List.of(instance("hero", "Old", "<p onmouseover=\"y()\">old</p>", 1)))));
        rows = new HtmlCleanCli.MemoryRows(seed);
        original = HtmlCleanMigration.readAll(rows);
        dir = Files.createTempDirectory("html-clean-migration");
    }

    private String json(final Object row) throws JsonProcessingException {
        return mapper.writeValueAsString(row);
    }

    private static Trip trip(final String id, final String title, final String description) {
        final Trip trip = Trip.builder().id(id).build();
        trip.setTitle(title);
        trip.setOrgId(ORG);
        trip.setDescription(description);
        trip.setEndDate(LocalDateTime.of(2027, 4, 9, 12, 0));
        return trip;
    }

    private static ContentInstance instance(final String id, final String title, final String body,
            final int version) {
        return new ContentInstance(id, PAGE, title, "text", 1, Map.of("body", body, "caption", "a < b"), null, 0,
                version, null, "tester");
    }

    private int run(final HtmlCleanMigration.Rows over, final String... args) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final String[] all = new String[args.length + 2];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--out";
        all[args.length + 1] = dir.toString();
        final int status = new HtmlCleanCli(mapper, LOOKUPS, over,
                new PrintStream(bytes, true, StandardCharsets.UTF_8)).run(all);
        printed = bytes.toString(StandardCharsets.UTF_8);
        return status;
    }

    private int run(final String... args) {
        return run(rows, args);
    }

    /** The pictures' verdicts, as the private repo's comparison writes them beside plan.json. */
    private void pictures(final String sameKey, final String differentKey) throws IOException {
        Files.createDirectories(dir.resolve("visual"));
        Files.writeString(dir.resolve(HtmlCleanCli.VISUAL), "[{\"key\":\"" + sameKey + "\",\"verdict\":\"SAME\"},"
                + "{\"key\":\"" + differentKey + "\",\"verdict\":\"DIFFERENT\"},"
                + "{\"key\":\"" + PAGE + "\",\"verdict\":\"SAME\"}]");
    }

    private String stored(final String table, final String rowId) {
        return rows.read(table).get(rowId);
    }

    private JsonNode storedTree(final String table, final String rowId) throws JsonProcessingException {
        return mapper.readTree(stored(table, rowId));
    }

    /** The whole road: evaluate, rehearse, stop, approve, go on, verify, and back again byte for byte. */
    @Test
    public void anApplyWritesOnlyWhatIsClearedAndStopsAtTheFirstUnitThatIsNot() throws IOException {
        Assert.assertEquals(run("plan"), 2, "the page's script is a change someone must decide");
        pictures(TRIP_RESPELLED, TRIP_LOOKS_DIFFERENT);

        Assert.assertEquals(run("rehearse"), 2, printed);
        Assert.assertTrue(printed.contains("Rows an apply would write: 2."), printed);
        Assert.assertTrue(printed.contains("Units an apply would STOP at (it stops at the first): 2."), printed);
        Assert.assertTrue(printed.contains("Charlie): its pictures compared as DIFFERENT"), printed);
        Assert.assertTrue(printed.contains(PAGE + "): cleaning changes its content"), printed);
        Assert.assertEquals(HtmlCleanMigration.readAll(rows), original, "a rehearsal writes nothing");

        // Same content respelled and the same pictures: written with no question. The next unit stops the run.
        Assert.assertEquals(run("apply", "--yes"), 2, printed);
        Assert.assertTrue(printed.contains("STOPPED at trip " + TRIP_LOOKS_DIFFERENT), printed);
        Assert.assertEquals(storedTree("trips", TRIP_RESPELLED).path("description").asText(), RESPELLED_CLEAN);
        Assert.assertEquals(storedTree("todo_items", TRIP_RESPELLED + "/todo1").path("moreDetails").asText(),
                RESPELLED_CLEAN);
        Assert.assertEquals(stored("trips", TRIP_LOOKS_DIFFERENT), original.get("trips").get(TRIP_LOOKS_DIFFERENT),
                "nothing of the unit it stopped at was written");
        Assert.assertEquals(stored("content", "hero"), original.get("content").get("hero"), "nor anything after it");
        Assert.assertEquals(stored("trips", TRIP_SAME), original.get("trips").get(TRIP_SAME));

        // The owner's yes to that one unit lets the run go on, as far as the next unit nobody approved.
        Assert.assertEquals(run("approve", "--unit", TRIP_LOOKS_DIFFERENT, "--words", "Yes, approved"), 0, printed);
        Assert.assertEquals(run("apply", "--yes"), 2, printed);
        Assert.assertTrue(printed.contains("STOPPED at page " + PAGE), printed);
        Assert.assertEquals(storedTree("trips", TRIP_LOOKS_DIFFERENT).path("description").asText(), RESPELLED_CLEAN);

        Assert.assertEquals(run("verify"), 2, "the page is still to clean");
        Assert.assertEquals(run("approve", "--unit", PAGE, "--words", "Yes"), 0, printed);
        Assert.assertEquals(run("apply", "--yes"), 0, printed);
        Assert.assertTrue(printed.contains("Verified: cleaning anything stored returns it as it is."), printed);

        // Only the cleaned fields moved: the current version, the undo history, and the escaped caption left alone.
        final JsonNode hero = storedTree("content", "hero");
        Assert.assertEquals(hero.path("current").path("title").asText(), "<b>Hi</b>");
        Assert.assertEquals(hero.path("current").path("values").path("body").asText(), "<p>new</p>");
        Assert.assertEquals(hero.path("previous").path(0).path("values").path("body").asText(), "<p>old</p>");
        Assert.assertEquals(hero.path("previous").path(0).path("title").asText(), "Old");
        Assert.assertEquals(hero.path("current").path("values").path("caption").asText(), "a < b");
        Assert.assertEquals(mapper.readTree(Files.readString(dir.resolve(HtmlCleanCli.APPLIED))).size(), 4);
        Assert.assertEquals(run("apply", "--yes"), 0, "a second apply finds nothing to do: " + printed);

        Assert.assertEquals(run("rollback", "--yes"), 0, printed);
        Assert.assertEquals(HtmlCleanMigration.readAll(rows), original, "every row is back, byte for byte");
    }

    /** An approval is for the texts the owner saw: once the stored text moves, it no longer counts. */
    @Test
    public void anApprovalStopsCountingWhenTheTextChanges() throws IOException {
        run("plan");
        pictures(TRIP_RESPELLED, TRIP_LOOKS_DIFFERENT);
        Assert.assertEquals(run("approve", "--unit", TRIP_LOOKS_DIFFERENT, "--words", "Yes"), 0, printed);
        // Someone edits the trip after the owner looked.
        rows.replace("trips", TRIP_LOOKS_DIFFERENT, stored("trips", TRIP_LOOKS_DIFFERENT),
                json(trip(TRIP_LOOKS_DIFFERENT, "Charlie", "<P>Edited & saved</P>")));

        Assert.assertEquals(run("apply", "--yes"), 2, printed);
        Assert.assertTrue(printed.contains("its text is not what the evaluation judged: evaluate it again (an earlier"
                + " approval was for other text, and no longer counts)"), printed);
        Assert.assertTrue(storedTree("trips", TRIP_LOOKS_DIFFERENT).path("description").asText()
                .contains("<P>Edited"), "the edited trip is untouched");
    }

    /** With no evaluation of its pictures, even a respelling is not written. */
    @Test
    public void aUnitWhosePicturesWereNeverComparedIsNotWritten() {
        run("plan");
        Assert.assertEquals(run("apply", "--yes"), 2, printed);
        Assert.assertTrue(printed.contains("STOPPED at trip " + TRIP_RESPELLED), printed);
        Assert.assertTrue(printed.contains("its pictures have not been compared"), printed);
        Assert.assertEquals(HtmlCleanMigration.readAll(rows), original);
    }

    /** A row saved by someone between the read and the write is left as they saved it, and the run says so. */
    @Test
    public void aRowSavedMeanwhileIsRefusedNotOverwritten() throws IOException {
        run("plan");
        pictures(TRIP_RESPELLED, TRIP_LOOKS_DIFFERENT);
        final HtmlCleanMigration.Rows racing = new HtmlCleanMigration.Rows() {
            @Override
            public Map<String, String> read(final String table) {
                return rows.read(table);
            }

            @Override
            public boolean replace(final String table, final String rowId, final String expected,
                    final String replacement) {
                return !"trips".equals(table) && rows.replace(table, rowId, expected, replacement);
            }
        };
        Assert.assertEquals(run(racing, "apply", "--yes"), 2, printed);
        Assert.assertTrue(printed.contains("REFUSED trips " + TRIP_RESPELLED + " (Bravo): saved by someone since"),
                printed);
        Assert.assertEquals(stored("trips", TRIP_RESPELLED), original.get("trips").get(TRIP_RESPELLED));
    }

    /**
     * A row a script wrote in its own JSON spelling (production has some with escaped letters) is cleaned like any
     * other: it says the same after as before apart from the cleaned field, and a rollback restores its bytes.
     */
    @Test
    public void aRowInAnotherJsonSpellingIsCleanedAndCanBePutBackExactly() throws IOException {
        final String scripted = json(trip(TRIP_RESPELLED, "Bravo del año", RESPELLED)).replace("ñ", "\\u00f1")
                .replaceFirst("\\{", "{ ");
        rows.replace("trips", TRIP_RESPELLED, stored("trips", TRIP_RESPELLED), scripted);
        run("plan");
        pictures(TRIP_RESPELLED, TRIP_LOOKS_DIFFERENT);
        Assert.assertEquals(run("rehearse"), 2, printed);
        Assert.assertTrue(printed.contains("which come back in this program's (same data): 1."), printed);
        run("apply", "--yes");
        final JsonNode trip = storedTree("trips", TRIP_RESPELLED);
        Assert.assertEquals(trip.path("description").asText(), RESPELLED_CLEAN);
        Assert.assertEquals(trip.path("title").asText(), "Bravo del año");
        final com.fasterxml.jackson.databind.node.ObjectNode back =
                (com.fasterxml.jackson.databind.node.ObjectNode) trip.deepCopy();
        back.put("description", RESPELLED);
        Assert.assertEquals(back, mapper.readTree(scripted), "nothing but the cleaned field says anything new");
        Assert.assertEquals(run("rollback", "--yes"), 0, printed);
        Assert.assertEquals(stored("trips", TRIP_RESPELLED), scripted);
    }

    /** A row that is not what its table holds stops the whole run before anything is judged or written. */
    @Test
    public void aRowThatCannotBeReadStopsTheRun() {
        rows.replace("trips", TRIP_SAME, stored("trips", TRIP_SAME), "{\"id\": [not json");
        Assert.assertEquals(run("plan"), 1, printed);
        Assert.assertTrue(printed.contains("trips " + TRIP_SAME + " cannot be read as a Trip"), printed);
        Assert.assertEquals(run("apply", "--yes"), 1, printed);
    }

    @Test
    public void theCommandsRefuseWhatTheyCannotDoSafely() throws IOException {
        Assert.assertEquals(run("apply"), 1, "a write needs --yes");
        Assert.assertEquals(run("approve", "--unit", PAGE, "--words", "Yes"), 1, "no plan to approve from");
        run("plan");
        Assert.assertEquals(run("approve", "--unit", TRIP_SAME, "--words", "Yes"), 1, printed);
        Assert.assertTrue(printed.contains("has nothing to change"), printed);
        Assert.assertEquals(run("approve", "--unit", "no-such-unit", "--words", "Yes"), 1, printed);
        Assert.assertEquals(run("approve", "--unit", PAGE), 1, "the owner's words are part of the record");
        Assert.assertEquals(new HtmlCleanCli(mapper, LOOKUPS, new PrintStream(new ByteArrayOutputStream()))
                .run(new String[] {"verify", "--out", dir.toString()}), 1, "no rows, no verify");
    }

    /**
     * The live rows: the DAO's own text read and its only-if-unchanged write, on the three tables and no other.
     * Through the content table, the one of the three the local store keeps as rows (trips and to-dos live only in
     * its cache there).
     */
    @Test
    public void theLiveRowsAreReplacedOnlyWhileUnchanged() throws IOException {
        final String id = "raw-" + java.util.UUID.randomUUID();
        Assert.assertTrue(DAO.getInstance().saveContent(
                new ContentInstance(id, PAGE, "Raw", "text", 1, Map.of("body", RESPELLED), null, 0, 0, null, "tester"),
                5));
        final HtmlCleanCli.DaoRows live = new HtmlCleanCli.DaoRows();
        final String before = live.read("content").get(id);
        Assert.assertEquals(mapper.readTree(before).path("current").path("values").path("body").asText(), RESPELLED);
        final String after = before.replace("Tom", "Tim");
        Assert.assertFalse(live.replace("content", id, "not what it holds", after));
        Assert.assertTrue(live.replace("content", id, before, after));
        Assert.assertEquals(live.read("content").get(id), after);
        Assert.assertThrows(IllegalArgumentException.class, () -> live.read("people"));
        Assert.assertThrows(IllegalArgumentException.class, () -> live.replace("todo_items", "no-slash", "a", "b"));
        // The local store keeps no to-do rows, so this writes nothing; it is the key's two parts being read.
        live.replace("todo_items", "some-trip/some-todo", "a", "b");
        Assert.assertNotNull(live.read("trips"));
        Assert.assertNotNull(live.read("todo_items"));
    }
}
