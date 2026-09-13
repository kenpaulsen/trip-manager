package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;

/**
 * A person's block list: the people whose chat messages, photos and comments they no longer want to see
 * (App Store guideline 1.2, "the ability to block abusive users").
 *
 * <p>Stored as ONE {@link PersonDataValue} per person under a reserved {@link DataId} rather than a new
 * table: the person-data table already exists, is backed up and cache-invalidated like everything else, and
 * a reserved id never collides with a trip's registration-question ids (those are random 18-character
 * strings). Because it is never a trip's dataId, a trip delete does not touch it; account deletion removes
 * every person-data row, so it goes with the account.
 *
 * <p>The list is the blocker's private preference. It is never disclosed to the blocked person, never
 * redacted INTO a {@code PersonDto}, and enforcement is client-side (the app hides blocked authors'
 * content) plus one server rule: a blocked person cannot {@code @mention} their blocker.
 */
@Slf4j
@Named("blocks")
@ApplicationScoped
public class BlockListCommands {

    /** The reserved person-data id. Not a random id on purpose: it must be the same for every person. */
    public static final DataId BLOCK_LIST_ID = DataId.from("blocked-people");
    public static final String BLOCK_LIST_TYPE = "BlockList";
    /** Far above honest use; stops a runaway client from growing one row without bound. */
    public static final int MAX_BLOCKED = 500;

    /** Outcome of a block/unblock; the code is one of the {@code REFUSED_*} constants on failure. */
    public record BlockOutcome(boolean ok, String code, String message, List<Person.Id> personIds) {
        static BlockOutcome ok(final List<Person.Id> ids) {
            return new BlockOutcome(true, null, null, ids);
        }

        static BlockOutcome refused(final String code, final String message, final List<Person.Id> ids) {
            return new BlockOutcome(false, code, message, ids);
        }
    }

    public static final String REFUSED_SELF = "self";
    public static final String REFUSED_NOT_FOUND = "not_found";
    public static final String REFUSED_FULL = "full";
    public static final String REFUSED_STORE = "store";

    /** The ids {@code me} has blocked, in the order they were blocked; empty when none. */
    public List<Person.Id> blockedBy(final Person.Id me) {
        if (me == null) {
            return List.of();
        }
        return dao().getPersonDataValue(me, BLOCK_LIST_ID, Cached.NO)
                .map(BlockListCommands::idsOf)
                .orElseGet(List::of);
    }

    /** @return whether {@code blocker} has blocked {@code target}. */
    public boolean isBlocked(final Person.Id blocker, final Person.Id target) {
        return target != null && blockedBy(blocker).contains(target);
    }

    /** Adds {@code target} to {@code me}'s list. Idempotent: blocking twice is one block. */
    public BlockOutcome block(final Person.Id me, final Person.Id target) {
        final List<Person.Id> current = new ArrayList<>(blockedBy(me));
        if (target == null || target.equals(me)) {
            return BlockOutcome.refused(REFUSED_SELF, "You cannot block yourself.", current);
        }
        if (current.contains(target)) {
            // Before the existence check on purpose: a person who has since deleted their account stays
            // blocked, and re-blocking them must not turn into a 404 the client cannot act on.
            return BlockOutcome.ok(current);
        }
        if (dao().getPerson(target, Cached.YES).isEmpty()) {
            return BlockOutcome.refused(REFUSED_NOT_FOUND, "No such person.", current);
        }
        if (current.size() >= MAX_BLOCKED) {
            return BlockOutcome.refused(REFUSED_FULL, "Your block list is full.", current);
        }
        current.add(target);
        return save(me, current);
    }

    /** Removes {@code target} from {@code me}'s list. Idempotent: unblocking a stranger is a success. */
    public BlockOutcome unblock(final Person.Id me, final Person.Id target) {
        final List<Person.Id> current = new ArrayList<>(blockedBy(me));
        if (target == null || !current.remove(target)) {
            return BlockOutcome.ok(current);
        }
        return save(me, current);
    }

    /** Removes the whole row -- account deletion's call. */
    public boolean clear(final Person.Id me) {
        return me != null && Boolean.TRUE.equals(dao().deletePersonDataValue(me, BLOCK_LIST_ID));
    }

    private BlockOutcome save(final Person.Id me, final List<Person.Id> ids) {
        final PersonDataValue row = PersonDataValue.builder()
                .userId(me)
                .dataId(BLOCK_LIST_ID)
                .type(BLOCK_LIST_TYPE)
                .content(ids.stream().map(Person.Id::getValue).toList())
                .build();
        try {
            if (store(row)) {
                return BlockOutcome.ok(List.copyOf(ids));
            }
        } catch (final IOException | RuntimeException ex) {
            log.warn("Block list not saved for {}", me.getValue(), ex);
        }
        return BlockOutcome.refused(REFUSED_STORE, "Your block list was not saved. Try again.", blockedBy(me));
    }

    /** The one write; a seam so a test can make the store fail without a mockable DAO (it is final). */
    protected boolean store(final PersonDataValue row) throws IOException {
        return Boolean.TRUE.equals(dao().savePersonDataValue(row));
    }

    /**
     * The stored content is a JSON array of id strings; after a cache round trip it is a {@code List}, and
     * a row written by hand could hold anything, so everything that is not a non-blank string is skipped.
     */
    private static List<Person.Id> idsOf(final PersonDataValue row) {
        final Object content = row.getContent();
        if (!(content instanceof Collection<?> items)) {
            return List.of();
        }
        final List<Person.Id> ids = new ArrayList<>();
        for (final Object item : items) {
            if (item instanceof String value && !value.isBlank()) {
                final Person.Id id = Person.Id.from(value);
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** Seam for tests that swap the store; production is the singleton. */
    protected DAO dao() {
        return DAO.getInstance();
    }

}
