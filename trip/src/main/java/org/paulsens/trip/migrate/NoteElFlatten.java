package org.paulsens.trip.migrate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.action.ReminderHtml;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

/**
 * Turns the EL stored in trip events' private notes into the text it used to show
 * ({@code docs/migrations/note-el-flatten.md}). Until 2026-11 the itinerary pages evaluated every private note
 * as EL (medjugorje#116), and managers pasted {@code #{reg.getRoomPDV(theTrip.id, person.id).content}} into
 * notes for years to show each traveler's room. The pages no longer evaluate anything, so each such expression
 * is replaced, once, by the room it names: the label {@code RegistrationCommands.getRoomPDV} answers (an ACTIVE
 * reservation's room first, then the legacy free-text room), HTML-escaped.
 *
 * <p>Nothing stored is ever evaluated here either: the ONE expression above is recognized by a pattern and
 * answered by Java. Any other expression is a BLOCKER: its event is reported and left untouched, for a person
 * to decide. A changed row is never overwritten ({@link Store#replace} is conditional on the text it read).
 */
public final class NoteElFlatten {
    /** The room expression, as pasted, with any spacing. */
    static final Pattern ROOM_EXPRESSION = Pattern.compile("#\\{\\s*reg\\s*\\.\\s*getRoomPDV\\s*\\(\\s*theTrip\\s*"
            + "\\.\\s*id\\s*,\\s*person\\s*\\.\\s*id\\s*\\)\\s*\\.\\s*content\\s*\\}");
    /** Any EL marker at all: what must be gone from every note when the repair is done. */
    static final Pattern ANY_EXPRESSION = Pattern.compile("[#$]\\{");

    /** What the repair reads and writes; the DAO in production ({@code NoteElFlattenCli}), a map in tests. */
    public interface Store {
        /** Every event row's stored JSON, by event id, read fresh. */
        Map<String, String> eventContents();

        /** Every trip, by the id of each event it lists. */
        Map<String, Trip> tripsByEventId();

        /** A person's room label on a trip, as the itinerary showed it; "" for none. */
        String room(String tripId, Person.Id personId);

        /** Writes {@code replacement} only if the row still holds {@code expected}; false when it does not. */
        boolean replace(String eventId, String expected, String replacement);
    }

    /** One person's note on an event, and the room that replaced its expression(s). */
    public record Replacement(String personId, String room, int expressions) {
    }

    /** One event row to rewrite: the JSON read, the JSON to write, and why. */
    public record Change(String eventId, String tripId, String tripTitle, String tripEnd, List<Replacement> notes,
            String before, String after) {
    }

    /** An event this repair will not touch, and why; a person decides what to do with it. */
    public record Blocker(String eventId, String tripTitle, String reason) {
    }

    /**
     * What one {@code plan} found: the changes, the blockers, and the notes (event id + person id) the note cleaner
     * will lose formatting from once notes stop being evaluated ({@link ReminderHtml#cleanNote}), for the owner
     * to look at before the pages change.
     */
    public record Plan(List<Change> changes, List<Blocker> blockers, List<String> formattingLoss) {
        /** How many expressions the changes replace. */
        public int expressions() {
            return changes.stream().flatMap(change -> change.notes().stream()).mapToInt(Replacement::expressions)
                    .sum();
        }
    }

    private final ObjectMapper mapper;
    private final Store store;

    public NoteElFlatten(final ObjectMapper mapper, final Store store) {
        this.mapper = mapper;
        this.store = store;
    }

    /** Reads everything and decides; writes nothing. */
    public Plan plan() {
        final List<Change> changes = new ArrayList<>();
        final List<Blocker> blockers = new ArrayList<>();
        final List<String> formattingLoss = new ArrayList<>();
        final Map<String, Trip> trips = store.tripsByEventId();
        for (final Map.Entry<String, String> row : new TreeMap<>(store.eventContents()).entrySet()) {
            planEvent(row.getKey(), row.getValue(), trips.get(row.getKey()), changes, blockers, formattingLoss);
        }
        return new Plan(changes, blockers, formattingLoss);
    }

    private void planEvent(final String eventId, final String content, final Trip trip, final List<Change> changes,
            final List<Blocker> blockers, final List<String> formattingLoss) {
        final TripEvent event = parse(eventId, content, trip, blockers);
        if (event == null) {
            return;
        }
        final List<Replacement> notes = new ArrayList<>();
        final String title = (trip == null) ? "(no trip)" : trip.getTitle();
        final Map<Person.Id, String> byPerson = new TreeMap<>(Comparator.comparing(Person.Id::getValue));
        byPerson.putAll(event.getPrivNotes());
        for (final Map.Entry<Person.Id, String> note : byPerson.entrySet()) {
            if (trip == null && note.getValue() != null && ANY_EXPRESSION.matcher(note.getValue()).find()) {
                blockers.add(new Blocker(eventId, title, "no trip lists this event, so its rooms are unknown"));
                return;
            }
            final String flattened = flatten(note.getKey(), note.getValue(), trip, notes);
            if (ANY_EXPRESSION.matcher(flattened).find()) {
                blockers.add(new Blocker(eventId, title, "a note for " + note.getKey().getValue()
                        + " holds an expression this repair does not know: " + snippet(flattened)));
                return;
            }
            if (ReminderHtml.cleanNoteNoting(flattened).dropped()) {
                formattingLoss.add(eventId + " " + note.getKey().getValue() + " (" + title + ")");
            }
            event.getPrivNotes().put(note.getKey(), flattened);
        }
        if (notes.isEmpty()) {
            if (ANY_EXPRESSION.matcher(content).find()) {
                blockers.add(new Blocker(eventId, title, "an expression outside the private notes"));
            }
            return;
        }
        final String after = write(event);
        if (ANY_EXPRESSION.matcher(after).find()) {
            blockers.add(new Blocker(eventId, title, "an expression outside the private notes"));
        } else {
            changes.add(new Change(eventId, trip.getId(), title, String.valueOf(trip.getEndDate()), notes, content,
                    after));
        }
    }

    private TripEvent parse(final String eventId, final String content, final Trip trip,
            final List<Blocker> blockers) {
        try {
            return mapper.readValue(content, TripEvent.class);
        } catch (final JsonProcessingException ex) {
            blockers.add(new Blocker(eventId, (trip == null) ? "(no trip)" : trip.getTitle(),
                    "the row is not readable as an event: " + ex.getOriginalMessage()));
            return null;
        }
    }

    /** The note with every room expression replaced; records the replacement when there was one. */
    private String flatten(final Person.Id personId, final String note, final Trip trip,
            final List<Replacement> notes) {
        if (note == null) {
            return "";
        }
        final Matcher matcher = ROOM_EXPRESSION.matcher(note);
        final StringBuilder out = new StringBuilder();
        int count = 0;
        String room = "";
        while (matcher.find()) {
            if (count == 0) {
                room = MailTemplates.escape(store.room(trip.getId(), personId));
            }
            count++;
            matcher.appendReplacement(out, Matcher.quoteReplacement(room));
        }
        matcher.appendTail(out);
        if (count > 0) {
            notes.add(new Replacement(personId.getValue(), room, count));
        }
        return out.toString();
    }

    private String write(final TripEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("Unable to write event " + event.getId(), ex);
        }
    }

    private static String snippet(final String note) {
        final Matcher marker = ANY_EXPRESSION.matcher(note);
        final int from = marker.find() ? marker.start() : 0;
        final int end = note.indexOf('}', from);
        return note.substring(from, Math.min(note.length(), (end < 0) ? from + 80 : Math.min(end + 1, from + 200)));
    }

    /** Writes one change; false (writing nothing) when its row changed since the plan read it. */
    public boolean apply(final Change change) {
        return store.replace(change.eventId(), change.before(), change.after());
    }

    /** Puts one change's row back; false (writing nothing) when it changed since the apply wrote it. */
    public boolean rollback(final Change change) {
        return store.replace(change.eventId(), change.after(), change.before());
    }

    /**
     * What is still wrong after an apply: each planned row that does not hold what the apply wrote, and each
     * event whose private notes still hold any expression. Empty when the repair is complete.
     */
    public List<String> verify(final Plan plan) {
        final List<String> failures = new ArrayList<>();
        final Map<String, String> contents = store.eventContents();
        for (final Change change : plan.changes()) {
            if (!change.after().equals(contents.get(change.eventId()))) {
                failures.add(change.eventId() + " (" + change.tripTitle() + ") does not hold the flattened notes");
            }
        }
        for (final Map.Entry<String, String> row : new TreeMap<>(contents).entrySet()) {
            final TripEvent event = parse(row.getKey(), row.getValue(), null, new ArrayList<>());
            final boolean expression = event != null && event.getPrivNotes().values().stream()
                    .anyMatch(note -> note != null && ANY_EXPRESSION.matcher(note).find());
            if (expression) {
                failures.add(row.getKey() + " still has an expression in a private note");
            }
        }
        return failures;
    }
}
