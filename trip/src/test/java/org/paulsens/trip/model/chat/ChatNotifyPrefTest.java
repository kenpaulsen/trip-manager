package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.paulsens.trip.model.chat.ChatNotifyPref.DeliveryMode;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The notification defaults and the migration off the old single mode.
 *
 * <p>Two things are worth pinning. The <b>direction of each default</b> is a product decision — mentions on,
 * digest off — and a refactor that flipped either would show up as silence or as a mass mailing, neither of which
 * announces itself. And the <b>legacy read path</b> is the only thing standing between existing membership rows
 * and a silent preference reset: those rows carry {@code email} as an enum and nothing else.
 */
public class ChatNotifyPrefTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    public void mentionsDefaultOnAndDigestDefaultsOff() {
        final ChatNotifyPref defaults = ChatNotifyPref.defaults();
        Assert.assertTrue(defaults.isMentionEmail(), "being named is worth an interruption");
        Assert.assertFalse(defaults.isDailyDigest(), "a daily rollup is a positive opt-in");
        Assert.assertTrue(defaults.isInApp());
        Assert.assertEquals(defaults.getPush(), DeliveryMode.OFF, "push stays off until APNs exists");
    }

    @Test
    public void anEmptyRowTakesTheDefaults() {
        final ChatNotifyPref pref = new ChatNotifyPref(null, null, null, null, null, null, null, null);
        Assert.assertTrue(pref.isMentionEmail());
        Assert.assertFalse(pref.isDailyDigest());
    }

    @Test
    public void aRowWrittenBeforeTheSplitKeepsWhatItMeant() throws Exception {
        // These are the shapes actually sitting in chat_members today. Read them wrong and someone who opted into
        // a digest stops getting one, or someone who opted out of mail starts getting it.
        Assert.assertFalse(legacy("OFF").isMentionEmail(), "OFF meant no mail at all");
        Assert.assertFalse(legacy("OFF").isDailyDigest());

        Assert.assertTrue(legacy("MENTIONS").isMentionEmail());
        Assert.assertFalse(legacy("MENTIONS").isDailyDigest());

        Assert.assertTrue(legacy("DIGEST_DAILY").isDailyDigest(), "an opted-in digest must survive the change");
        Assert.assertFalse(legacy("DIGEST_DAILY").isMentionEmail(), "the old mode was exclusive; it chose digest");

        // Hourly is gone as an option, but a row holding it still wanted a summary.
        Assert.assertTrue(legacy("DIGEST_HOURLY").isDailyDigest());

        // ALL is gone too. Mentions is the nearest surviving intent -- never "every message", which no longer exists.
        Assert.assertTrue(legacy("ALL").isMentionEmail());
        Assert.assertFalse(legacy("ALL").isDailyDigest());
    }

    @Test
    public void aStoredChoiceBeatsTheLegacyField() throws Exception {
        // Once someone uses the new toggles their row carries both, and the stale enum must not override them.
        final ChatNotifyPref pref = MAPPER.readValue(
                "{\"email\":\"OFF\",\"mentionEmail\":true,\"dailyDigest\":true}", ChatNotifyPref.class);
        Assert.assertTrue(pref.isMentionEmail());
        Assert.assertTrue(pref.isDailyDigest());
    }

    @Test
    public void theTogglesSurviveARoundTrip() throws Exception {
        final ChatNotifyPref off = ChatNotifyPref.defaults().withEmail(false, true);
        final ChatNotifyPref reread = MAPPER.readValue(MAPPER.writeValueAsString(off), ChatNotifyPref.class);
        Assert.assertFalse(reread.isMentionEmail(), "an explicit off must not spring back to the default");
        Assert.assertTrue(reread.isDailyDigest());
    }

    @Test
    public void anyEmailIsTrueWhenEitherIs() {
        Assert.assertFalse(ChatNotifyPref.defaults().withEmail(false, false).isAnyEmail());
        Assert.assertTrue(ChatNotifyPref.defaults().withEmail(true, false).isAnyEmail());
        Assert.assertTrue(ChatNotifyPref.defaults().withEmail(false, true).isAnyEmail());
    }

    private static ChatNotifyPref legacy(final String mode) throws Exception {
        return MAPPER.readValue("{\"inApp\":true,\"email\":\"" + mode + "\"}", ChatNotifyPref.class);
    }
}
