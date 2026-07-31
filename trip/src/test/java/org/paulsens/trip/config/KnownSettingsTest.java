package org.paulsens.trip.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.SettingSection;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The registry's own invariants.
 *
 * <p>These matter more than they look. A declared default that does not parse as its declared type throws from
 * {@code intDefault()} on the first read of that setting -- which happens mid-request, on whatever page reads it
 * first, long after the mistake was made. Catching it here turns a production 500 into a red build.
 */
public class KnownSettingsTest {

    @Test
    public void everyDeclaredDefaultParsesAsItsDeclaredType() {
        for (final SettingDef def : KnownSettings.all()) {
            switch (def.getType()) {
                case INT -> Assert.assertEquals(String.valueOf(def.intDefault()), def.getDefaultValue(),
                        def.getName() + " declares an INT default that does not round-trip");
                case LONG -> Assert.assertEquals(String.valueOf(def.longDefault()), def.getDefaultValue(),
                        def.getName() + " declares a LONG default that does not round-trip");
                // A BOOLEAN default must be an actual true/false spelling, not merely something
                // Boolean.parseBoolean tolerates: anything else silently means false.
                case BOOLEAN -> Assert.assertTrue(List.of("true", "false").contains(def.getDefaultValue()),
                        def.getName() + " declares a BOOLEAN default that is neither 'true' nor 'false': "
                                + def.getDefaultValue());
                case STRING -> Assert.assertNotNull(def.getDefaultValue(),
                        def.getName() + " must declare a default, using \"\" for none");
            }
        }
    }

    @Test
    public void everySettingHasALabelAndADescription() {
        for (final SettingDef def : KnownSettings.all()) {
            Assert.assertFalse(def.getLabel() == null || def.getLabel().isBlank(),
                    def.getName() + " has no label, so the settings page would show a blank row");
            Assert.assertFalse(def.getDescription() == null || def.getDescription().isBlank(),
                    def.getName() + " has no description; an unexplained setting is one nobody dares change");
        }
    }

    @Test
    public void namesAreUniqueAndAllReachable() {
        final List<String> names = new ArrayList<>();
        for (final SettingSection section : KnownSettings.sections()) {
            section.getSettings().forEach(def -> names.add(def.getName()));
        }
        final Set<String> unique = new HashSet<>(names);
        Assert.assertEquals(unique.size(), names.size(), "A duplicate key would shadow one of the two on the "
                + "page while both call sites kept reading the same row: " + names);
        Assert.assertEquals(KnownSettings.all().size(), names.size());
        names.forEach(name -> Assert.assertTrue(KnownSettings.isKnown(name), name + " is not indexed"));
    }

    @Test
    public void unrecognisedKeysAreNotKnown() {
        // The "Other settings" section depends on this being false for anything the code does not read.
        Assert.assertFalse(KnownSettings.isKnown("some.key.nothing.reads"));
        Assert.assertFalse(KnownSettings.isKnown(null));
        // A near miss must not pass as the real thing -- the whole point of the registry.
        Assert.assertFalse(KnownSettings.isKnown("chat.digest.enable"));
        Assert.assertTrue(KnownSettings.isKnown("chat.digest.enabled"));
    }

    @Test
    public void theTypeMatchesHowTheSettingIsRead() {
        // Spot-checks tying a declaration to the accessor its call site uses. A BOOLEAN read with getInt would
        // silently return the caller's default forever.
        Assert.assertEquals(KnownSettings.CHAT_MAIL_ENABLED.getType(), Config.Type.BOOLEAN);
        Assert.assertEquals(KnownSettings.CHAT_DIGEST_HOUR.getType(), Config.Type.INT);
        Assert.assertEquals(KnownSettings.CHAT_MAIL_FROM.getType(), Config.Type.STRING);
    }

    @Test
    public void bothChatEmailSwitchesDefaultToOff() {
        // These ship dark on purpose: the code deploys and is exercised before any pilgrim receives mail from it.
        // Flipping either default is a decision, not a tidy-up, so it should break this test.
        Assert.assertFalse(KnownSettings.CHAT_MAIL_ENABLED.booleanDefault());
        Assert.assertFalse(KnownSettings.CHAT_DIGEST_ENABLED.booleanDefault());
    }
}
