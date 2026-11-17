package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The one-time repair that turns the room EL stored in private notes into text
 * ({@code docs/migrations/note-el-flatten.md}, medjugorje#116), against an in-memory store.
 */
public class NoteElFlattenTest {
    private static final String ROOM_EL = "#{reg.getRoomPDV(theTrip.id, person.id).content}";
    private final Person.Id ann = Person.Id.newInstance();
    private final Person.Id bob = Person.Id.newInstance();
    private ObjectMapper mapper;
    private Trip trip;
    private MapStore store;

    /** The rows in a map; {@link #replace} is conditional exactly as the DAO's is. */
    private static final class MapStore implements NoteElFlatten.Store {
        private final Map<String, String> contents = new HashMap<>();
        private final Map<String, Trip> trips = new HashMap<>();
        private final Map<Person.Id, String> rooms = new HashMap<>();

        @Override
        public Map<String, String> eventContents() {
            return new HashMap<>(contents);
        }

        @Override
        public Map<String, Trip> tripsByEventId() {
            return trips;
        }

        @Override
        public String room(final String tripId, final Person.Id personId) {
            return rooms.getOrDefault(personId, "");
        }

        @Override
        public boolean replace(final String eventId, final String expected, final String replacement) {
            if (!expected.equals(contents.get(eventId))) {
                return false;
            }
            contents.put(eventId, replacement);
            return true;
        }
    }

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        mapper = DAO.getInstance().getMapper();
        trip = DAO.getInstance().getTrip(FakeData.FAKE_TRIP_ID, Cached.NO).orElseThrow();
    }

    @BeforeMethod
    void freshStore() {
        store = new MapStore();
        store.rooms.put(ann, "1<2");
        store.rooms.put(bob, "114");
    }

    private String event(final boolean onTrip, final Map<Person.Id, String> notes) throws IOException {
        final TripEvent event = new TripEvent();
        event.getPrivNotes().putAll(notes);
        store.contents.put(event.getId(), mapper.writeValueAsString(event));
        if (onTrip) {
            store.trips.put(event.getId(), trip);
        }
        return event.getId();
    }

    /** An event whose private notes are plain but whose title holds {@code text}. */
    private String withTitle(final String text) throws IOException {
        final String id = event(true, Map.of(ann, "plain"));
        final String json = store.contents.get(id);
        store.contents.put(id, json.substring(0, json.lastIndexOf('}')) + ",\"title\":\"" + text + "\"}");
        return id;
    }

    @Test
    public void theRoomExpressionBecomesTheRoomAndAnythingElseIsABlocker() throws IOException {
        final String good = event(true, Map.of(ann, "Room: " + ROOM_EL, bob, "nothing to do"));
        final String twice = event(true, Map.of(bob, "#{ reg.getRoomPDV( theTrip.id , person.id ).content } / "
                + ROOM_EL));
        final String unknown = event(true, Map.of(ann, "#{people.getPerson(to.id).last}"));
        final String orphan = event(false, Map.of(ann, ROOM_EL));
        final String outside = withTitle("${7*7}");
        event(true, Map.of(bob, "<span style='font-family:serif'>big</span>"));
        store.contents.put("unreadable", "not json");

        final NoteElFlatten.Plan plan = new NoteElFlatten(mapper, store).plan();

        Assert.assertEquals(plan.changes().size(), 2);
        Assert.assertEquals(plan.expressions(), 3);
        final NoteElFlatten.Change first = plan.changes().stream().filter(c -> c.eventId().equals(good))
                .findFirst().orElseThrow();
        final TripEvent after = mapper.readValue(first.after(), TripEvent.class);
        Assert.assertEquals(after.getPrivNotes().get(ann), "Room: 1&lt;2", "the room, HTML-escaped");
        Assert.assertEquals(after.getPrivNotes().get(bob), "nothing to do");
        Assert.assertEquals(first.tripId(), trip.getId());
        Assert.assertEquals(plan.changes().stream().filter(c -> c.eventId().equals(twice)).findFirst().orElseThrow()
                .notes().get(0).expressions(), 2);
        Assert.assertEquals(plan.blockers().stream().map(NoteElFlatten.Blocker::eventId).sorted().toList(),
                List.of(orphan, outside, unknown, "unreadable").stream().sorted().toList());
        Assert.assertEquals(plan.formattingLoss().size(), 1, "the font family the note cleaner drops");
    }

    @Test
    public void applyWritesOnlyUnchangedRowsAndRollbackPutsThemBack() throws IOException {
        final String good = event(true, Map.of(ann, "Room: " + ROOM_EL));
        final String edited = event(true, Map.of(bob, ROOM_EL));
        final NoteElFlatten repair = new NoteElFlatten(mapper, store);
        final NoteElFlatten.Plan plan = repair.plan();
        final String before = store.contents.get(good);
        store.contents.put(edited, store.contents.get(edited).replace("#{", "#{ "));

        Assert.assertTrue(repair.apply(change(plan, good)));
        Assert.assertFalse(repair.apply(change(plan, edited)), "a row edited since the plan is never overwritten");
        Assert.assertFalse(store.contents.get(good).contains("#{"));
        Assert.assertEquals(repair.verify(plan).size(), 2, "the refused row: not flattened, still an expression");

        Assert.assertTrue(repair.rollback(change(plan, good)));
        Assert.assertEquals(store.contents.get(good), before);
        Assert.assertFalse(repair.rollback(change(plan, good)), "only a row still as the apply wrote it");
    }

    private static NoteElFlatten.Change change(final NoteElFlatten.Plan plan, final String eventId) {
        return plan.changes().stream().filter(c -> c.eventId().equals(eventId)).findFirst().orElseThrow();
    }

    private int run(final ByteArrayOutputStream out, final String... args) {
        return new NoteElFlattenCli(mapper, store, new PrintStream(out, true, StandardCharsets.UTF_8)).run(args);
    }

    @Test
    public void theCommandLinePlansAppliesVerifiesAndRollsBack() throws IOException {
        final String good = event(true, Map.of(ann, "Room: " + ROOM_EL));
        final String before = store.contents.get(good);
        final Path dir = Files.createTempDirectory("note-el-flatten");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Assert.assertEquals(run(out, "plan", "--out", dir.toString()), 0);
        Assert.assertTrue(Files.readString(dir.resolve("plan.txt")).contains("Expressions to replace: 1"));
        Assert.assertEquals(run(out, "apply", "--out", dir.toString()), 1, "a write needs --yes");
        Assert.assertEquals(store.contents.get(good), before);
        Assert.assertEquals(run(out, "apply", "--out", dir.toString(), "--yes"), 0);
        Assert.assertFalse(store.contents.get(good).contains("\n"),
                "rows are stored compact, as TripEventDAO writes");
        Assert.assertTrue(Files.readString(dir.resolve("plan.json")).contains("\n"),
                "the report a person reads is not");
        Assert.assertEquals(Files.readString(dir.resolve("before-" + good + ".json")), before);
        Assert.assertFalse(store.contents.get(good).contains("#{"));
        Assert.assertEquals(run(out, "verify", "--out", dir.toString()), 0);
        Assert.assertEquals(run(out, "rollback", "--out", dir.toString()), 1, "a write needs --yes");
        Assert.assertEquals(run(out, "rollback", "--out", dir.toString(), "--yes"), 0);
        Assert.assertEquals(store.contents.get(good), before);
        Assert.assertEquals(run(out, "verify", "--out", dir.toString()), 2, "rolled back: the expression is back");
        Assert.assertEquals(run(out, "rollback", "--out", dir.toString(), "--yes"), 2, "nothing left to restore");
    }

    @Test
    public void theCommandLineReportsBlockersRefusalsAndMistakes() throws IOException {
        final String good = event(true, Map.of(ann, ROOM_EL));
        event(true, Map.of(ann, "#{people.getPerson(to.id).last}"));
        final Path dir = Files.createTempDirectory("note-el-flatten");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Assert.assertEquals(run(out, "plan", "--out", dir.toString()), 2, "a blocker");
        store.contents.put(good, store.contents.get(good) + " ");
        Assert.assertEquals(run(out, "apply", "--out", dir.toString(), "--yes"), 2, "a refused write");
        Assert.assertTrue(out.toString(StandardCharsets.UTF_8).contains("REFUSED " + good));

        Assert.assertEquals(run(out), 1);
        Assert.assertEquals(run(out, "plan"), 1, "--out is required");
        Assert.assertEquals(run(out, "explode", "--out", dir.toString()), 1);
        Assert.assertEquals(run(out, "verify", "--out", dir.resolve("missing").toString()), 1, "no plan.json");
    }

    /** The live store, in local mode: the trips' events, the reservation's room, the conditional write. */
    @Test
    public void theDaoStoreReadsTheTripsAndTheRooms() {
        final NoteElFlattenCli.DaoStore live = new NoteElFlattenCli.DaoStore();
        final Person.Id matt = FakeData.getFakePeople().stream()
                .filter(p -> "user6@example.com".equals(p.getEmail())).findFirst().orElseThrow().getId();

        Assert.assertEquals(live.tripsByEventId().get(FakeData.FAKE_TRIP_LODGING_EVENT_ID).getId(), trip.getId());
        Assert.assertEquals(live.room(trip.getId(), matt), "114");
        Assert.assertEquals(live.room(trip.getId(), Person.Id.newInstance()), "");
        Assert.assertNotNull(live.eventContents());
        Assert.assertTrue(live.replace("no-such-event", "a", "b"), "the local store models no condition");
    }
}
