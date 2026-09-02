package org.paulsens.trip.content;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The registry's invariants: the admin UI, template validation, and fragments all lean on these. */
public class ProgrammaticTypesTest {

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();     // the File type's media choices come from the seeded library
    }

    @Test
    public void registryIsWellFormed() {
        Assert.assertFalse(ProgrammaticTypes.ALL.isEmpty());
        final Set<String> ids = new HashSet<>();
        for (final ProgrammaticContentTemplate type : ProgrammaticTypes.ALL) {
            Assert.assertTrue(ids.add(type.getTypeId()), "duplicate type id: " + type.getTypeId());
            Assert.assertFalse(type.getDisplayName().isBlank());
            Assert.assertFalse(type.getDescription().isBlank());
            Assert.assertTrue(type.getFragmentPath().startsWith("/WEB-INF/ptypes/"),
                    "fragments live in ptypes/: " + type.getFragmentPath());
            for (final Placeholder property : type.getProperties()) {
                Assert.assertFalse(property.getName().isBlank());
                Assert.assertFalse(property.getLabel().isBlank());
            }
        }
        Assert.assertTrue(ProgrammaticTypes.byId("pilgrimages").isPresent());
        Assert.assertTrue(ProgrammaticTypes.byId("no-such-type").isEmpty());
    }

    @Test
    public void everyChoicePropertyHasAProvider() {
        for (final ProgrammaticContentTemplate type : ProgrammaticTypes.ALL) {
            for (final Placeholder property : type.getProperties()) {
                if (property.getType() == Placeholder.Type.CHOICE) {
                    Assert.assertFalse(type.choicesFor(property.getName()).isEmpty(),
                            type.getTypeId() + "." + property.getName() + " offers no choices");
                }
            }
            Assert.assertTrue(type.choicesFor("no-such-property").isEmpty());
        }
    }

    @Test
    public void pilgrimageChoicesCoverTheLanguages() {
        final ProgrammaticContentTemplate type = ProgrammaticTypes.byId("pilgrimages").orElseThrow();
        final var languages = type.choicesFor("language");
        Assert.assertTrue(languages.stream().anyMatch(c -> c.value().equals("English")));
        Assert.assertTrue(languages.stream().anyMatch(c -> c.value().equals("Spanish")));
    }

    @Test
    public void fileChoicesListOnlyVisibleCuratedMedia() {
        final ProgrammaticContentTemplate type = ProgrammaticTypes.byId("file").orElseThrow();
        final var choices = type.choicesFor("mediaId");
        Assert.assertTrue(choices.stream().anyMatch(c -> c.value().equals("fake-doc-1")),
                "the visible library doc is offered");
        Assert.assertTrue(choices.stream().noneMatch(c -> c.value().equals("fake-doc-2")),
                "hidden media is not offered");
        Assert.assertTrue(choices.stream().noneMatch(c -> c.value().startsWith("fake-photo-")),
                "chat photos are not documents");
        Assert.assertTrue(choices.stream().anyMatch(c -> c.label().contains("MB")
                        || c.label().contains("KB") || c.label().contains("bytes")),
                "labels carry the live size");
    }

    /**
     * The stored placeholder list of a PROGRAMMATIC row is a creation-time snapshot and only advisory: the
     * registry's live list wins, whatever the row carries. Every other kind, and a programmatic row whose
     * type is no longer registered, keeps what it stored.
     */
    @Test
    public void placeholdersOfPrefersTheLiveRegistryList() {
        Assert.assertTrue(ProgrammaticTypes.placeholdersOf(null).isEmpty());
        final List<Placeholder> stored = List.of(
                new Placeholder("language", Placeholder.Type.CHOICE, "Language", null, true));
        final ContentTemplate standard = new ContentTemplate("pt-standard", 1, "Standard", null,
                "<p>{{language}}</p>", stored, null, null);
        Assert.assertEquals(ProgrammaticTypes.placeholdersOf(standard), stored, "STANDARD keeps its stored list");

        final ContentTemplate stale = new ContentTemplate("pt-stale", 1, "Stale", null, "", stored, null, null,
                TemplateKind.PROGRAMMATIC, null, null, "pilgrimages");
        final List<Placeholder> live = ProgrammaticTypes.byId("pilgrimages").orElseThrow().getProperties();
        Assert.assertEquals(ProgrammaticTypes.placeholdersOf(stale), live,
                "a programmatic row answers its type's live properties, not the snapshot it stored");
        Assert.assertTrue(live.stream().anyMatch(ph -> ph.getName().equals("includeOrgs")
                        && ph.getType() == Placeholder.Type.MULTI_CHOICE),
                "the shared-site org curation prompt is part of that live list");
        Assert.assertEquals(stale.getPlaceholders(), stored, "reading never rewrites the row");

        final ContentTemplate retired = new ContentTemplate("pt-retired", 1, "Retired", null, "", stored,
                null, null, TemplateKind.PROGRAMMATIC, null, null, "no-such-type");
        Assert.assertEquals(ProgrammaticTypes.placeholdersOf(retired), stored,
                "an unregistered type falls back to what the row stored");
    }
}
