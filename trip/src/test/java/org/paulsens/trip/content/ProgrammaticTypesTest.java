package org.paulsens.trip.content;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.action.TripCommands;
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
        Assert.assertEquals(languages.get(languages.size() - 1).value(), TripCommands.ANY_LANGUAGE,
                "'Any language' is offered, last, so a new section's menu still opens on a real language");
        Assert.assertEquals(languages.get(0).value(), "English");
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

    /**
     * The interface's own defaults, which every type registered TODAY happens to override: a new type that
     * declares neither must still answer empty rather than null, since the content dialog asks both of a
     * type it knows nothing else about.
     */
    @Test
    public void aTypeThatDeclaresNeitherOptionalAnswerGetsEmptyOnes() {
        final ProgrammaticContentTemplate bare = new BareType();
        Assert.assertTrue(bare.choicesFor("anything").isEmpty(), "no choices unless a type offers some");
        Assert.assertTrue(bare.sharedSiteOnlyProperties().isEmpty(),
                "every property of an unopinionated type shows on an organization's own site too");
        Assert.assertEquals(bare.getIcon(), "pi-cog", "the template picker's card has an icon for any type");
    }

    /** A type declaring only the required half of the contract. */
    private static final class BareType implements ProgrammaticContentTemplate {
        @Override
        public String getTypeId() {
            return "bare";
        }

        @Override
        public String getDisplayName() {
            return "Bare";
        }

        @Override
        public String getDescription() {
            return "A type with no choices and no shared-site-only properties.";
        }

        @Override
        public List<Placeholder> getProperties() {
            return List.of();
        }

        @Override
        public String getFragmentPath() {
            return "/WEB-INF/ptypes/bare.xhtml";
        }
    }

    /**
     * Every registered type ships a static sample: the Add dialog's thumbnail, and its preview where the site has
     * nothing real. Sound markup in the fragment's own classes, with no token and no color of its own.
     */
    @Test
    public void everyTypeHasAStaticSampleThatIsSoundThemedMarkup() {
        for (final ProgrammaticContentTemplate type : ProgrammaticTypes.ALL) {
            final String sample = type.getSampleHtml();
            Assert.assertFalse(sample.isBlank(), type.getTypeId() + " has no sample");
            Assert.assertNull(HtmlFragmentValidator.validate(sample), type.getTypeId());
            Assert.assertFalse(sample.contains("{{") || sample.contains("#{"), type.getTypeId());
            Assert.assertFalse(BundledContentColorTest.COLOR.matcher(sample).find(),
                    type.getTypeId() + " writes a literal color");
            Assert.assertFalse(sample.contains("\u2014") || sample.contains("&mdash;"), type.getTypeId());
            Assert.assertFalse(sample.contains("<script") || sample.contains("href="), type.getTypeId());
        }
        Assert.assertTrue(ProgrammaticTypes.byId("person").orElseThrow().getSampleHtml().contains("personCard"));
        Assert.assertTrue(ProgrammaticTypes.byId("file").orElseThrow().getSampleHtml().contains("qopBullets"));
        Assert.assertTrue(ProgrammaticTypes.byId("pilgrimages").orElseThrow().getSampleHtml().contains("pilgrimages"));
        Assert.assertTrue(ProgrammaticTypes.byId("trip-schedule").orElseThrow().getSampleHtml()
                .contains("agenda-item"));
        Assert.assertTrue(ProgrammaticTypes.byId("trip-registration").orElseThrow().getSampleHtml()
                .contains("regStatus-button"));
        Assert.assertTrue(ProgrammaticTypes.byId("photo-albums").orElseThrow().getSampleHtml()
                .contains("src=\"" + StarterTemplates.SAMPLE_PICTURE + "\""));
    }

    /** A type that says nothing has no sample and no real data to preview with: the interface's defaults. */
    @Test
    public void aTypeWithoutASampleOrPreviewFallsBackToItsIcon() {
        final ProgrammaticContentTemplate bare = new ProgrammaticContentTemplate() {
            @Override
            public String getTypeId() {
                return "bare";
            }

            @Override
            public String getDisplayName() {
                return "Bare";
            }

            @Override
            public String getDescription() {
                return "Nothing";
            }

            @Override
            public List<Placeholder> getProperties() {
                return List.of();
            }

            @Override
            public String getFragmentPath() {
                return "/WEB-INF/ptypes/bare.xhtml";
            }
        };
        Assert.assertEquals(bare.getSampleHtml(), "");
        Assert.assertNull(bare.previewValues(new ProgrammaticContentTemplate.PreviewContext(null, null)));
        Assert.assertEquals(bare.getIcon(), "pi-cog");
    }

    /** A preview shows the site's own data: the seeded library's first document, the signed-in person. */
    @Test
    public void previewsUseTheSitesOwnDataWhereThereIsSome() {
        final ProgrammaticContentTemplate.PreviewContext nobody =
                new ProgrammaticContentTemplate.PreviewContext(null, null);
        final ProgrammaticContentTemplate file = ProgrammaticTypes.byId("file").orElseThrow();
        Assert.assertEquals(file.previewValues(nobody).get("mediaId"), file.choicesFor("mediaId").get(0).value());

        final ProgrammaticContentTemplate person = ProgrammaticTypes.byId("person").orElseThrow();
        Assert.assertNull(person.previewValues(nobody), "nobody signed in: the sample");
        Assert.assertEquals(person.previewValues(new ProgrammaticContentTemplate.PreviewContext(null, "p-1")),
                Map.of(PersonType.PROP_PERSON_ID, "p-1"));

        final Map<String, String> listing = ProgrammaticTypes.byId("pilgrimages").orElseThrow().previewValues(nobody);
        Assert.assertNotNull(listing, "the seeded site lists public trips");
        Assert.assertTrue(List.of("English", "Spanish").contains(listing.get("language")), listing.toString());

        // The recent trips' albums when some qualify (the seed has one), and an event's own on its page.
        final ProgrammaticContentTemplate albums = ProgrammaticTypes.byId("photo-albums").orElseThrow();
        Assert.assertEquals(albums.previewValues(nobody), Map.of(), "the type's defaults already show the seed's");
        Assert.assertNull(albums.previewValues(new ProgrammaticContentTemplate.PreviewContext("no-such-trip", null)),
                "an event with no pictures shows the sample");
    }

    /** The two trip sections: the page's own trip, else the site's next public one with something to show. */
    @Test
    public void tripSectionPreviewsPickThePagesTripOrTheNextPublicOne() {
        final ProgrammaticContentTemplate schedule = ProgrammaticTypes.byId("trip-schedule").orElseThrow();
        final ProgrammaticContentTemplate registration = ProgrammaticTypes.byId("trip-registration").orElseThrow();
        final ProgrammaticContentTemplate.PreviewContext onConference =
                new ProgrammaticContentTemplate.PreviewContext(FakeData.CONF_TRIP_ID, null);
        Assert.assertEquals(schedule.previewValues(onConference), Map.of("tripId", FakeData.CONF_TRIP_ID),
                "the conference seed has a public agenda");
        Assert.assertEquals(registration.previewValues(onConference), Map.of("tripId", FakeData.CONF_TRIP_ID));

        final ProgrammaticContentTemplate.PreviewContext unknownTrip =
                new ProgrammaticContentTemplate.PreviewContext("no-such-trip", null);
        Assert.assertNull(schedule.previewValues(unknownTrip), "a page whose trip cannot be shown: the sample");
        Assert.assertNull(registration.previewValues(unknownTrip));

        final ProgrammaticContentTemplate.PreviewContext elsewhere =
                new ProgrammaticContentTemplate.PreviewContext(null, null);
        final Map<String, String> anyTrip = registration.previewValues(elsewhere);
        Assert.assertNotNull(anyTrip, "the seeded site lists public trips");
        Assert.assertFalse(anyTrip.get("tripId").isBlank());
        final Map<String, String> withAgenda = schedule.previewValues(elsewhere);
        Assert.assertTrue(withAgenda == null || !withAgenda.get("tripId").isBlank(),
                "a trip with sessions, or the sample when the site lists none");
    }
}
