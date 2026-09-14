package org.paulsens.trip.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The two wire renderings of one payload: the APNs JSON the app parses and the JSON the service worker shows. */
public class PushPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PushPayload mention() {
        return PushPayload.alert(PushPayload.KIND_CHAT_MENTION, "Autumn Pilgrimage", "Maria mentioned you",
                "bus leaves at 7", "trip:t1", "unitetrip://chat/t1", "https://acme.example/trip/chat.jsf?trip=t1")
                .withChat("trip:t1", "m1");
    }

    @Test
    public void anApnsAlertCarriesTheContractKeys() throws Exception {
        final JsonNode root = MAPPER.readTree(mention().withBadge(2).toApns());
        final JsonNode aps = root.get("aps");
        Assert.assertEquals(aps.get("alert").get("title").asText(), "Autumn Pilgrimage");
        Assert.assertEquals(aps.get("alert").get("subtitle").asText(), "Maria mentioned you");
        Assert.assertEquals(aps.get("alert").get("body").asText(), "bus leaves at 7");
        Assert.assertEquals(aps.get("badge").asInt(), 2);
        Assert.assertEquals(aps.get("sound").asText(), "default");
        Assert.assertEquals(aps.get("thread-id").asText(), "trip:t1");
        Assert.assertEquals(aps.get("interruption-level").asText(), "active");
        Assert.assertNull(aps.get("mutable-content"), "no image, nothing to mutate");
        Assert.assertEquals(root.get("kind").asText(), "chat.mention");
        Assert.assertEquals(root.get("link").asText(), "unitetrip://chat/t1");
        Assert.assertEquals(root.get("url").asText(), "https://acme.example/trip/chat.jsf?trip=t1");
        Assert.assertEquals(root.get("channelId").asText(), "trip:t1");
        Assert.assertEquals(root.get("messageId").asText(), "m1");
        Assert.assertNull(root.get("image"));
    }

    @Test
    public void quietHoursMakeItPassiveAndAnImageMakesItMutable() throws Exception {
        final JsonNode root = MAPPER.readTree(mention().withPassive(true).withImage("https://f/x-small.jpg").toApns());
        final JsonNode aps = root.get("aps");
        Assert.assertNull(aps.get("sound"), "quiet hours: no sound");
        Assert.assertEquals(aps.get("interruption-level").asText(), "passive");
        Assert.assertEquals(aps.get("mutable-content").asInt(), 1);
        Assert.assertEquals(root.get("image").asText(), "https://f/x-small.jpg");
        Assert.assertNull(aps.get("badge"), "no badge unless the sender set one");
    }

    @Test
    public void theSilentPushIsContentAvailableAndNothingElse() throws Exception {
        final PushPayload silent = PushPayload.silent();
        Assert.assertTrue(silent.isSilent());
        Assert.assertEquals(silent.getCollapseId(), "refresh");
        Assert.assertEquals(silent.toApns(), "{\"aps\":{\"content-available\":1}}");
        Assert.assertFalse(mention().isSilent());
        Assert.assertNull(MAPPER.readTree(silent.toApns()).get("kind"));
    }

    @Test
    public void theWebPushBodyLeadsWithTheSubtitle() throws Exception {
        final JsonNode root = MAPPER.readTree(mention().withIcon("https://acme.example/logo.png")
                .withImage("https://f/x.jpg").toWebPush());
        Assert.assertEquals(root.get("title").asText(), "Autumn Pilgrimage");
        Assert.assertEquals(root.get("body").asText(), "Maria mentioned you: bus leaves at 7");
        Assert.assertEquals(root.get("icon").asText(), "https://acme.example/logo.png");
        Assert.assertEquals(root.get("image").asText(), "https://f/x.jpg");
        Assert.assertEquals(root.get("url").asText(), "https://acme.example/trip/chat.jsf?trip=t1");
        Assert.assertEquals(root.get("tag").asText(), "trip:t1");
        Assert.assertEquals(root.get("kind").asText(), "chat.mention");

        Assert.assertEquals(PushPayload.alert("k", null, "Sub", null, null, null, null).webBody(), "Sub");
        Assert.assertEquals(PushPayload.alert("k", null, null, "Body", null, null, null).webBody(), "Body");
        Assert.assertEquals(PushPayload.alert("k", null, " ", null, null, null, null).webBody(), "");
        final JsonNode bare = MAPPER.readTree(PushPayload.alert("k", null, null, null, null, null, null).toWebPush());
        Assert.assertEquals(bare.get("title").asText(), "");
        Assert.assertNull(bare.get("url"));
    }

    @Test
    public void copiesReplaceOneFieldAndKeepTheRest() {
        final PushPayload base = mention();
        Assert.assertEquals(base.withBadge(3).getBadge(), Integer.valueOf(3));
        Assert.assertEquals(base.withBadge(3).getBody(), base.getBody());
        Assert.assertTrue(base.withPassive(true).isPassive());
        Assert.assertEquals(base.withPassive(true).getMessageId(), "m1");
        Assert.assertEquals(base.withIcon("i").getIconUrl(), "i");
        Assert.assertEquals(base.withImage("x").withIcon("i").getImageUrl(), "x");
    }
}
