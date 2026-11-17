package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The platform tag list (issue 154): parsing the setting, ids, and what a trip may store and show. */
public class TripTagsTest {

    private static final List<TripTags.Tag> OFFERED = TripTags.parse("Retreat, Bible Study,  , retreat, Youth");

    @Test
    public void theSettingParsesInOrderWithIdsAndNoRepeats() {
        Assert.assertEquals(OFFERED, List.of(new TripTags.Tag("retreat", "Retreat"),
                new TripTags.Tag("bible-study", "Bible Study"), new TripTags.Tag("youth", "Youth")));
        Assert.assertEquals(TripTags.parse(null), List.of());
        Assert.assertEquals(TripTags.parse(KnownSettings.TRIP_TAGS.getDefaultValue()).stream()
                .map(TripTags.Tag::id).toList(), List.of("pilgrimage", "retreat", "conference", "mission", "youth",
                "family", "study", "service", "tour"), "The owner's starter list, plus Tour");
        Assert.assertEquals(TripTags.current().size(), 9, "Nothing stored: the default list");
    }

    @Test
    public void idsAreSlugs() {
        Assert.assertEquals(TripTags.idOf(" Bible  Study! "), "bible-study");
        Assert.assertEquals(TripTags.idOf("--Youth--"), "youth");
        Assert.assertEquals(TripTags.idOf(null), "");
    }

    @Test
    public void aWriteKeepsOnlyOfferedTagsInTheListsOrder() {
        Assert.assertEquals(TripTags.sanitize(List.of("youth", "Bible Study", "made-up", "retreat"), OFFERED),
                List.of("retreat", "bible-study", "youth"));
        Assert.assertEquals(TripTags.sanitize(null, OFFERED), List.of());
        Assert.assertEquals(TripTags.sanitize(java.util.Arrays.asList("youth", null), OFFERED), List.of("youth"));
    }

    @Test
    public void labelsShowOnlyTagsStillOffered() {
        final Trip trip = Trip.builder().tags(List.of("youth", "retired-tag", "retreat")).build();
        Assert.assertEquals(TripTags.shownOn(trip, OFFERED).stream().map(TripTags.Tag::label).toList(),
                List.of("Retreat", "Youth"));
        Assert.assertEquals(TripTags.shownOn(null, OFFERED), List.of());
    }
}
