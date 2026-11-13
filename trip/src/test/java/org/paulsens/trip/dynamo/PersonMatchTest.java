package org.paulsens.trip.dynamo;

import java.util.List;
import java.util.Set;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** The one people-search rule: names and cell digits match anywhere, email only from its start. */
public class PersonMatchTest {
    private static final PersonMatch.Reach ALL = PersonMatch.EVERY_FIELD;
    private final Person ken = Person.builder().first("Ken").last("Paulsen").nickname("Kenny")
            .email("ken.paulsen@example.com").cell("(555) 867-5309").build();

    @Test
    public void namesMatchAnywhereInside() {
        assertTrue(PersonMatch.matchesQuery(ken, "ulse", ALL), "inside the last name");
        assertTrue(PersonMatch.matchesQuery(ken, "SEN", ALL), "case does not matter");
        assertTrue(PersonMatch.matchesQuery(ken, "nny", ALL), "inside the nickname");
        assertTrue(PersonMatch.matchesQuery(ken, "en paul", ALL), "every word, each anywhere");
        assertFalse(PersonMatch.matchesQuery(ken, "ken smith", ALL), "every word must match");
    }

    @Test
    public void cellDigitsMatchAnywhereWhateverTheFormatting() {
        assertTrue(PersonMatch.matchesQuery(ken, "5309", ALL));
        assertTrue(PersonMatch.matchesQuery(ken, "867-5309", ALL));
        assertTrue(PersonMatch.matchesQuery(ken, "(555)867", ALL), "punctuation in the query is ignored");
        assertTrue(PersonMatch.matchesQuery(ken, "5558675309", ALL), "punctuation in the stored cell is ignored");
        assertFalse(PersonMatch.matchesQuery(ken, "1234", ALL));
    }

    @Test
    public void emailMatchesOnlyFromItsStart() {
        assertTrue(PersonMatch.matchesQuery(ken, "ken.paulsen@", ALL));
        assertFalse(PersonMatch.matchesQuery(ken, "example", ALL), "owner decision: no contains on email");
        assertFalse(PersonMatch.matchesQuery(ken, "@example.com", ALL));
    }

    @Test
    public void nothingMatchesNoQueryOrNoPerson() {
        assertFalse(PersonMatch.matchesQuery(ken, null, ALL));
        assertFalse(PersonMatch.matchesQuery(ken, " , ", ALL));
        assertFalse(PersonMatch.matchesQuery(null, "ken", ALL));
        assertFalse(PersonMatch.matchesQuery(new Person(), "ken", ALL), "a blank person has nothing to match");
    }

    @Test
    public void queryWordsSplitOnSpaceAndComma() {
        assertEquals(PersonMatch.queryWords("  Paulsen,  Ken "), List.of("paulsen", "ken"));
        assertEquals(PersonMatch.queryWords(null), List.of());
    }

    @Test
    public void theLookupWordIsTheLongestAsDigitsWhenPhoneLike() {
        assertEquals(PersonMatch.lookupWord(List.of("ken", "paulsen")), "paulsen");
        assertEquals(PersonMatch.lookupWord(List.of("(555)867-5309")), "5558675309");
        assertEquals(PersonMatch.lookupWord(List.of()), "");
    }

    @Test
    public void indexTokensAreNameAndCellSuffixesPlusTheWholeEmail() {
        final Set<String> tokens = PersonMatch.indexTokens(ken);
        assertTrue(tokens.contains("paulsen"));
        assertTrue(tokens.contains("ulsen"), "a suffix, so a prefix lookup finds 'uls' inside the name");
        assertTrue(tokens.contains("n"));
        assertTrue(tokens.contains("5309"), "cell digit suffix");
        assertFalse(tokens.contains("(555) 867-5309"), "the raw cell is never a token: cells match by digits");
        assertTrue(tokens.contains("ken.paulsen@example.com"));
        assertFalse(tokens.contains("example.com"), "no email suffixes");
        assertTrue(PersonMatch.indexTokens(null).isEmpty());
    }

    @Test
    public void aMultiWordNameIndexesNoBlankOrPaddedSuffix() {
        final Person maryAnn = Person.builder().first("Mary Ann").last("Lee").build();
        final Set<String> tokens = PersonMatch.indexTokens(maryAnn);
        assertTrue(tokens.contains("ann"), "the suffix after the space, trimmed");
        assertFalse(tokens.contains(" ann"));
        assertFalse(tokens.contains(""));
    }

    @Test
    public void aPrivateCellOrEmailIsNotSearchedForAViewerWhoCannotSeeIt() {
        final Person shy = Person.builder().first("Shy").last("Vee").email("shy.vee@example.com")
                .cell("555-867-5309").build();
        shy.getPrivacy().setCell(PrivacySettings.Visibility.PRIVATE);
        shy.getPrivacy().setEmail(PrivacySettings.Visibility.PRIVATE);
        assertFalse(PersonMatch.matchesQuery(shy, "5309", PersonMatch.SHARED_FIELDS), "a hidden cell is no oracle");
        assertFalse(PersonMatch.matchesQuery(shy, "shy.vee@", PersonMatch.SHARED_FIELDS), "nor a hidden email");
        assertTrue(PersonMatch.matchesQuery(shy, "vee", PersonMatch.SHARED_FIELDS), "names are never private");
        assertTrue(PersonMatch.matchesQuery(shy, "5309", ALL), "an admin view searches every field");
        assertTrue(PersonMatch.matchesQuery(ken, "5309", PersonMatch.SHARED_FIELDS), "a shared cell is searched");
        assertTrue(PersonMatch.matchesQuery(ken, "ken.paulsen@", PersonMatch.SHARED_FIELDS));
    }

    @Test
    public void aReachIsAskedPerSubjectAndField() {
        final PersonMatch.Reach cellOnly = (subject, field) -> field == PersonMatch.Field.CELL;
        assertTrue(PersonMatch.matchesQuery(ken, "5309", cellOnly));
        assertFalse(PersonMatch.matchesQuery(ken, "ken.paulsen@", cellOnly));
    }
}
