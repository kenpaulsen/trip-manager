package org.paulsens.trip.api;

import org.paulsens.trip.action.ChatCommands;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The {@code Accept} token derivation.
 *
 * <p>Chat negotiated versions with a hardcoded {@code accept.contains("vnd.trip.chat.v1")} before this was
 * generalized onto {@code BaseResource}. If the derivation does not reproduce that string exactly, chat quietly
 * stops recognising its own media type: every response falls back to {@code application/json}, no error is
 * raised anywhere, and the only symptom is that a client can no longer confirm which version it is talking to.
 */
public class ApiMediaTypesTest {

    @Test
    public void chatsTokenIsExactlyWhatItsResourceUsedToHardcode() {
        // The literal that was in ChatResource.negotiatedType() before the lift. It must not move.
        Assert.assertEquals(ApiMediaTypes.token(ApiMediaTypes.CHAT_V1), "vnd.trip.chat.v1");
    }

    @Test
    public void chatsMediaTypeStillComesFromTheBeanTheShippedClientAgreesWith() {
        // chat.xhtml sends this string literally. Aliasing it here must not have changed it.
        Assert.assertEquals(ApiMediaTypes.CHAT_V1, ChatCommands.MEDIA_TYPE_V1);
        Assert.assertEquals(ApiMediaTypes.CHAT_V1, "application/vnd.trip.chat.v1+json");
    }

    @Test
    public void everyAreaGetsADistinctToken() {
        // Two areas sharing a token would make one of them negotiate as the other -- silently, and only for
        // clients that asked for a version, which is the hardest case to notice in testing.
        Assert.assertNotEquals(ApiMediaTypes.token(ApiMediaTypes.AUTH_V1),
                ApiMediaTypes.token(ApiMediaTypes.CHAT_V1));
        Assert.assertEquals(ApiMediaTypes.token(ApiMediaTypes.AUTH_V1), "vnd.trip.auth.v1");
    }

    @Test
    public void aMediaTypeWithoutTheExpectedShapeIsReturnedRatherThanMangled() {
        // Defensive: a caller-supplied or future type that is not application/...+json must not silently
        // become a truncated token that then matches something it should not.
        Assert.assertEquals(ApiMediaTypes.token("text/plain"), "text/plain");
        Assert.assertEquals(ApiMediaTypes.token(null), "");
    }
}
