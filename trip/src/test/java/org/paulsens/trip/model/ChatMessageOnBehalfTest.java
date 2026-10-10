package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@code ChatMessage.onBehalfOfId}: the stamp a family manager's post carries for the member it was posted FOR.
 * Additive and nullable, so rows written before it existed still read, and immutable, so every copy keeps it.
 */
public class ChatMessageOnBehalfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Person.Id KEN = Person.Id.from("ken");
    private static final Person.Id LUCY = Person.Id.from("lucy");

    private static ChatMessage stamped() {
        return new ChatMessage(ChatMessage.Id.from("0000000000001"), ChatChannel.Id.forTrip("t1"), KEN, LUCY,
                Instant.now(), null, "Lucy will be late", null, null, null, null, null, null, null, "c1", null);
    }

    @Test
    public void jsonRoundTripKeepsTheStamp() throws Exception {
        final ChatMessage message = stamped();

        final ChatMessage back = MAPPER.readValue(MAPPER.writeValueAsString(message), ChatMessage.class);

        Assert.assertEquals(back, message);
        Assert.assertEquals(back.getOnBehalfOfId(), LUCY);
        Assert.assertEquals(back.getAuthorId(), KEN, "the author is untouched by the stamp");
    }

    /** A row written before the field existed, and a rollback reading a stamped row, both still parse. */
    @Test
    public void aRowWithoutTheFieldReadsAsUnstamped() throws Exception {
        final ChatMessage back = MAPPER.readValue(
                "{\"id\":\"0000000000001\",\"channelId\":\"trip:t1\",\"authorId\":\"ken\",\"body\":\"hi\"}",
                ChatMessage.class);

        Assert.assertNull(back.getOnBehalfOfId());
        Assert.assertEquals(back.getAuthorId(), KEN);
    }

    @Test
    public void theLegacyConstructorStampsNothing() {
        final ChatMessage plain = new ChatMessage(ChatMessage.Id.from("0000000000001"), ChatChannel.Id.forTrip("t1"),
                KEN, Instant.now(), null, "hi", null, null, null, null, null, null, null, null, null);

        Assert.assertNull(plain.getOnBehalfOfId());
    }

    /** Edits, deletes, attachment changes and re-keying all go through a copy; none may drop the stamp. */
    @Test
    public void anEditKeepsTheOnBehalfStamp() {
        final ChatMessage message = stamped();

        Assert.assertEquals(message.withBody("corrected").getOnBehalfOfId(), LUCY);
        Assert.assertEquals(message.withDeleted("admin").getOnBehalfOfId(), LUCY);
        Assert.assertEquals(message.withAttachments(List.<ChatAttachment>of()).getOnBehalfOfId(), LUCY);
        Assert.assertEquals(message.withId(ChatMessage.Id.of(1_700_000_000_000L)).getOnBehalfOfId(), LUCY);
        Assert.assertEquals(message.withExpiresAt(42L).getOnBehalfOfId(), LUCY);
    }
}
