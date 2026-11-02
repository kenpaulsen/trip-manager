package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.action.ConferenceCommands;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Placeholder;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The conference page's two section types (docs/conference.md): registered, trip-page-fit, one Event property. */
public class ConferenceTypesTest {

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @Test
    public void bothTypesAreRegisteredWithTheirFragmentsAndOneEventProperty() {
        for (final ProgrammaticContentTemplate type : List.of(new TripScheduleType(), new TripRegistrationType())) {
            Assert.assertTrue(ProgrammaticTypes.byId(type.getTypeId()).isPresent(), type.getTypeId());
            Assert.assertTrue(type.getFragmentPath().startsWith("/WEB-INF/ptypes/trip"), type.getFragmentPath());
            Assert.assertFalse(type.getDisplayName().isBlank());
            Assert.assertFalse(type.getDescription().isBlank());
            Assert.assertTrue(type.fitsTripPage(), "made for a trip's page");
            final List<Placeholder> properties = type.getProperties();
            Assert.assertEquals(properties.size(), 1);
            Assert.assertEquals(properties.get(0).getName(), ConferenceCommands.PROP_TRIP_ID);
            Assert.assertFalse(properties.get(0).isRequired(), "blank means this page's event");
            Assert.assertTrue(type.choicesFor("nope").isEmpty());
            final List<ProgrammaticContentTemplate.Choice> choices = type.choicesFor(ConferenceCommands.PROP_TRIP_ID);
            Assert.assertEquals(choices.get(0).value(), "", "the first choice is this page's event");
        }
        Assert.assertEquals(new TripScheduleType().getTypeId(), StarterTemplates.TRIP_SCHEDULE_ID);
        Assert.assertEquals(new TripRegistrationType().getTypeId(), StarterTemplates.TRIP_REGISTRATION_ID);
        Assert.assertFalse(new PilgrimagesType().fitsTripPage());
        // Photo albums fit a conference page since they can show ONE event's album (its "Event" property).
        Assert.assertTrue(new PhotoAlbumsType().fitsTripPage());
        Assert.assertTrue(new PhotoAlbumsType().getProperties().stream()
                .anyMatch(p -> ConferenceCommands.PROP_TRIP_ID.equals(p.getName())));
        Assert.assertEquals(new PhotoAlbumsType().choicesFor(ConferenceCommands.PROP_TRIP_ID).get(0).value(), "");
        Assert.assertTrue(new FileType().fitsTripPage());
    }
}
