package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
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
                "channelId", s(channelId), "content", s("{ not json")))).join();

        Assert.assertTrue(dao.getChannel(ChatChannel.Id.from(channelId)).join().isEmpty());
    }

    @Test
    public void aMangledMembershipReadsAsAbsentAndIsSkippedFromTheRoster() {
        final String channelId = "trip:parse-mem-" + RandomData.genAlpha(6);
        persistence.putItem(b -> b.tableName("chat_members").item(Map.of(
                "channelId", s(channelId), "personId", s("bad"), "content", s("{ not json")))).join();

        Assert.assertTrue(dao.getMembership(ChatChannel.Id.from(channelId), Person.Id.from("bad"))
                .join().isEmpty());
        Assert.assertTrue(dao.listMembers(ChatChannel.Id.from(channelId)).join().isEmpty(),
                "a mangled member row must be skipped, not kill the whole roster listing");
    }

    @Test
    public void theTwoArgConstructorWiresItsOwnCache() {
        Assert.assertNotNull(new ChatDAO(mapper, persistence));
    }
}
