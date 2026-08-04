package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link ChatAdminResource}, the badge query.
 *
 * <p>The flattening is the point of the test: {@code ChatSummary} carries a whole {@code ChatChannel} with
 * moderation thresholds and retention settings, and none of that may reach the traveller's conversation list.
 */
public class ChatAdminResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("chat-admin-me");

    private ChatCommands chat;
    private MockedStatic<ChatCommands> chatStatic;
    private ChatAdminResource resource;

    @BeforeMethod
    public void bindChat() {
        chat = Mockito.mock(ChatCommands.class);
        chatStatic = Mockito.mockStatic(ChatCommands.class);
        chatStatic.when(ChatCommands::getChatCommands).thenReturn(chat);
        resource = resource(new ChatAdminResource());
        signedInAs(ME);
    }

    @AfterMethod(alwaysRun = true)
    public void closeChatStatic() {
        if (chatStatic != null) {
            chatStatic.close();
            chatStatic = null;
        }
    }

    @Test
    public void myChannelsFlattensToSummariesWithoutChannelSettings() {
        final ChatChannel channel = Mockito.mock(ChatChannel.class);
        Mockito.when(channel.getId()).thenReturn(ChatChannel.Id.forTrip("trip-1"));
        Mockito.when(chat.myChats(ME)).thenReturn(List.of(
                new ChatCommands.ChatSummary(channel, "Rome 2027", 1_723_000_000_000L, true)));

        final Response response = resource.myChannels();

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> entry = (Map<String, Object>) ((List<?>) response.getEntity()).get(0);
        Assert.assertEquals(entry.get("channelId"), "trip:trip-1");
        Assert.assertEquals(entry.get("tripTitle"), "Rome 2027");
        Assert.assertEquals(entry.get("unread"), true);
        // The flattened form is EXACTLY these four keys; a fifth would be channel settings leaking.
        Assert.assertEquals(entry.keySet().size(), 4, "Unexpected key in badge summary: " + entry.keySet());
    }

    @Test
    public void aChannellessSummaryFlattensToANullId() {
        Mockito.when(chat.myChats(ME)).thenReturn(List.of(
                new ChatCommands.ChatSummary(null, "Orphan", 0L, false)));

        final Response response = resource.myChannels();

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> entry = (Map<String, Object>) ((List<?>) response.getEntity()).get(0);
        Assert.assertNull(entry.get("channelId"));
    }

    @Test
    public void theProducedTypeIsTheChatAdminMediaType() {
        Assert.assertEquals(new ChatAdminResource().versionedType(), ApiMediaTypes.CHAT_ADMIN_V1);
    }
}
