package org.paulsens.trip.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern DASH = Pattern.compile("--|\u2014|\u2013");
    private static final Pattern BANNED = Pattern.compile("(?i)\\b(enables?|enabled|disables?|disabled|configures?"
            + "|configured|select|displayed|users|pilgrims?|pilgrimage)\\b|\\be\\.g\\.|\\bi\\.e\\.|\\betc\\.");
    private static final Pattern SENTENCE_END = Pattern.compile("[.?!](\\s|$)");
    private static final Pattern PARENS = Pattern.compile("\\(([^)]*)\\)");

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
                default -> Assert.fail(def.getName() + " has a type this test does not cover: " + def.getType());
            }
        }
    }

    /**
     * A {@code SettingDef} declared but placed in no {@code SettingSection} is invisible on the Settings page
     * and, when org-overridable, cannot be overridden either -- which is how {@code lodging.bills.autoRecompute}
     * shipped unreachable (fixed 2026-10-25). Reflection over the public constants is the only list that cannot
     * forget one.
     */
    @Test
    public void everyDeclaredSettingIsInASection() throws IllegalAccessException {
        final Set<String> sectioned = new HashSet<>();
        for (final SettingDef def : KnownSettings.all()) {
            sectioned.add(def.getName());
        }
        final List<String> orphans = new ArrayList<>();
        for (final Field field : KnownSettings.class.getFields()) {
            if (field.getType() == SettingDef.class && Modifier.isStatic(field.getModifiers())) {
                final SettingDef def = (SettingDef) field.get(null);
                if (!sectioned.contains(def.getName())) {
                    orphans.add(field.getName() + " (" + def.getName() + ")");
                }
            }
        }
        Assert.assertTrue(orphans.isEmpty(), "Declared but in no SettingSection, so nobody can see or set them: "
                + orphans);
        Assert.assertTrue(sectioned.contains(KnownSettings.LODGING_AUTO_RECOMPUTE.getName()));
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

    /**
     * The site Settings page hides org-only rows and, through {@code hasSiteSettings()}, any section made
     * only of them: Branding is exactly that section, and Site (org name, base domain + the org-only
     * analytics id) must keep rendering.
     */
    @Test
    public void aSectionOfOnlyOrgOnlySettingsHasNoSiteSettings() {
        final SettingSection branding = KnownSettings.sections().stream()
                .filter(section -> KnownSettings.BRANDING_SECTION.equals(section.getTitle()))
                .findFirst().orElseThrow();
        Assert.assertFalse(branding.hasSiteSettings(), "every Branding def is org-only");
        Assert.assertTrue(branding.getSettings().stream().allMatch(SettingDef::isOrgOnly));
        final SettingSection site = KnownSettings.sections().stream()
                .filter(section -> "Site".equals(section.getTitle()))
                .findFirst().orElseThrow();
        Assert.assertTrue(site.hasSiteSettings(), "the Site section still has site-level rows");
        Assert.assertFalse(new SettingSection("Empty", null, List.of()).hasSiteSettings());
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
    public void aMenuSettingDefaultsToBlankOrToOneOfItsOwnChoices() {
        // A default outside the menu could never be re-chosen once changed, and the page could not show it.
        for (final SettingDef def : KnownSettings.all()) {
            if (def.hasChoices()) {
                Assert.assertTrue(def.getDefaultValue().isEmpty() || def.allows(def.getDefaultValue()),
                        def.getName() + " declares a default its own choices refuse: " + def.getDefaultValue());
                Assert.assertEquals(def.getType(), Config.Type.STRING, def.getName() + ": a menu is a STRING");
            }
        }
    }

    @Test
    public void bothChatEmailSwitchesDefaultToOff() {
        // These ship dark on purpose: the code deploys and is exercised before any pilgrim receives mail from it.
        // Flipping either default is a decision, not a tidy-up, so it should break this test.
        Assert.assertFalse(KnownSettings.CHAT_MAIL_ENABLED.booleanDefault());
        Assert.assertFalse(KnownSettings.CHAT_DIGEST_ENABLED.booleanDefault());
    }

    /**
     * Every description is the body of an "i" help card ({@code WEB-INF/fieldHelp.xhtml}) on the site or org
     * settings pages, so it follows the card wording rules (owner, 2026-11-03; the page copy is held to the same
     * rules by the webtests' {@code FieldHelpPolicyIT}). An org-overridable setting is read by organization admins
     * and follows all of them. A site-only one is read by site administrators, so it may run long and keep
     * developer terms (owner's call), but it still ends with a period and carries no dash.
     */
    @Test
    public void everyDescriptionReadsAsAHelpCard() {
        final List<String> problems = new ArrayList<>();
        for (final SettingDef def : KnownSettings.all()) {
            final String text = def.getDescription();
            final List<String> why = def.isOrgOverridable() ? strictCardProblems(text) : looseCardProblems(text);
            if (!why.isEmpty()) {
                problems.add(def.getName() + " " + why);
            }
        }
        Assert.assertTrue(problems.isEmpty(), "Descriptions that break the help-card rules:\n"
                + String.join("\n", problems));
    }

    private static List<String> looseCardProblems(final String text) {
        final List<String> why = new ArrayList<>();
        if (!text.endsWith(".")) {
            why.add("must end with a period");
        }
        if (DASH.matcher(text).find()) {
            why.add("no dashes: use a comma, colon or new sentence");
        }
        return why;
    }

    private static List<String> strictCardProblems(final String text) {
        final List<String> why = looseCardProblems(text);
        final int words = text.trim().split("\\s+").length;
        if (words < 15 || words > 60) {
            why.add(words + " words, want 15 to 60");
        }
        final long sentences = SENTENCE_END.matcher(text).results().count();
        if (sentences < 1 || sentences > 3) {
            why.add(sentences + " sentences, want 1 to 3");
        }
        if (text.contains("!")) {
            why.add("no exclamation marks");
        }
        final Matcher banned = BANNED.matcher(text);
        if (banned.find()) {
            why.add("plain words only, not '" + banned.group() + "'");
        }
        if (text.startsWith("This ") || text.startsWith("Whether ")) {
            why.add("must not open with This or Whether");
        }
        final Matcher parens = PARENS.matcher(text);
        while (parens.find()) {
            if (!parens.group(1).startsWith("for example")) {
                why.add("parentheses only around one example: (" + parens.group(1) + ")");
            }
        }
        return why;
    }
}
