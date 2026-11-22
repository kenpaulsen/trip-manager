package org.paulsens.trip.model;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import org.paulsens.trip.config.KnownSettings;
import org.testng.annotations.Test;

/**
 * The {@code labels} bundle against the code that reads it. Enum names and setting words are looked up by a
 * key BUILT from the constant or the setting's name, which {@code BundlePolicyTest} cannot see (it reads literal
 * keys), and a missing key renders {@code ???key???} instead of failing: only this test notices a constant or
 * a setting added without its line.
 */
public class LabelsBundleTest {

    private static final String MISSING = "???";

    @Test
    public void everyEnumConstantHasItsDisplayName() {
        final List<String> missing = new ArrayList<>();
        check(missing, Status.StatusValue.class, Status.StatusValue::getDisplayValue);
        check(missing, Status.Priority.class, Status.Priority::getDisplayValue);
        check(missing, BudgetCategory.class, BudgetCategory::getLabel);
        check(missing, ExpenseCategory.class, ExpenseCategory::getLabel);
        check(missing, TripReminder.Kind.class, TripReminder.Kind::getLabel);
        check(missing, TripReminder.Anchor.class, TripReminder.Anchor::getLabel);
        check(missing, TripReminder.Audience.class, TripReminder.Audience::getLabel);
        check(missing, RegistrationOption.QuestionType.class, RegistrationOption.QuestionType::getDisplayValue);
        check(missing, TripEvent.Type.class, TripEvent.Type::getDisplayValue);
        check(missing, TripKind.class, TripKind::getDisplayValue);
        check(missing, TripKind.class, TripKind::getRegisterLabel);
        check(missing, TripKind.class, TripKind::getPeopleNoun);
        check(missing, ProcessorType.class, ProcessorType::getDisplayLabel);
        check(missing, ReminderSend.Outcome.class, ReminderSend.Outcome::getLabel);
        check(missing, ExpensePaidStatus.class, ExpensePaidStatus::getLabel);
        check(missing, Registration.Status.class, Registration.Status::getLabel);
        check(missing, AdvanceType.class, AdvanceType::getLabel);
        check(missing, ExpenseStatus.class, ExpenseStatus::getLabel);
        assertTrue(missing.isEmpty(), "enum constants with no line in labels.properties: " + missing);
    }

    @Test
    public void theEnglishNamesAreTheOnesTheCodeUsedToCarry() {
        assertEquals(Status.StatusValue.IN_PROGRESS.getDisplayValue(), "In Progress");
        assertEquals(TripEvent.Type.GROUND.getDisplayValue(), "Bus, Van, Car");
        assertEquals(TripReminder.Anchor.BALANCE_DUE_DATE.getLabel(), "the balance due date");
        assertEquals(AdvanceType.PARTICIPANT.getLabel(), "Cash given by participant(s)");
        assertEquals(ExpenseCategory.SCHOLARSHIP.getLabel(), BudgetCategory.SCHOLARSHIP.getLabel(),
                "a category the budget also has reads the budget's word");
        assertEquals(ExpenseCategory.MEALS.getLabel(), "Meals");
        assertEquals(ExpensePaidStatus.ORG.getChip(), "Org paid");
        assertNull(ExpensePaidStatus.NOT_PAID.getChip());
    }

    @Test
    public void theStoredEnglishOfAStatusAndAProcessorIsNotTheBundles() {
        // These two are written to storage and compared by code, so they stay literals in the source.
        assertEquals(Registration.Status.NOT_REGISTERED.getDescription(), "Not Registered");
        assertEquals(Registration.Status.fromDescription("Pending"), Registration.Status.PENDING);
        assertEquals(ProcessorType.OFFLINE.getLabel(), "Offline payment");
        assertEquals(ProcessorType.OFFLINE.getDisplayLabel(), "Offline payment");
    }

    @Test
    public void everyScheduleSentenceExists() {
        for (final TripReminder.Anchor anchor : TripReminder.Anchor.values()) {
            for (final int offset : new int[] {-1000, -2, -1, 0, 1, 14}) {
                final String text = TripReminder.builder().anchor(anchor).offsetDays(offset).build()
                        .getScheduleText();
                assertFalse(text.contains(MISSING), anchor + " at " + offset + " days reads " + text);
            }
        }
        assertEquals(TripReminder.builder().anchor(TripReminder.Anchor.TRIP_END).offsetDays(-1000).build()
                .getScheduleText(), "1000 days before the trip end", "a count is plain digits, as it always was");
        assertEquals(TripReminder.builder().anchor(TripReminder.Anchor.BALANCE_DUE_DATE).offsetDays(1).build()
                .getScheduleText(), "1 day after the balance due date");
    }

    @Test
    public void everyDeclaredSettingAndSectionHasItsWords() {
        final List<String> missing = new ArrayList<>();
        for (final SettingSection section : KnownSettings.sections()) {
            if (section.getDisplayTitle().contains(MISSING) || !bundle().containsKey(sectionKey(section))) {
                missing.add("section " + section.getTitle());
            }
            for (final SettingDef def : section.getSettings()) {
                if (def.getLabel().contains(MISSING) || def.getDescription().contains(MISSING)) {
                    missing.add(def.getName());
                }
            }
        }
        assertTrue(missing.isEmpty(), "settings or sections with no words in labels.properties: " + missing);
        assertEquals(KnownSettings.SITE_LANGUAGES.getLabel(), "Languages offered");
        assertEquals(KnownSettings.sections().get(0).getDisplayTitle(), "Site");
        assertNull(KnownSettings.sections().get(0).getNote(), "the Site section has no note");
        assertTrue(KnownSettings.sections().get(1).getNote().startsWith("The look of an ORGANIZATION's own site"));
    }

    @Test
    public void theBundleDescribesNoSettingTheCodeDoesNotDeclare() {
        final List<String> stale = new ArrayList<>();
        for (final String key : bundle().stringPropertyNames()) {
            if (key.startsWith("labels.setting.") && !KnownSettings.isKnown(settingOf(key))) {
                stale.add(key);
            }
        }
        assertTrue(stale.isEmpty(), "labels.properties describes settings that no longer exist: " + stale);
    }

    @Test
    public void aDefinitionOutsideTheRegistryKeepsItsOwnWords() {
        final SettingDef adHoc = new SettingDef("x.key", Config.Type.INT, "42", "The label", "The description");
        assertEquals(adHoc.getLabel(), "The label");
        assertEquals(adHoc.getDescription(), "The description");
        final SettingSection section = new SettingSection("Nowhere", "Its own note", List.of(adHoc));
        assertEquals(section.getDisplayTitle(), "Nowhere");
        assertEquals(section.getNote(), "Its own note");
        assertNull(new SettingSection("Nowhere", List.of()).getNote());
    }

    private static <E extends Enum<E>> void check(final List<String> missing, final Class<E> type,
            final Function<E, String> label) {
        for (final E constant : type.getEnumConstants()) {
            final String text = label.apply(constant);
            if (text == null || text.isBlank() || text.contains(MISSING)) {
                missing.add(type.getSimpleName() + "." + constant.name() + " (" + text + ")");
            }
        }
    }

    private static String sectionKey(final SettingSection section) {
        // Mirrors SettingSection's own key: a heading that fell back to the raw title would pass unnoticed.
        final StringBuilder id = new StringBuilder();
        for (final String word : section.getTitle().split("[^A-Za-z0-9]+")) {
            id.append(id.isEmpty() ? word.substring(0, 1).toLowerCase(java.util.Locale.ROOT)
                    : word.substring(0, 1).toUpperCase(java.util.Locale.ROOT)).append(word.substring(1));
        }
        return "labels.settingSection." + id + ".title";
    }

    /** {@code labels.setting.site.languages.label} names the setting {@code site.languages}. */
    private static String settingOf(final String key) {
        return key.substring("labels.setting.".length(), key.lastIndexOf('.'));
    }

    private static Properties bundle() {
        final Properties properties = new Properties();
        try (InputStream in = LabelsBundleTest.class.getClassLoader().getResourceAsStream("i18n/labels.properties");
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return properties;
    }
}
