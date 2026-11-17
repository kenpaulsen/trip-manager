package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

/**
 * The note-EL repair's command line ({@code docs/migrations/note-el-flatten.md}); the account's script
 * ({@code ../medjugorje/scripts/flatten-note-el.sh}) supplies the classpath, the profile and the region.
 *
 * <pre>
 * plan     --out DIR        reads only: plan.json (exactly what apply writes) and plan.txt (the summary)
 * apply    --out DIR --yes  writes plan.json's changes, each only if its row is unchanged since the plan read it
 *                           (before-&lt;eventId&gt;.json and applied.json land in DIR), then verifies
 * verify   --out DIR        re-reads the table: every planned row holds its new text, and no private note anywhere
 *                           still holds an expression
 * rollback --out DIR --yes  puts back each row applied.json names, if it still holds what apply wrote
 * </pre>
 * Exit status: 0 done, 1 usage or failure, 2 blockers, refused writes or failed checks.
 */
public final class NoteElFlattenCli {
    static final String PLAN = "plan.json";
    static final String APPLIED = "applied.json";

    /** The DAO's own mapper, unchanged: the rows the repair writes must be the JSON every other writer stores. */
    private final ObjectMapper mapper;
    /** For the report files only (plan.json, applied.json), which a person reads. */
    private final ObjectWriter files;
    private final NoteElFlatten.Store store;
    private final PrintStream out;

    NoteElFlattenCli(final ObjectMapper mapper, final NoteElFlatten.Store store, final PrintStream out) {
        this.mapper = mapper;
        this.files = mapper.writerWithDefaultPrettyPrinter();
        this.store = store;
        this.out = out;
    }

    public static void main(final String[] args) {
        System.exit(new NoteElFlattenCli(DAO.getInstance().getMapper(), new DaoStore(), System.out).run(args));
    }

    int run(final String[] args) {
        if (args.length == 0) {
            out.println("usage: plan|apply|verify|rollback --out DIR [--yes]");
            return 1;
        }
        final Map<String, String> opts = CliArgs.options(args);
        try {
            final Path dir = CliArgs.path(opts, "out");
            return switch (args[0]) {
                case "plan" -> plan(dir);
                case "apply" -> apply(dir, opts);
                case "verify" -> verify(dir);
                case "rollback" -> rollback(dir, opts);
                default -> unknown(args[0]);
            };
        } catch (final IOException | RuntimeException ex) {
            out.println("ERROR: " + ex.getMessage());
            return 1;
        }
    }

    private int unknown(final String command) {
        out.println("Unknown command: " + command);
        return 1;
    }

    private int plan(final Path dir) throws IOException {
        final NoteElFlatten.Plan plan = new NoteElFlatten(mapper, store).plan();
        Files.createDirectories(dir);
        files.writeValue(dir.resolve(PLAN).toFile(), plan);
        final List<String> summary = summary(plan);
        Files.write(dir.resolve("plan.txt"), summary);
        summary.forEach(out::println);
        out.println("Wrote " + dir.resolve(PLAN) + " (apply writes exactly this file).");
        return plan.blockers().isEmpty() ? 0 : 2;
    }

    private static List<String> summary(final NoteElFlatten.Plan plan) {
        final List<String> lines = new ArrayList<>();
        final Map<String, int[]> byTrip = new LinkedHashMap<>();
        for (final NoteElFlatten.Change change : plan.changes()) {
            final int[] counts = byTrip.computeIfAbsent(change.tripEnd() + "  " + change.tripTitle(), k -> new int[2]);
            counts[0]++;
            counts[1] += change.notes().stream().mapToInt(NoteElFlatten.Replacement::expressions).sum();
        }
        lines.add("Expressions to replace: " + plan.expressions() + " in " + plan.changes().size() + " event(s) on "
                + byTrip.size() + " trip(s).");
        byTrip.forEach((trip, counts) -> lines.add("  " + trip + ": " + counts[0] + " event(s), " + counts[1]
                + " expression(s)"));
        lines.add("Blockers (left untouched): " + plan.blockers().size());
        plan.blockers().forEach(blocker -> lines.add("  " + blocker.eventId() + " (" + blocker.tripTitle() + "): "
                + blocker.reason()));
        lines.add("Notes that will lose formatting when they are cleaned for display: "
                + plan.formattingLoss().size());
        plan.formattingLoss().forEach(note -> lines.add("  " + note));
        return lines;
    }

    private NoteElFlatten.Plan readPlan(final Path dir) throws IOException {
        return mapper.readValue(dir.resolve(PLAN).toFile(), NoteElFlatten.Plan.class);
    }

    private int apply(final Path dir, final Map<String, String> opts) throws IOException {
        CliArgs.requireYes(opts);
        final NoteElFlatten.Plan plan = readPlan(dir);
        final NoteElFlatten repair = new NoteElFlatten(mapper, store);
        final List<String> applied = new ArrayList<>();
        int refused = 0;
        for (final NoteElFlatten.Change change : plan.changes()) {
            Files.writeString(dir.resolve("before-" + change.eventId() + ".json"), change.before());
            if (repair.apply(change)) {
                applied.add(change.eventId());
                // Written after every row, so a run that stops part way still says what it changed.
                files.writeValue(dir.resolve(APPLIED).toFile(), applied);
            } else {
                refused++;
                out.println("REFUSED " + change.eventId() + " (" + change.tripTitle() + "): changed since the plan");
            }
        }
        out.println("Applied " + applied.size() + " of " + plan.changes().size() + " event(s).");
        final int verified = verify(dir);
        return (refused > 0) ? 2 : verified;
    }

    private int verify(final Path dir) throws IOException {
        final List<String> failures = new NoteElFlatten(mapper, store).verify(readPlan(dir));
        failures.forEach(failure -> out.println("FAILED " + failure));
        out.println(failures.isEmpty() ? "Verified: no private note holds an expression."
                : failures.size() + " check(s) failed.");
        return failures.isEmpty() ? 0 : 2;
    }

    private int rollback(final Path dir, final Map<String, String> opts) throws IOException {
        CliArgs.requireYes(opts);
        final NoteElFlatten.Plan plan = readPlan(dir);
        final List<?> applied = mapper.readValue(dir.resolve(APPLIED).toFile(), List.class);
        final NoteElFlatten repair = new NoteElFlatten(mapper, store);
        int restored = 0;
        int refused = 0;
        for (final NoteElFlatten.Change change : plan.changes()) {
            if (!applied.contains(change.eventId())) {
                continue;
            }
            if (repair.rollback(change)) {
                restored++;
            } else {
                refused++;
                out.println("REFUSED " + change.eventId() + " (" + change.tripTitle() + "): changed since the apply");
            }
        }
        out.println("Restored " + restored + " event(s).");
        return (refused > 0) ? 2 : 0;
    }

    /** The live store: the DAO's uncached raw rows and conditional write, and the itinerary's room lookup. */
    static final class DaoStore implements NoteElFlatten.Store {
        private final RegistrationCommands registrations =
                new RegistrationCommands(() -> Caller.forActor(AuditActor.system()));

        @Override
        public Map<String, String> eventContents() {
            return DAO.getInstance().rawTripEventContents();
        }

        @Override
        public Map<String, Trip> tripsByEventId() {
            final Map<String, Trip> trips = new HashMap<>();
            for (final Trip trip : DAO.getInstance().getRecentTrips(0, Cached.NO)) {
                trip.getTripEventIds().forEach(eventId -> trips.put(eventId, trip));
            }
            return trips;
        }

        @Override
        public String room(final String tripId, final Person.Id personId) {
            return registrations.roomLabel(tripId, personId);
        }

        @Override
        public boolean replace(final String eventId, final String expected, final String replacement) {
            return DAO.getInstance().replaceTripEventContent(eventId, expected, replacement);
        }
    }
}
