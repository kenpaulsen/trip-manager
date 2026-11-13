package org.paulsens.trip.dynamo;

import java.util.List;
import java.util.Set;
import org.paulsens.trip.model.Person;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** The one people-search rule: names and cell digits match anywhere, email only from its start. */
public class PersonMatchTest {
    private final Person ken = Person.builder().first("Ken").last("Paulsen").nickname("Kenny")
            .email("ken.paulsen@example.com").cell("(555) 867-5309").build();

    @Test
    public void namesMatchAnywhereInside() {
        assertTrue(PersonMatch.matchesQuery(ken, "ulse"), "inside the last name");
        assertTrue(PersonMatch.matchesQuery(ken, "SEN"), "case does not matter");
        assertTrue(PersonMatch.matchesQuery(ken, "nny"), "inside the nickname");
        assertTrue(PersonMatch.matchesQuery(ken, "en paul"), "every word, each anywhere");
        assertFalse(PersonMatch.matchesQuery(ken, "ken smith"), "every word must match");
    }

    @Test
    public void cellDigitsMatchAnywhereWhateverTheFormatting() {
        assertTrue(PersonMatch.matchesQuery(ken, "5309"));
        assertTrue(PersonMatch.matchesQuery(ken, "867-5309"));
        assertTrue(PersonMatch.matchesQuery(ken, "(555)867"), "punctuation in the query is ignored");
        assertTrue(PersonMatch.matchesQuery(ken, "5558675309"), "punctuation in the stored cell is ignored");
        assertFalse(PersonMatch.matchesQuery(ken, "1234"));
    }

    @Test
    public void emailMatchesOnlyFromItsStart() {
        assertTrue(PersonMatch.matchesQuery(ken, "ken.paulsen@"));
        assertFalse(PersonMatch.matchesQuery(ken, "example"), "owner decision: no contains on email");
        assertFalse(PersonMatch.matchesQuery(ken, "@example.com"));
    }

    @Test
    public void nothingMatchesNoQueryOrNoPerson() {
        assertFalse(PersonMatch.matchesQuery(ken, null));
        assertFalse(PersonMatch.matchesQuery(ken, " , "));
        assertFalse(PersonMatch.matchesQuery(null, "ken"));
        assertFalse(PersonMatch.matchesQuery(new Person(), "ken"), "a blank person has nothing to match");
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
    public void indexTokensAreNameAndCellSuffixesPlusWholeEmailAndCell() {
        final Set<String> tokens = PersonMatch.indexTokens(ken);
        assertTrue(tokens.contains("paulsen"));
        assertTrue(tokens.contains("ulsen"), "a suffix, so a prefix lookup finds 'uls' inside the name");
        assertTrue(tokens.contains("n"));
        assertTrue(tokens.contains("5309"), "cell digit suffix");
        assertTrue(tokens.contains("(555) 867-5309"), "the raw cell keeps its prefix lookup");
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
}
