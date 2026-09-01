package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.dto.TripDto;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The corners the mainline resource tests walked past: every chat endpoint's bad-channel refusal, the read-denial
 * message table, the remaining reaction/edit error mappings, BaseResource's session fallbacks, and the trip
 * update's field-by-field apply. Corners, not filler -- each of these is a branch a client can actually hit.
 */
public class ApiCoverageTailTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("tail-me");
    private static final String BAD = "not-a-trip-channel";

    private ChatCommands chat;
    private MockedStatic<ChatCommands> chatStatic;
    private ChatResource chatResource;

    @BeforeMethod
    public void bindChat() {
        chat = Mockito.mock(ChatCommands.class);
        chatStatic = Mockito.mockStatic(ChatCommands.class);
        chatStatic.when(ChatCommands::getChatCommands).thenReturn(chat);
        chatResource = resource(new ChatResource());
        signedInAs(ME);
    }

    @AfterMethod(alwaysRun = true)
    public void closeChatStatic() {
        if (chatStatic != null) {
            chatStatic.close();
            chatStatic = null;
        }
    }

    // --- every chat endpoint refuses a non-trip channel id ---

    @Test
    public void everyChatEndpointRefusesANonTripChannelId() {
        assertError(chatResource.send(BAD, CSRF_OK, ApiMediaTypes.CHAT_V1, Map.of()), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.delete(BAD, "m1", CSRF_OK), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.edit(BAD, "m1", CSRF_OK, Map.of()), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.markRead(BAD, CSRF_OK, Map.of("cursor", "m1")), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.react(BAD, "m1", "x", CSRF_OK), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.reactions(BAD, "m1", "m2"), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.join(BAD, CSRF_OK), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.leave(BAD, CSRF_OK), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.export(BAD, 10), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.roster(BAD), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.prefs(BAD), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.savePrefs(BAD, CSRF_OK, Map.of()), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.mute(BAD, "t", CSRF_OK, Map.of("minutes", 5)), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.unmute(BAD, "t", CSRF_OK), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.removeMember(BAD, "t", null, CSRF_OK), 400, ChatErrors.BAD_CHANNEL);
        assertError(chatResource.addMember(BAD, "t", CSRF_OK, Map.of()), 400, ChatErrors.BAD_CHANNEL);
        Mockito.verifyNoInteractions(chat);
    }

    @Test
    public void everyMutatingChatEndpointRefusesAMissingCsrfHeader() {
        final String channel = "trip:t1";
        assertError(chatResource.delete(channel, "m1", null), 403, ChatErrors.CSRF);
        assertError(chatResource.edit(channel, "m1", null, Map.of()), 403, ChatErrors.CSRF);
        assertError(chatResource.markRead(channel, null, Map.of()), 403, ChatErrors.CSRF);
        assertError(chatResource.react(channel, "m1", "x", null), 403, ChatErrors.CSRF);
        assertError(chatResource.join(channel, null), 403, ChatErrors.CSRF);
        assertError(chatResource.leave(channel, null), 403, ChatErrors.CSRF);
        assertError(chatResource.savePrefs(channel, null, Map.of()), 403, ChatErrors.CSRF);
        assertError(chatResource.mute(channel, "t", null, Map.of()), 403, ChatErrors.CSRF);
        assertError(chatResource.unmute(channel, "t", null), 403, ChatErrors.CSRF);
        assertError(chatResource.removeMember(channel, "t", null, null), 403, ChatErrors.CSRF);
        assertError(chatResource.addMember(channel, "t", null, Map.of()), 403, ChatErrors.CSRF);
        Mockito.verifyNoInteractions(chat);
    }

    @Test
    public void aSendWithNoBodyAtAllReachesTheBeanAsNulls() {
        Mockito.when(chat.send(ArgumentMatchers.eq("t1"), ArgumentMatchers.eq(ME), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.any(),
                ArgumentMatchers.anyList()))
                .thenReturn(ChatCommands.SendResult.fail("empty", "Message is empty"));

        assertError(chatResource.send("trip:t1", CSRF_OK, ApiMediaTypes.CHAT_V1, null),
                400, ChatErrors.MESSAGE_EMPTY);
    }

    @Test
    public void remainingReactionAndEditErrorsMapToTheirStatuses() {
        Mockito.when(chat.react(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenReturn(ChatCommands.ReactResult.fail("not_found", "gone"))
                .thenReturn(ChatCommands.ReactResult.fail("store", "store broke"))
                .thenReturn(ChatCommands.ReactResult.fail("NOT_AUTHENTICATED", "who?"))
                .thenReturn(ChatCommands.ReactResult.fail("REACTIONS_DISABLED", "off"));

        assertError(chatResource.react("trip:t1", "m1", "x", CSRF_OK), 404, ChatErrors.NOT_FOUND);
        assertError(chatResource.react("trip:t1", "m1", "x", CSRF_OK), 500, ChatErrors.STORE_FAILED);
        assertError(chatResource.react("trip:t1", "m1", "x", CSRF_OK), 401, ChatErrors.NOT_AUTHENTICATED);
        assertError(chatResource.react("trip:t1", "m1", "x", CSRF_OK), 403, ChatErrors.REACTIONS_DISABLED);

        Mockito.when(chat.editMessage(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenReturn(ChatCommands.ReactResult.fail("too_long", "long"))
                .thenReturn(ChatCommands.ReactResult.fail("NOT_AUTHENTICATED", "who?"))
                .thenReturn(ChatCommands.ReactResult.fail("store", "broke"));

        assertError(chatResource.edit("trip:t1", "m1", CSRF_OK, Map.of()), 400, ChatErrors.MESSAGE_TOO_LONG);
        assertError(chatResource.edit("trip:t1", "m1", CSRF_OK, Map.of()), 401, ChatErrors.NOT_AUTHENTICATED);
        assertError(chatResource.edit("trip:t1", "m1", CSRF_OK, Map.of()), 500, ChatErrors.STORE_FAILED);
    }

    @Test
    public void theReactionWindowReturnsSummariesWithNames() {
        Mockito.when(chat.getChannel("t1")).thenReturn(Mockito.mock(ChatChannel.class));
        Mockito.when(chat.readDenial(ArgumentMatchers.any(), ArgumentMatchers.eq(ME))).thenReturn(null);
        final Map<ChatMessage.Id, ChatReactionSummary> summaries =
                Map.of(ChatMessage.Id.from("m1"), Mockito.mock(ChatReactionSummary.class));
        Mockito.when(chat.reactionWindow(ArgumentMatchers.eq("t1"), ArgumentMatchers.eq(ME),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(summaries);
        Mockito.when(chat.reactorNames(summaries)).thenReturn(Map.of("p1", "Ken"));
        Mockito.when(chat.reactionsVersion("t1")).thenReturn(3L);

        final Response response = chatResource.reactions("trip:t1", "m1", "m9");

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("reactionsVersion"), 3L);
        Assert.assertEquals(body.get("displayNames"), Map.of("p1", "Ken"));
    }

    @Test
    public void aDeniedReactionWindowForwardsTheDenial() {
        Mockito.when(chat.getChannel("t1")).thenReturn(Mockito.mock(ChatChannel.class));
        Mockito.when(chat.readDenial(ArgumentMatchers.any(), ArgumentMatchers.eq(ME)))
                .thenReturn(ChatErrors.LEFT_CHANNEL);

        assertError(chatResource.reactions("trip:t1", "m1", "m9"), 403, ChatErrors.LEFT_CHANNEL);
    }

    /** The denial-message table: each denial code renders its own explanation, not a generic shrug. */
    @Test
    public void eachDenialCodeCarriesItsOwnMessage() {
        Mockito.when(chat.chatEnabledForTrip("t1")).thenReturn(true);
        Mockito.when(chat.getChannel("t1")).thenReturn(Mockito.mock(ChatChannel.class));
        Mockito.when(chat.readDenial(ArgumentMatchers.any(), ArgumentMatchers.eq(ME)))
                .thenReturn(ChatErrors.LEFT_CHANNEL)
                .thenReturn(ChatErrors.NOT_A_TRIP_MEMBER)
                .thenReturn(ChatErrors.CHAT_DISABLED)
                .thenReturn("SOMETHING_NEW");

        Assert.assertTrue(feedMessage().contains("rejoin"), "LEFT_CHANNEL should mention rejoining");
        Assert.assertTrue(feedMessage().contains("no longer have access"), "NOT_A_TRIP_MEMBER text");
        Assert.assertTrue(feedMessage().contains("turned off"), "CHAT_DISABLED text");
        Assert.assertEquals(feedMessage(), "Not permitted.", "Unknown codes fall back to the generic message");
    }

    private String feedMessage() {
        final Response out = chatResource.feed("trip:t1", null, null, null, 0, 200, null, null);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) out.getEntity();
        return String.valueOf(body.get("message"));
    }

    // --- BaseResource fallbacks ---

    @Test
    public void personIdFallsBackToTheRawSessionKeyWithoutTheFilter() {
        // No filter attribute; the raw session key holds a Person.Id.
        signedInWithoutTheFilter(ME);
        bindMock(PersonCommands.class);
        final TodosResource todos = resource(new TodosResource());
        bind(org.paulsens.trip.action.TodoCommands.class,
                Mockito.mock(org.paulsens.trip.action.TodoCommands.class));
        Mockito.when(bean(org.paulsens.trip.action.TodoCommands.class).getTodosForUser("t1", ME, false))
                .thenReturn(List.of());

        assertOk(todos.myStatuses("t1"));
    }

    @Test
    public void personIdParsesALegacyStringSessionValue() {
        signedInWithoutTheFilter(ME);
        session(PersonCommands.ACTIVE_USER_ID, ME.getValue()); // a legacy String, not a Person.Id
        bindMock(PersonCommands.class);
        final TodosResource todos = resource(new TodosResource());
        bind(org.paulsens.trip.action.TodoCommands.class,
                Mockito.mock(org.paulsens.trip.action.TodoCommands.class));
        Mockito.when(bean(org.paulsens.trip.action.TodoCommands.class).getTodosForUser("t1", ME, false))
                .thenReturn(List.of());

        assertOk(todos.myStatuses("t1"));
    }

    @Test
    public void findPersonAnswersNullForANullId() {
        Assert.assertNull(BaseResource.findPerson(null));
    }

    @Test
    public void isSiteAdminReadsTheSessionRole() {
        signedInAsSiteAdmin(ME);
        Assert.assertTrue(resource(new TodosResource()).isSiteAdmin());

        signedInAs(ME);
        Assert.assertFalse(resource(new TodosResource()).isSiteAdmin());
    }

    // --- ChatErrors null codes ---

    @Test
    public void nullResultCodesMapToInternalNotAnNpe() {
        Assert.assertEquals(ChatErrors.forSendResult(null), ChatErrors.INTERNAL);
        Assert.assertEquals(ChatErrors.forReactResult(null), ChatErrors.INTERNAL);
        Assert.assertEquals(ChatErrors.forEditResult(null), ChatErrors.INTERNAL);
        Assert.assertEquals(ChatErrors.forSendResult("slow_mode"), ChatErrors.SLOW_MODE);
        Assert.assertEquals(ChatErrors.forSendResult("archived"), ChatErrors.CHANNEL_ARCHIVED);
        Assert.assertEquals(ChatErrors.forSendResult("forbidden"), ChatErrors.FORBIDDEN);
        Assert.assertEquals(ChatErrors.forEditResult("EDIT_DISABLED"), "EDIT_DISABLED");
    }

    // --- TripsResource: the remaining branches ---

    @Test
    public void theTripUpdateAppliesEveryOptionalField() {
        final TripCommands trips = bindMock(TripCommands.class);
        bindMock(PersonCommands.class);
        signedInAsSiteAdmin(ME);
        final TripsResource resource = resource(new TripsResource());
        final Trip existing = Trip.builder().id("t1").people(List.of(ME)).build();
        Mockito.when(trips.getTrip("t1")).thenReturn(existing);
        Mockito.when(trips.saveTrip(existing)).thenReturn(true);

        final LocalDateTime start = LocalDateTime.of(2027, 5, 1, 8, 0);
        final LocalDateTime end = LocalDateTime.of(2027, 5, 10, 20, 0);
        final TripDto body = new TripDto(null, null, "New description", Boolean.TRUE, false, start, end, 25,
                "CFPW", null, null, "$3000", "Fr. Director", "Guide", "Facilitators",
                "https://flyer.example/f.pdf", "https://info.example", null, null, null, null);
        assertOk(resource.update("t1", CSRF_OK, body));

        Assert.assertEquals(existing.getDescription(), "New description");
        Assert.assertEquals(existing.getOpenToPublic(), Boolean.TRUE);
        Assert.assertEquals(existing.getStartDate(), start);
        Assert.assertEquals(existing.getEndDate(), end);
        Assert.assertEquals(existing.getRegLimit(), Integer.valueOf(25));
        Assert.assertEquals(existing.getProvider(), "CFPW");
        Assert.assertEquals(existing.getEstimatedPrice(), "$3000");
        Assert.assertEquals(existing.getDirector(), "Fr. Director");
        Assert.assertEquals(existing.getLocalGuide(), "Guide");
        Assert.assertEquals(existing.getFacilitators(), "Facilitators");
        Assert.assertEquals(existing.getFlyerUrl(), "https://flyer.example/f.pdf");
        Assert.assertEquals(existing.getNonHostedTripUrl(), "https://info.example");
    }

    @Test
    public void theInactiveFilterPassesTheCallerAdminFlagAndCapThrough() {
        final TripCommands trips = bindMock(TripCommands.class);
        bindMock(PersonCommands.class);
        signedInAsSiteAdmin(ME);
        final TripsResource resource = resource(new TripsResource());
        Mockito.when(trips.getInactiveTrips(ArgumentMatchers.eq(ME), ArgumentMatchers.eq(true),
                ArgumentMatchers.anyInt(), ArgumentMatchers.eq(30))).thenReturn(List.of());

        assertOk(resource.list("inactive", 30));

        Mockito.verify(trips).getInactiveTrips(ArgumentMatchers.eq(ME), ArgumentMatchers.eq(true),
                ArgumentMatchers.anyInt(), ArgumentMatchers.eq(30));
    }

    @Test
    public void tripMutationsRefuseAMissingCsrfHeader() {
        bindMock(TripCommands.class);
        bindMock(PersonCommands.class);
        signedInAsSiteAdmin(ME);
        final TripsResource resource = resource(new TripsResource());

        assertError(resource.update("t1", null, null), 403, ApiErrors.CSRF);
        assertError(resource.setParticipation("t1", null, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.saveNote("t1", "e1", null, null, Map.of()), 403, ApiErrors.CSRF);
    }

    @Test
    public void participationAndNotesReport404sAndStoreFailures() {
        final TripCommands trips = bindMock(TripCommands.class);
        bindMock(PersonCommands.class);
        signedInAsSiteAdmin(ME);
        final TripsResource resource = resource(new TripsResource());

        Mockito.when(trips.getTrip("gone")).thenReturn(Trip.builder().build());
        assertError(resource.setParticipation("gone", CSRF_OK, null, Map.of()), 404, ApiErrors.NOT_FOUND);
        assertError(resource.saveNote("gone", "e1", CSRF_OK, null, Map.of()), 404, ApiErrors.NOT_FOUND);

        final Trip trip = Trip.builder().id("t1").people(List.of(ME)).build();
        trip.getTripEvents().add(new org.paulsens.trip.model.TripEvent("e1", null, "T", null,
                LocalDateTime.now(), null, null, null));
        Mockito.when(trips.getTrip("t1")).thenReturn(trip);
        Mockito.when(trips.setEventParticipation(ArgumentMatchers.eq(trip), ArgumentMatchers.anyList(),
                ArgumentMatchers.eq(ME))).thenReturn(false);
        assertError(resource.setParticipation("t1", CSRF_OK, null, Map.of("eventIds", List.of("e1"))),
                500, ApiErrors.STORE_FAILED);
    }

    // --- Caller helpers the resources lean on ---

    @Test
    public void callerOfANullSessionIsAnonymousAndHoldsNothing() {
        final Caller caller = Caller.of((HttpSession) null);

        Assert.assertFalse(caller.isSiteAdmin());
        Assert.assertFalse(caller.has("anything"));
    }
}
