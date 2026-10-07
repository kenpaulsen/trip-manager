package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.PrivacySettings;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The board row's rules: live account fields, the page's own words, and the person's privacy choices. */
public class PersonTypeTest {

    private static final String ID = "8b0e3c2a-1f4d-4e6a-9b7c-5d3e2f1a0b9c";

    private static Person person(final String cell, final String email) {
        final Person person = Person.builder().id(Person.Id.from(ID)).first("Kenneth").nickname("Ken")
                .last("Paulsen").cell(cell).email(email).build();
        return person;
    }

    private static Map<String, String> values(final String... pairs) {
        final Map<String, String> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    @Test
    public void theTypeDeclaresAPersonPickerAndItsOwnWords() {
        final PersonType type = new PersonType();
        Assert.assertEquals(type.getTypeId(), StarterTemplates.PERSON_ID);
        Assert.assertEquals(type.getFragmentPath(), "/WEB-INF/ptypes/person.xhtml");
        Assert.assertFalse(type.getDisplayName().isBlank());
        Assert.assertFalse(type.getDescription().isBlank());
        Assert.assertEquals(type.getProperties().get(0).getType(), Placeholder.Type.PERSON);
        Assert.assertEquals(type.getProperties().stream().map(Placeholder::getName).toList(),
                java.util.List.of("personId", "role", "publicEmail", "name", "phone"));
        Assert.assertTrue(type.getProperties().stream().noneMatch(Placeholder::isRequired),
                "a row without an account is legitimate, so nothing is required");
        Assert.assertTrue(ProgrammaticTypes.byId(StarterTemplates.PERSON_ID).isPresent(), "registered");
    }

    @Test
    public void anAccountRowPublishesItsCellAndShowsTheAccountEmailOnlyToSignedInViewers() {
        final Person ken = person("(360) 555-0100", "ken@example.com");
        final Map<String, String> row = values("role", "President", "publicEmail", "KEN@cfpw.example");

        final PersonType.Card anonymous = PersonType.card(row, ken, false);
        Assert.assertEquals(anonymous.personId(), ID);
        Assert.assertEquals(anonymous.name(), "Ken Paulsen", "preferred name, as the page always showed");
        Assert.assertEquals(anonymous.role(), "President");
        Assert.assertEquals(anonymous.publicEmail(), "KEN@cfpw.example");
        Assert.assertEquals(anonymous.phone(), "(360) 555-0100", "the cell is published, as before");
        Assert.assertNull(anonymous.accountEmail(), "never to an anonymous visitor");
        Assert.assertEquals(anonymous.avatarEmail(), "ken@example.com");
        Assert.assertEquals(anonymous.initials(), "KP");

        Assert.assertEquals(PersonType.card(row, ken, true).accountEmail(), "ken@example.com");
        Assert.assertNull(PersonType.card(values("publicEmail", " Ken@Example.com "), ken, true).accountEmail(),
                "an account email that IS the public one is not repeated");
    }

    @Test
    public void aPrivateFieldIsWithheldEvenFromSignedInViewers() {
        final Person ken = person("(360) 555-0100", "ken@example.com");
        ken.setPrivacy(new PrivacySettings(PrivacySettings.Visibility.PRIVATE, PrivacySettings.Visibility.PRIVATE,
                null, null));
        final PersonType.Card card = PersonType.card(values("publicEmail", "board@example.com"), ken, true);
        Assert.assertNull(card.phone(), "a PRIVATE cell is not published");
        Assert.assertNull(card.accountEmail(), "a PRIVATE email is not shown");
        Assert.assertNull(card.avatarEmail(), "nor hashed into a gravatar");
        Assert.assertEquals(card.publicEmail(), "board@example.com", "the page's own words always show");
        Assert.assertEquals(PersonType.card(values("phone", "(503) 555-0199"), ken, false).phone(),
                "(503) 555-0199", "a typed phone is the editor's publication, not the account's");
    }

    @Test
    public void overridesWinAndARowWithoutAnAccountStandsOnThem() {
        final Person ken = person(null, null);
        final PersonType.Card renamed = PersonType.card(values("name", " Fr. Ken "), ken, true);
        Assert.assertEquals(renamed.name(), "Fr. Ken");
        Assert.assertNull(renamed.phone(), "no cell on the account and no override");
        Assert.assertNull(renamed.avatarEmail(), "no address to hash");

        final PersonType.Card noAccount = PersonType.card(
                values("name", "Karen Stratton", "phone", "(503) 555-0101", "publicEmail", "karen@example.com",
                        "role", " "), null, true);
        Assert.assertNull(noAccount.personId());
        Assert.assertEquals(noAccount.name(), "Karen Stratton");
        Assert.assertEquals(noAccount.phone(), "(503) 555-0101");
        Assert.assertNull(noAccount.role(), "blank is absent");
        Assert.assertNull(noAccount.accountEmail());
        Assert.assertEquals(noAccount.initials(), "KS");

        final PersonType.Card empty = PersonType.card(null, null, false);
        Assert.assertNull(empty.name());
        Assert.assertEquals(empty.initials(), "");
    }

    @Test
    public void initialsTakeTheFirstAndLastWords() {
        Assert.assertEquals(PersonType.initials("Monica Martinez de Martinez"), "MM");
        Assert.assertEquals(PersonType.initials("cher"), "C");
        Assert.assertEquals(PersonType.initials("   "), "");
        Assert.assertEquals(PersonType.initials(null), "");
    }

    @Test
    public void anAccountWithNoNameFallsBackToNothing() {
        final Person blank = Person.builder().id(Person.Id.from(ID)).build();
        Assert.assertNull(PersonType.card(Map.of(), blank, false).name());
    }
}
