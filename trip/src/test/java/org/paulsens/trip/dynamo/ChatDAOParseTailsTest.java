package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatDraft;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * {@link ChatDAO}'s parse-failure tails: a chat row the console mangled reads as absent, never as a null
 * element or a 500 in the chat page.
 */
public class ChatDAOParseTailsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Persistence persistence = DynamoLocal.persistence();
    private final ChatDAO dao = new ChatDAO(mapper, persistence, new InMemoryCacheClient());

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    private static AttributeValue s(final String value) {
        return AttributeValue.builder().s(value).build();
    }

    @Test
    public void aMangledChannelReadsAsAbsent() {
        final String channelId = "trip:parse-chan-" + RandomData.genAlpha(6);
        persistence.putItem(b -> b.tableName("chat_channels").item(Map.of(
                "channelId", s(channelId), "content", s("{ not json"))));

        Assert.assertTrue(dao.getChannel(ChatChannel.Id.from(channelId)).isEmpty());
    }

    @Test
    public void aMangledMembershipReadsAsAbsentAndIsSkippedFromTheRoster() {
        final String channelId = "trip:parse-mem-" + RandomData.genAlpha(6);
        persistence.putItem(b -> b.tableName("chat_members").item(Map.of(
                "channelId", s(channelId), "personId", s("bad"), "content", s("{ not json"))));

        Assert.assertTrue(dao.getMembership(ChatChannel.Id.from(channelId), Person.Id.from("bad"))
                .isEmpty());
        Assert.assertTrue(dao.listMembers(ChatChannel.Id.from(channelId)).isEmpty(),
                "a mangled member row must be skipped, not kill the whole roster listing");
    }

    @Test
    public void theTwoArgConstructorWiresItsOwnCache() {
        Assert.assertNotNull(new ChatDAO(mapper, persistence));
    }

    // --- composer drafts ---

    @Test
    public void aMangledDraftReadsAsAbsent() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final ChatDAO drafts = new ChatDAO(mapper, persistence, cache);
        final ChatChannel.Id channelId = ChatChannel.Id.from("trip:parse-draft-" + RandomData.genAlpha(6));
        final Person.Id me = Person.Id.from("draft-person");
        cache.putValue(org.paulsens.trip.cache.CacheKeys.chatDraftKey(channelId.getValue(), me.getValue()),
                "{ not json", null);

        Assert.assertTrue(dao.getDraft(channelId, me).isEmpty(),
                "shared-cache draft key unset for the shared dao");
        Assert.assertTrue(drafts.getDraft(channelId, me).isEmpty(),
                "a mangled draft must read as absent, never surface an error in a composer");
    }

    @Test
    public void draftRoundTripAndDelete() {
        final ChatChannel.Id channelId = ChatChannel.Id.from("trip:draft-rt-" + RandomData.genAlpha(6));
        final Person.Id me = Person.Id.from("draft-rt-person");
        final ChatDraft draft = new ChatDraft("hello @{someone}",
                java.util.List.of(new ChatDraft.Ref("chat/t/x.jpg", "A title", true)), java.time.Instant.now());

        Assert.assertTrue(dao.saveDraft(channelId, me, draft));
        final ChatDraft read = dao.getDraft(channelId, me).orElseThrow();
        Assert.assertEquals(read.body(), "hello @{someone}");
        Assert.assertEquals(read.attachments().size(), 1);
        Assert.assertEquals(read.attachments().get(0).key(), "chat/t/x.jpg");
        Assert.assertEquals(read.attachments().get(0).title(), "A title");
        Assert.assertEquals(read.attachments().get(0).hidden(), Boolean.TRUE);

        Assert.assertTrue(dao.deleteDraft(channelId, me));
        Assert.assertTrue(dao.getDraft(channelId, me).isEmpty());
    }

    @Test
    public void draftNullArgumentsAreRefusedNotThrown() {
        final ChatChannel.Id channelId = ChatChannel.Id.from("trip:draft-null");
        final Person.Id me = Person.Id.from("draft-null-person");
        final ChatDraft draft = new ChatDraft("x", null, java.time.Instant.now());

        Assert.assertFalse(dao.saveDraft(null, me, draft));
        Assert.assertFalse(dao.saveDraft(channelId, null, draft));
        Assert.assertFalse(dao.saveDraft(channelId, me, null));
        Assert.assertTrue(dao.getDraft(null, me).isEmpty());
        Assert.assertTrue(dao.getDraft(channelId, null).isEmpty());
        Assert.assertFalse(dao.deleteDraft(null, me));
        Assert.assertFalse(dao.deleteDraft(channelId, null));
    }
}
