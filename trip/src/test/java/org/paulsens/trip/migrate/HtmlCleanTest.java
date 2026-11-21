package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The read-only evaluation behind the page-HTML cleaning ({@link HtmlClean}, medjugorje#158): every stored value is
 * judged, each trip and each page gets its own verdict, and the plan carries what the screenshot comparison and
 * the owner's per-trip approval need.
 */
public class HtmlCleanTest {
    private static final String TRIP_A = "11111111-1111-4111-8111-111111111111";
    private static final String TRIP_B = "22222222-2222-4222-8222-222222222222";
    private static final String ORG = "33333333-3333-4333-8333-333333333333";
    private static final String CLEAN = "<p><strong>Welcome</strong> pilgrims</p>";

    /** The seeds, for the one test that reads through the real DAO: another class may have cleared them. */
    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    /** A store over plain lists: what the DAO answers in production. */
    private static final class ListStore implements HtmlClean.Store {
        private final List<Trip> trips = new ArrayList<>();
        private final List<ContentRecord> records = new ArrayList<>();
        private final List<TodoItem> todos = new ArrayList<>();

        @Override
        public List<Trip> trips() {
            return trips;
        }

        @Override
        public List<ContentRecord> contentRecords() {
            return records;
        }

        @Override
        public List<TodoItem> todos(final String tripId) {
            return todos.stream().filter(todo -> tripId.equals(todo.getTripId())).toList();
        }

        @Override
        public Optional<ContentTemplate> template(final String templateId, final int version) {
            return "text".equals(templateId) ? Optional.of(TEXT_TEMPLATE) : Optional.empty();
        }

        @Override
        public String siteBase(final String orgId) {
            return ORG.equals(orgId) ? "https://acme.example" : "";
        }
    }

    private static final ContentTemplate TEXT_TEMPLATE = new ContentTemplate("text", 1, "Text", "",
            "<div>{{body}}</div><p>{{caption}}</p>",
            List.of(new Placeholder("body", Placeholder.Type.RICH_TEXT, "Body", null, false),
                    new Placeholder("caption", Placeholder.Type.TEXT, "Caption", null, false)), null, null);

    private static Trip trip(final String id, final String title, final String description) {
        final Trip trip = Trip.builder().id(id).build();
        trip.setTitle(title);
        trip.setOrgId(ORG);
        trip.setDescription(description);
        trip.setEndDate(LocalDateTime.of(2027, 4, 9, 12, 0));
        return trip;
    }

    private static ContentInstance instance(final String id, final String section, final String title,
            final String body) {
        return new ContentInstance(id, section, title, "text", 1, Map.of("body", body, "caption", "a < b"), null, 0,
                1, null, "tester");
    }

    private static HtmlClean.Unit unit(final HtmlClean.Plan plan, final String key) {
        return plan.units().stream().filter(unit -> unit.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    public void eachTripIsJudgedOnItsOwn() {
        final ListStore store = new ListStore();
        store.trips.add(trip(TRIP_A, "Spring", CLEAN));
        store.trips.add(trip(TRIP_B, "Fall", "<p onclick=\"x()\">Hi<script>y()</script></p>"));
        final HtmlClean.Plan plan = new HtmlClean(store).plan();
        Assert.assertEquals(unit(plan, TRIP_A).verdict(), HtmlClean.Verdict.IDENTICAL);
        final HtmlClean.Unit changed = unit(plan, TRIP_B);
        Assert.assertEquals(changed.verdict(), HtmlClean.Verdict.CHANGED, "one trip's script is its own finding");
        Assert.assertEquals(changed.kind(), "trip");
        Assert.assertEquals(changed.ends(), "2027-04-09");
        Assert.assertEquals(changed.url(), "https://acme.example/trip/tripPage.jsf?trip=" + TRIP_B);
        final HtmlClean.Value value = changed.values().get(0);
        Assert.assertEquals(value.drops(), List.of("onclick on <p>", "<script>"));
        Assert.assertEquals(value.after(), "<p>Hi</p>");
        Assert.assertTrue(value.textDiffers(), "the script's text is gone too");
        Assert.assertEquals(plan.count(HtmlClean.Verdict.CHANGED), 1);
    }

    /** Spelling a browser reads alike is not a change, and is told apart from a value that comes back as stored. */
    @Test
    public void aRespelledValueIsEquivalentNotChanged() {
        final ListStore store = new ListStore();
        store.trips.add(trip(TRIP_A, "Spring", "<P class='b a'>Tom & Jerry<BR></P>"));
        final HtmlClean.Value value = unit(new HtmlClean(store).plan(), TRIP_A).values().get(0);
        Assert.assertEquals(value.verdict(), HtmlClean.Verdict.EQUIVALENT);
        Assert.assertTrue(value.drops().isEmpty());
        Assert.assertNotEquals(value.beforeSha(), value.afterSha());
        Assert.assertEquals(value.beforeSha(), LedgerData.sha256("<P class='b a'>Tom & Jerry<BR></P>"));
    }

    /** A trip's sections, the children of its containers and its to-dos are all the trip's to judge. */
    @Test
    public void aTripOwnsItsSectionsTheirChildrenAndItsTodos() {
        final ListStore store = new ListStore();
        store.trips.add(trip(TRIP_A, "Spring", null));
        store.records.add(new ContentRecord("band", instance("band", "page:trip:" + TRIP_A + ":home", "Band", CLEAN),
                List.of()));
        store.records.add(new ContentRecord("child", instance("child", "band", "Child",
                "<p>ok</p><link rel=\"stylesheet\" href=\"/x.css\">"), List.of()));
        store.todos.add(TodoItem.builder().tripId(TRIP_A).dataId(DataId.from("todo1")).description("Passport")
                .moreDetails("<img src=x onerror=alert(1)>").build());
        final HtmlClean.Unit unit = unit(new HtmlClean(store).plan(), TRIP_A);
        Assert.assertEquals(unit.values().stream().map(value -> value.table() + " " + value.field()).toList(),
                List.of("todo_items moreDetails", "content value body", "content value body"));
        Assert.assertEquals(unit.values().get(0).rowId(), TRIP_A + "/todo1");
        Assert.assertEquals(unit.values().get(2).drops(), List.of("<link>"));
        Assert.assertEquals(unit.verdict(), HtmlClean.Verdict.CHANGED);
    }

    /** Only what a page writes as HTML is HTML: a text value and a plain title are escaped where they are shown. */
    @Test
    public void textValuesAndPlainTitlesAreLeftAlone() {
        final ListStore store = new ListStore();
        store.records.add(new ContentRecord("a", instance("a", "page:org:" + ORG + ":home", "Plain title", CLEAN),
                List.of(instance("a", "page:org:" + ORG + ":home", "<h2>Old</h2>", "<p>old</p>"))));
        final HtmlClean.Unit page = unit(new HtmlClean(store).plan(), "page:org:" + ORG + ":home");
        Assert.assertEquals(page.kind(), "page");
        Assert.assertEquals(page.orgId(), ORG);
        Assert.assertEquals(page.url(), "https://acme.example/");
        Assert.assertEquals(page.values().stream().map(value -> value.version() + " " + value.field()).toList(),
                List.of("current value body", "previous 1 title", "previous 1 value body"),
                "the caption (TEXT) and the plain current title are not HTML");
        Assert.assertTrue(page.values().get(0).shown());
        Assert.assertFalse(page.values().get(1).shown(), "undo history is stored, never rendered");
    }

    /** An organization's page has an address a visitor can open; a shared host's page is judged without one. */
    @Test
    public void aPageUnitKnowsWhereItIsShown() {
        final ListStore store = new ListStore();
        store.records.add(new ContentRecord("a", instance("a", "page:org:" + ORG + ":board", "T", CLEAN), List.of()));
        store.records.add(new ContentRecord("b", instance("b", "page:trip-index", "T", CLEAN), List.of()));
        store.records.add(new ContentRecord("c", instance("c", "page:org:unknown:home", "T", CLEAN), List.of()));
        final HtmlClean.Plan plan = new HtmlClean(store).plan();
        Assert.assertEquals(unit(plan, "page:org:" + ORG + ":board").url(), "https://acme.example/page.jsf?name=board");
        Assert.assertEquals(unit(plan, "page:trip-index").url(), "");
        Assert.assertEquals(unit(plan, "page:org:unknown:home").url(), "");
    }

    @Test
    public void aSectionWithNoPageIsStillReported() {
        Assert.assertEquals(HtmlClean.unitOf("x", Map.of("x", "docs")), "docs");
        Assert.assertEquals(HtmlClean.unitOf("gone", Map.of()), "orphan:gone");
        Assert.assertEquals(HtmlClean.unitOf("a", Map.of("a", "b", "b", "a")), "orphan:a",
                "two rows naming each other belong to no page");
    }

    /** The live store answers from the DAO, and the whole evaluation runs over what it reads. */
    @Test
    public void theDaoStoreReadsTripsSectionsTodosAndTemplates() {
        final HtmlCleanCli.DaoStore live = new HtmlCleanCli.DaoStore();
        Assert.assertTrue(live.trips().stream().anyMatch(trip -> FakeData.FAKE_TRIP_ID.equals(trip.getId())));
        Assert.assertFalse(live.contentRecords().isEmpty(), "the seeded pages have sections");
        Assert.assertNotNull(live.todos(FakeData.FAKE_TRIP_ID));
        Assert.assertTrue(live.template("no-such-template", 1).isEmpty());
        final ContentInstance seeded = live.contentRecords().get(0).getCurrent();
        Assert.assertTrue(live.template(seeded.getTemplateId(), seeded.getTemplateVersion()).isPresent());
        Assert.assertTrue(live.template(seeded.getTemplateId(), 9_999).isPresent(),
                "a version that aged out answers the template's latest");
        Assert.assertFalse(live.siteBase(null).endsWith("/"));
        final HtmlClean.Plan plan = new HtmlClean(live).plan();
        Assert.assertFalse(plan.units().isEmpty());
        Assert.assertEquals(plan.count(HtmlClean.Verdict.CHANGED), 0,
                "nothing the seeds hold is something the cleaner removes");
    }

    @Test
    public void theCommandWritesThePlanAndSaysWhetherTheOwnerIsNeeded() throws IOException {
        final ListStore store = new ListStore();
        store.trips.add(trip(TRIP_A, "Spring", CLEAN));
        final Path dir = Files.createTempDirectory("html-clean");
        final ByteArrayOutputStream printed = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
        final HtmlCleanCli cli = new HtmlCleanCli(DAO.getInstance().getMapper(), store, out);
        Assert.assertEquals(cli.run(new String[] {"plan", "--out", dir.toString()}), 0);
        final JsonNode plan = DAO.getInstance().getMapper().readTree(dir.resolve(HtmlCleanCli.PLAN).toFile());
        Assert.assertEquals(plan.get("units").get(0).get("values").get(0).get("before").asText(), CLEAN);
        Assert.assertTrue(Files.readString(dir.resolve("plan.txt")).contains("IDENTICAL  trip " + TRIP_A));

        store.trips.add(trip(TRIP_B, "Fall", "<script>x()</script>"));
        Assert.assertEquals(cli.run(new String[] {"plan", "--out", dir.toString()}), 2);
        Assert.assertTrue(printed.toString(StandardCharsets.UTF_8).contains("trips " + TRIP_B
                + " description [current]: <script>"));
        Assert.assertEquals(cli.run(new String[] {}), 1);
        Assert.assertEquals(cli.run(new String[] {"apply", "--out", dir.toString()}), 1);
        Assert.assertEquals(cli.run(new String[] {"plan"}), 1);
    }
}
