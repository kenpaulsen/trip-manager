package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.chat.InviteLinks;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The app's redeem: the link's pieces in, the outcome and the full trip id out. */
public class ChatInviteResourceTest extends ResourceTestSupport {

    private static final String TRIP_ID = "30692184-e927-4398-b8f9-a6e1eda1ea7b";
    private static final Person.Id ME = Person.Id.from("invite-me");
    private static final String CSRF_OK = "1";

    private ChatCommands chat;
    private MockedStatic<ChatCommands> chatStatic;
    private ChatInviteResource resource;

    @BeforeMethod
    public void bindChat() {
        chat = Mockito.mock(ChatCommands.class);
        chatStatic = Mockito.mockStatic(ChatCommands.class);
        chatStatic.when(ChatCommands::getChatCommands).thenReturn(chat);
        resource = resource(new ChatInviteResource());
        signedInAs(ME);
    }

    @AfterMethod(alwaysRun = true)
    public void closeChatStatic() {
        if (chatStatic != null) {
            chatStatic.close();
            chatStatic = null;
        }
    }

    /** The packed trip id of a short link is expanded before the bean sees it, and echoed back in full. */
    @Test
    public void aShortLinksPiecesRedeemAndAnswerTheFullTripId() {
        Mockito.when(chat.redeemInvite(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq("sel.val"),
                ArgumentMatchers.eq(ME), ArgumentMatchers.any(AuditActor.class))).thenReturn("ok");

        final Response got = resource.redeem(CSRF_OK,
                Map.of("trip", InviteLinks.compactTripId(TRIP_ID), "token", " sel.val "));
        assertOk(got);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) got.getEntity();
        Assert.assertEquals(body.get("result"), "ok");
        Assert.assertEquals(body.get("tripId"), TRIP_ID);
    }

    /** The older link spells the trip id out; a refusal is still a 200 with the reason for the phone to word. */
    @Test
    public void aLongLinksPiecesRedeemTooAndARefusalIsAnAnswer() {
        Mockito.when(chat.redeemInvite(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq("sel.val"),
                ArgumentMatchers.eq(ME), ArgumentMatchers.any(AuditActor.class))).thenReturn("removed");

        final Response got = resource.redeem(CSRF_OK, Map.of("trip", TRIP_ID, "token", "sel.val"));
        assertOk(got);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) got.getEntity();
        Assert.assertEquals(body.get("result"), "removed");
        Assert.assertEquals(body.get("tripId"), TRIP_ID);
    }

    @Test
    public void bothPiecesAreRequiredAndTheCsrfRuleHolds() {
        assertError(resource.redeem(CSRF_OK, Map.of("token", "sel.val")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.redeem(CSRF_OK, Map.of("trip", TRIP_ID)), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.redeem(CSRF_OK, Map.of("trip", " ", "token", "sel.val")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.redeem(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        final Map<String, Object> notStrings = new HashMap<>();
        notStrings.put("trip", 7);
        notStrings.put("token", "sel.val");
        assertError(resource.redeem(CSRF_OK, notStrings), 400, ApiErrors.BAD_REQUEST);
        Mockito.verify(chat, Mockito.never()).redeemInvite(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }
}
