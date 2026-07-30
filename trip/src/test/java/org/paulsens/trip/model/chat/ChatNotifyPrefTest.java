package org.paulsens.trip.model.chat;

import org.paulsens.trip.model.chat.ChatNotifyPref.DeliveryMode;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The notification defaults, which are a product decision rather than an implementation detail.
 *
 * <p>Email defaults to {@code MENTIONS} and digests do not, and both halves matter: being named in a trip's chat
 * is worth an interruption, a daily rollup nobody asked for is not. These assertions exist so a later refactor
 * cannot quietly flip either one — the symptom would be silence (nobody gets mentioned) or a mass mailing.
 */
public class ChatNotifyPrefTest {

    @Test
    public void emailDefaultsToMentions() {
        Assert.assertEquals(ChatNotifyPref.defaults().getEmail(), DeliveryMode.MENTIONS);
    }

    @Test
    public void aNullEmailModeBecomesMentions() {
        // This is the path that matters for rows already in DynamoDB, which were written before the field
        // existed or with an explicit null -- and for implicit members, who have no row at all.
        final ChatNotifyPref pref = new ChatNotifyPref(null, null, null, null, null, null);
        Assert.assertEquals(pref.getEmail(), DeliveryMode.MENTIONS);
    }

    @Test
    public void anExplicitOffIsHonoured() {
        // Someone who turned it off must stay off; the default only fills a null.
        final ChatNotifyPref pref = new ChatNotifyPref(true, DeliveryMode.OFF, null, null, null, null);
        Assert.assertEquals(pref.getEmail(), DeliveryMode.OFF);
    }

    @Test
    public void digestIsNeverTheDefault() {
        Assert.assertNotEquals(ChatNotifyPref.defaults().getEmail(), DeliveryMode.DIGEST_DAILY);
        Assert.assertNotEquals(ChatNotifyPref.defaults().getEmail(), DeliveryMode.DIGEST_HOURLY);
    }

    @Test
    public void pushStaysOffUntilItExists() {
        Assert.assertEquals(ChatNotifyPref.defaults().getPush(), DeliveryMode.OFF);
    }

    @Test
    public void inAppDefaultsOn() {
        Assert.assertTrue(ChatNotifyPref.defaults().isInApp());
        Assert.assertTrue(new ChatNotifyPref(null, null, null, null, null, null).isInApp());
    }
}
