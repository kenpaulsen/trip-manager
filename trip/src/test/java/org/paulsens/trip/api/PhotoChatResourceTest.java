package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.PhotoChatCommands;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.PhotoChatMeta;
import org.paulsens.trip.web.Sessions;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The photo-chat mapping layer: which bean answer becomes which status. The distinctive rules pinned here are
 * the ones a regression would break silently — anonymous READS are allowed (this resource is deliberately not
 * behind the auth filter), anonymous MUTATIONS are 401 JSON (never a redirect or an HTML error page), a rate
 * limit is a retryable 429 with Retry-After, and login-return only accepts a same-site path.
 */
public class PhotoChatResourceTest extends ResourceTestSupport {

    private static final String KEY = "chat/trip-x/20260809-a.jpg";
    private static final Person.Id ME = Person.Id.from("photo-rest-me");

    private PhotoChatCommands photoChat;
    private MockedStatic<PhotoChatCommands> photoStatic;
    private PhotoChatResource resource;

    @BeforeMethod
    public void bindPhotoChat() {
        photoChat = Mockito.mock(PhotoChatCommands.class);
        photoStatic = Mockito.mockStatic(PhotoChatCommands.class);
        photoStatic.when(PhotoChatCommands::getPhotoChatCommands).thenReturn(photoChat);
        photoStatic.when(() -> PhotoChatCommands.tripIdOfKey(ArgumentMatchers.anyString()))
                .thenCallRealMethod();
        resource = resource(new PhotoChatResource());
    }

    @AfterMethod(alwaysRun = true)
    public void closePhotoStatic() {
        if (photoStatic != null) {
            photoStatic.close();
            photoStatic = null;
        }
    }

    // --- reads are anonymous-tolerant ---

    @Test
    public void anAnonymousReaderGetsTheThread() {
        anonymous();
        Mockito.when(photoChat.readDenialFor(ArgumentMatchers.eq(KEY), ArgumentMatchers.any()))
                .thenReturn(null);
        Mockito.when(photoChat.thread(KEY, null, 0)).thenReturn(emptyPage());
        Mockito.when(photoChat.rootSummary(KEY))
                .thenReturn(ChatReactionSummary.empty(PhotoChatMeta.PHOTO_ROOT));
        Mockito.when(photoChat.canSeeIdentities(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(false);

        final Response response = resource.thread(KEY, null, 0);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertNotNull(body.get("messages"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> photo = (Map<String, Object>) body.get("photo");
        Assert.assertFalse(photo.containsKey("who"),
                "reactor identities are member-level detail; anonymous gets counts only");
    }

    @Test
    public void aMemberGetsReactorIdentities() {
        signedInAs(ME);
        Mockito.when(photoChat.readDenialFor(ArgumentMatchers.eq(KEY), ArgumentMatchers.any()))
                .thenReturn(null);
        Mockito.when(photoChat.thread(KEY, null, 0)).thenReturn(emptyPage());
        Mockito.when(photoChat.rootSummary(KEY)).thenReturn(new ChatReactionSummary(
                PhotoChatMeta.PHOTO_ROOT, Map.of("👍", List.of(ME)), Map.of(), Map.of()));
        Mockito.when(photoChat.canSeeIdentities(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(true);
        Mockito.when(photoChat.reactorNames(ArgumentMatchers.any()))
                .thenReturn(Map.of(ME.getValue(), "Me Myself"));

        final Response response = resource.thread(KEY, null, 0);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> photo =
                (Map<String, Object>) ((Map<String, Object>) response.getEntity()).get("photo");
        Assert.assertEquals(photo.get("who"), Map.of("👍", List.of("Me Myself")));
        Assert.assertEquals(photo.get("myReacted"), List.of("👍"));
    }

    @Test
    public void aDeniedOrUnknownPhotoIs404() {
        anonymous();
        Mockito.when(photoChat.readDenialFor(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn("NOT_FOUND");
        assertError(resource.thread(KEY, null, 0), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void metaCapsTheBatchAndAnswersPerKey() {
        anonymous();
        Mockito.when(photoChat.batchMeta(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Map.of(KEY, new PhotoChatMeta(2, null)));

        final Response ok = resource.meta(Map.of("keys", List.of(KEY)));
        assertOk(ok);
        @SuppressWarnings("unchecked")
        final Map<String, Object> photos =
                (Map<String, Object>) ((Map<String, Object>) ok.getEntity()).get("photos");
        @SuppressWarnings("unchecked")
        final Map<String, Object> one = (Map<String, Object>) photos.get(KEY);
        Assert.assertEquals(one.get("commentCount"), 2);

        final List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= PhotoChatCommands.MAX_META_KEYS; i++) {
            tooMany.add("chat/t/" + i + ".jpg");
        }
        assertError(resource.meta(Map.of("keys", tooMany)), 400, ApiErrors.BAD_REQUEST);
        assertOk(resource.meta(null));
    }

    @Test
    public void theEmojiPaletteIsOpen() {
        anonymous();
        Mockito.when(photoChat.palette()).thenReturn(List.of("👍"));
        assertOk(resource.emoji());
    }

    // --- mutations require session + CSRF ---

    @Test
    public void anAnonymousCommentIsA401JsonBody() {
        anonymous();
        assertError(resource.comment(CSRF_OK, Map.of("key", KEY, "body", "hi")),
                401, ChatErrors.NOT_AUTHENTICATED);
    }

    @Test
    public void aMissingCsrfHeaderRefusesEveryMutation() {
        signedInAs(ME);
        assertError(resource.comment(null, Map.of()), 403, ChatErrors.CSRF);
        assertError(resource.deleteComment("0000000000001", KEY, null), 403, ChatErrors.CSRF);
        assertError(resource.react("👍", KEY, null), 403, ChatErrors.CSRF);
        assertError(resource.unreact("👍", KEY, ""), 403, ChatErrors.CSRF);
        assertError(resource.loginReturn(null, Map.of("target", "/x")), 403, ChatErrors.CSRF);
    }

    @Test
    public void commentOutcomesMapToTheRightStatuses() {
        signedInAs(ME);
        stubComment(ChatCommands.SendResult.fail("empty", "Comment cannot be empty."));
        assertError(comment("hi"), 400, ChatErrors.MESSAGE_EMPTY);
        stubComment(ChatCommands.SendResult.fail("too_long", "too long"));
        assertError(comment("hi"), 400, ChatErrors.MESSAGE_TOO_LONG);
        stubComment(ChatCommands.SendResult.fail("not_found", "no photo"));
        assertError(comment("hi"), 404, ApiErrors.NOT_FOUND);
        stubComment(ChatCommands.SendResult.fail("disabled", "off"));
        assertError(comment("hi"), 403, ChatErrors.PHOTO_COMMENTS_DISABLED);
        stubComment(ChatCommands.SendResult.fail("store", "boom"));
        assertError(comment("hi"), 500, ChatErrors.STORE_FAILED);
    }

    @Test
    public void aRateLimitIsARetryable429WithRetryAfter() {
        signedInAs(ME);
        stubComment(ChatCommands.SendResult.rateLimited(ChatRateLimiter.Decision.deny("burst", 7)));

        final Response response = comment("hi");

        Assert.assertEquals(response.getStatus(), 429);
        Assert.assertEquals(response.getHeaderString("Retry-After"), "7",
                "the client shows this countdown and keeps the user's text");
    }

    @Test
    public void aSuccessfulCommentEchoesTheStoredMessage() {
        signedInAs(ME);
        final ChatMessage stored = new ChatMessage(ChatMessage.Id.of(9L), null, ME, Instant.now(),
                null, "hi", null, null, null, null, null, null, null, null, null);
        stubComment(ChatCommands.SendResult.ok(stored));

        final Response response = comment("hi");

        assertOk(response);
        Assert.assertSame(response.getEntity(), stored);
    }

    @Test
    public void reactReturnsFreshCountsForTheOptimisticClient() {
        signedInAs(ME);
        Mockito.when(photoChat.react(ArgumentMatchers.eq(KEY), ArgumentMatchers.eq(ME),
                        ArgumentMatchers.eq("👍"), ArgumentMatchers.eq(true), ArgumentMatchers.any()))
                .thenReturn(ChatCommands.ReactResult.success());
        Mockito.when(photoChat.batchMeta(ArgumentMatchers.eq(List.of(KEY)), ArgumentMatchers.any()))
                .thenReturn(Map.of(KEY, new PhotoChatMeta(0, new ChatReactionSummary(
                        PhotoChatMeta.PHOTO_ROOT, Map.of("👍", List.of(ME)), Map.of(), Map.of()))));

        final Response response = resource.react("👍", KEY, CSRF_OK);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("reacted"), true);
        Assert.assertEquals(body.get("reactions"), Map.of("👍", 1));
        Assert.assertEquals(body.get("myReacted"), List.of("👍"));
    }

    @Test
    public void reactFailuresMap() {
        signedInAs(ME);
        stubReact(ChatCommands.ReactResult.fail("bad_emoji", "no"));
        assertError(resource.react("💣", KEY, CSRF_OK), 400, ChatErrors.BAD_EMOJI);
        stubReact(ChatCommands.ReactResult.fail("not_found", "no"));
        assertError(resource.react("👍", KEY, CSRF_OK), 404, ApiErrors.NOT_FOUND);
        stubReact(ChatCommands.ReactResult.fail("disabled", "off"));
        assertError(resource.react("👍", KEY, CSRF_OK), 403, ChatErrors.PHOTO_COMMENTS_DISABLED);
        stubReact(ChatCommands.ReactResult.fail("store", "boom"));
        assertError(resource.unreact("👍", KEY, CSRF_OK), 500, ChatErrors.STORE_FAILED);
    }

    @Test
    public void deleteMapsRefusalTo403() {
        signedInAs(ME);
        Mockito.when(photoChat.deleteComment(ArgumentMatchers.eq(KEY), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(false);
        assertError(resource.deleteComment("0000000000001", KEY, CSRF_OK), 403, ChatErrors.FORBIDDEN);

        Mockito.when(photoChat.deleteComment(ArgumentMatchers.eq(KEY), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(true);
        assertOk(resource.deleteComment("0000000000001", KEY, CSRF_OK));
    }

    // --- login-return ---

    @Test
    public void loginReturnStashesOnlyASameSitePath() {
        anonymous();
        Mockito.when(request.getSession(true)).thenReturn(session);

        assertOk(resource.loginReturn(CSRF_OK, Map.of("target", "/trip/index.jsf?photo=chat%2Ft%2Fa.jpg")));
        Mockito.verify(session).setAttribute(
                Sessions.AFTER_LOGIN_URL, "/trip/index.jsf?photo=chat%2Ft%2Fa.jpg");

        assertError(resource.loginReturn(CSRF_OK, Map.of("target", "//evil.example")),
                400, ApiErrors.BAD_REQUEST);
        assertError(resource.loginReturn(CSRF_OK, Map.of("target", "https://evil.example/x")),
                400, ApiErrors.BAD_REQUEST);
        assertError(resource.loginReturn(CSRF_OK, Map.of("target", "/x\r\nSet-Cookie: a=b")),
                400, ApiErrors.BAD_REQUEST);
        assertError(resource.loginReturn(CSRF_OK, Map.of()), 400, ApiErrors.BAD_REQUEST);
    }

    // --- mention search ---

    @Test
    public void mentionSearchIsSignedInOnlyShortQueriesAre400AndTheBrakeIsA429() {
        anonymous();
        assertError(resource.mentionSearch("pat"), 401, ChatErrors.NOT_AUTHENTICATED);

        signedInAs(ME);
        final PhotoChatResource fresh = resource(new PhotoChatResource());
        assertError(fresh.mentionSearch("p"), 400, ApiErrors.BAD_REQUEST);
        assertError(fresh.mentionSearch(null), 400, ApiErrors.BAD_REQUEST);

        Mockito.when(photoChat.mentionSearchAllowed(ME)).thenReturn(false);
        assertError(fresh.mentionSearch("pat"), 429, ChatErrors.RATE_LIMITED);

        Mockito.when(photoChat.mentionSearchAllowed(ME)).thenReturn(true);
        Mockito.when(photoChat.mentionSearch("pat", 8))
                .thenReturn(List.of(Map.of("id", "p1", "label", "Pat")));
        final Response ok = fresh.mentionSearch("pat");
        assertOk(ok);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) ok.getEntity();
        Assert.assertEquals(body.get("people"), List.of(Map.of("id", "p1", "label", "Pat")));
    }

    // --- helpers ---

    private Response comment(final String text) {
        return resource.comment(CSRF_OK, Map.of("key", KEY, "body", text));
    }

    private void stubComment(final ChatCommands.SendResult result) {
        Mockito.when(photoChat.comment(ArgumentMatchers.eq(KEY), ArgumentMatchers.eq(ME),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(result);
    }

    private void stubReact(final ChatCommands.ReactResult result) {
        Mockito.when(photoChat.react(ArgumentMatchers.eq(KEY), ArgumentMatchers.eq(ME),
                ArgumentMatchers.any(), ArgumentMatchers.anyBoolean(), ArgumentMatchers.any()))
                .thenReturn(result);
    }

    private static ChatPage emptyPage() {
        return new ChatPage(List.of(), Map.of(), null, 0L, 0L, false, true, Map.of(), Instant.now());
    }
}
