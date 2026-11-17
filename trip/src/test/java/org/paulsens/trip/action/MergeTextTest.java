package org.paulsens.trip.action;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The mail merge's {@code {{field}}} wording ({@link MergeText}) and the shared engine under it
 * ({@link TokenText}). The merge was EL until medjugorje#79; these pin that nothing in it evaluates anything.
 */
public class MergeTextTest {

    @Test
    public void anExpressionMarkerIsFoundInAnyText() {
        Assert.assertTrue(MergeText.hasExpression("Hi #{to.first}"));
        Assert.assertTrue(MergeText.hasExpression(null, "${7*7}"));
        Assert.assertFalse(MergeText.hasExpression("Hi {{firstName}}", null));
        Assert.assertFalse(MergeText.hasExpression("A # and a { apart"));
        Assert.assertFalse(MergeText.hasExpression("Room #{{room}}", "Deposit: ${{balanceDue}}"),
                "a field typed right after # or $ is a field");
        Assert.assertTrue(MergeText.hasExpression("#{{room}} and #{to.first}"));
    }

    @Test
    public void unknownFieldsAreNamedOnceInOrderAcrossTexts() {
        Assert.assertEquals(MergeText.unknownFields("{{a}} {{firstName}}", "{{ b }} {{a}}", null), List.of("a", "b"));
        Assert.assertTrue(MergeText.unknownFields("{{firstName}} {{ room }}").isEmpty());
    }

    @Test
    public void fieldsAndKindsUsedAreReadFromTheText() {
        final String body = "{{ room }} for {{firstName}}, {{nope}} {{room}}";

        Assert.assertEquals(MergeText.fieldsUsed(body), new LinkedHashSet<>(List.of(MergeText.ROOM, "firstName")));
        Assert.assertEquals(MergeText.kindsUsed(body), EnumSet.of(MergeText.Kind.ON_TRIP, MergeText.Kind.PERSON));
        Assert.assertEquals(MergeText.markupOf(Set.of(MergeText.Kind.ON_TRIP), body), List.of("{{room}}"));
        Assert.assertTrue(MergeText.kindsUsed((String) null).isEmpty());
    }

    /**
     * Each value is escaped and written ONCE: a value holding a field or an expression is just text, never a
     * second round of substitution, and an unknown field is left as typed.
     */
    @Test
    public void theBodyEscapesEachValueAndNeverReadsItAgain() {
        final Map<String, String> values = new HashMap<>();
        values.put(MergeText.FIRST_NAME, "<b>A&B</b>");
        values.put(MergeText.LAST_NAME, "{{firstName}} #{7*7}");

        Assert.assertEquals(MergeText.render("{{firstName}} {{ lastName }} {{unknown}} {{email}}", values),
                "&lt;b&gt;A&amp;B&lt;/b&gt; {{firstName}} #{7*7} {{unknown}} ");
    }

    @Test
    public void theSubjectIsPlainText() {
        Assert.assertEquals(MergeText.renderPlain("{{fullName}} & co", Map.of(MergeText.FULL_NAME, "A & B")),
                "A & B & co");
        Assert.assertEquals(MergeText.renderPlain(null, Map.of()), "");
        Assert.assertEquals(MergeText.renderPlain("{{firstName}}!", null), "!");
    }

    @Test
    public void everyFieldIsListedOnceUnderItsKind() {
        final List<MergeText.Group> groups = MergeText.groups();
        Assert.assertEquals(groups.size(), MergeText.Kind.values().length);
        Assert.assertEquals(groups.stream().mapToInt(group -> group.fields().size()).sum(), MergeText.FIELDS.size());
        for (final MergeText.Group group : groups) {
            Assert.assertFalse(group.label().isBlank());
            group.fields().forEach(field -> Assert.assertEquals(field.getKind().getLabel(), group.label()));
        }
        final MergeText.Field first = MergeText.FIELDS.get(0);
        Assert.assertEquals(first.getMarkup(), "{{firstName}}");
        Assert.assertFalse(first.getHelp().isBlank());
        Assert.assertTrue(MergeText.Kind.TRIP.isNeedsTrip());
        Assert.assertFalse(MergeText.Kind.CONTACT.isNeedsTrip());
    }

    /** A name the reminders also use means the same thing there, so an author can move text between them. */
    @Test
    public void sharedNamesAreTheReminderTokens() {
        final Set<String> reminder = new LinkedHashSet<>();
        ReminderText.TOKENS.forEach(token -> reminder.add(token.getName()));
        for (final MergeText.Field field : MergeText.FIELDS) {
            if (reminder.contains(field.getName())) {
                Assert.assertTrue(ReminderText.unknownTokens(field.getMarkup()).isEmpty(), field.getName());
            }
        }
        Assert.assertTrue(reminder.contains(MergeText.FIRST_NAME));
    }
}
