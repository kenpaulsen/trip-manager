package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The block list against the real (fake-persistence) DAO: one reserved person-data row per person. */
public class BlockListCommandsTest {

    private DAO dao;
    private final BlockListCommands blocks = new BlockListCommands();

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
    }

    @Test
    public void blockUnblockRoundTrip() throws IOException {
        final Person me = saved();
        final Person them = saved();
        Assert.assertEquals(blocks.blockedBy(me.getId()), List.of(), "nothing blocked to start");

        final BlockListCommands.BlockOutcome blocked = blocks.block(me.getId(), them.getId());
        Assert.assertTrue(blocked.ok(), blocked.message());
        Assert.assertEquals(blocked.personIds(), List.of(them.getId()));
        Assert.assertTrue(blocks.isBlocked(me.getId(), them.getId()));
        Assert.assertFalse(blocks.isBlocked(them.getId(), me.getId()), "a block is one-directional");

        // Stored as one reserved row, readable back through the DAO under the reserved id.
        Assert.assertTrue(dao.getPersonDataValue(me.getId(), BlockListCommands.BLOCK_LIST_ID, Cached.NO)
                .isPresent());

        Assert.assertTrue(blocks.block(me.getId(), them.getId()).ok(), "blocking twice is idempotent");
        Assert.assertEquals(blocks.blockedBy(me.getId()).size(), 1);

        final BlockListCommands.BlockOutcome unblocked = blocks.unblock(me.getId(), them.getId());
        Assert.assertTrue(unblocked.ok());
        Assert.assertEquals(unblocked.personIds(), List.of());
        Assert.assertTrue(blocks.unblock(me.getId(), them.getId()).ok(), "unblocking a stranger is a success");
    }

    @Test
    public void refusesSelfAndUnknownPeople() throws IOException {
        final Person me = saved();
        Assert.assertEquals(blocks.block(me.getId(), me.getId()).code(), BlockListCommands.REFUSED_SELF);
        Assert.assertEquals(blocks.block(me.getId(), null).code(), BlockListCommands.REFUSED_SELF);
        Assert.assertEquals(blocks.block(me.getId(), Person.Id.from("no-such-" + RandomData.genAlpha(8))).code(),
                BlockListCommands.REFUSED_NOT_FOUND);
        Assert.assertEquals(blocks.blockedBy(null), List.of());
    }

    @Test
    public void clearRemovesTheRow() throws IOException {
        final Person me = saved();
        final Person them = saved();
        Assert.assertTrue(blocks.block(me.getId(), them.getId()).ok());
        Assert.assertTrue(blocks.clear(me.getId()));
        Assert.assertEquals(blocks.blockedBy(me.getId()), List.of());
        Assert.assertFalse(blocks.clear(null));
    }

    private Person saved() throws IOException {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("block." + RandomData.genAlpha(10).toLowerCase(Locale.ROOT) + "@example.com")
                .build();
        Assert.assertTrue(dao.savePerson(person));
        return person;
    }

    @Test
    public void aFullListRefusesAnotherBlock() throws IOException {
        final Person me = saved();
        final Person them = saved();
        final List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < BlockListCommands.MAX_BLOCKED; i++) {
            many.add("filler-" + i);
        }
        Assert.assertTrue(dao.savePersonDataValue(org.paulsens.trip.model.PersonDataValue.builder()
                .userId(me.getId()).dataId(BlockListCommands.BLOCK_LIST_ID)
                .type(BlockListCommands.BLOCK_LIST_TYPE).content(many).build()));
        Assert.assertEquals(blocks.blockedBy(me.getId()).size(), BlockListCommands.MAX_BLOCKED);
        Assert.assertEquals(blocks.block(me.getId(), them.getId()).code(), BlockListCommands.REFUSED_FULL);
        Assert.assertTrue(blocks.block(me.getId(), Person.Id.from("filler-3")).ok(),
                "an id already on the list is still an idempotent success");
    }

    @Test
    public void aMalformedRowReadsAsEmpty() throws IOException {
        final Person me = saved();
        Assert.assertTrue(dao.savePersonDataValue(org.paulsens.trip.model.PersonDataValue.builder()
                .userId(me.getId()).dataId(BlockListCommands.BLOCK_LIST_ID)
                .type(BlockListCommands.BLOCK_LIST_TYPE).content("not a list").build()));
        Assert.assertEquals(blocks.blockedBy(me.getId()), List.of());
        Assert.assertTrue(dao.savePersonDataValue(org.paulsens.trip.model.PersonDataValue.builder()
                .userId(me.getId()).dataId(BlockListCommands.BLOCK_LIST_ID)
                .type(BlockListCommands.BLOCK_LIST_TYPE)
                .content(java.util.Arrays.asList("dup", "dup", " ", null, 7)).build()));
        Assert.assertEquals(blocks.blockedBy(me.getId()), List.of(Person.Id.from("dup")),
                "blanks, non-strings and duplicates are dropped");
        Assert.assertFalse(blocks.isBlocked(me.getId(), null));
    }

    @Test
    public void aStoreFailureIsReportedNotThrown() throws IOException {
        final Person me = saved();
        final Person them = saved();
        final java.util.concurrent.atomic.AtomicBoolean explode = new java.util.concurrent.atomic.AtomicBoolean(true);
        final BlockListCommands onBroken = new BlockListCommands() {
            @Override
            protected boolean store(final org.paulsens.trip.model.PersonDataValue row) throws IOException {
                if (explode.get()) {
                    throw new IOException("disk full");
                }
                return false;
            }
        };
        final BlockListCommands.BlockOutcome outcome = onBroken.block(me.getId(), them.getId());
        Assert.assertFalse(outcome.ok());
        Assert.assertEquals(outcome.code(), BlockListCommands.REFUSED_STORE);
        Assert.assertEquals(outcome.personIds(), List.of(), "the answer is what the store still holds");
        explode.set(false);
        Assert.assertEquals(onBroken.block(me.getId(), them.getId()).code(), BlockListCommands.REFUSED_STORE);
        Assert.assertEquals(onBroken.unblock(me.getId(), them.getId()).code(), null, "nothing to remove: ok");
    }
}
