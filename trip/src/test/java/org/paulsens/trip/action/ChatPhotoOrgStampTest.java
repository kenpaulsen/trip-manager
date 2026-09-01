package org.paulsens.trip.action;

import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.testng.Assert;
import org.testng.annotations.Test;

/** A chat photo's media row is stamped with its TRIP's organization, whatever host the sender was on. */
public class ChatPhotoOrgStampTest {

    @Test
    public void theTripsOrgIsTheStampAndAnUnresolvableTripStampsNothing() {
        DAO.getInstance();
        FakeData.addFakeData();
        Assert.assertEquals(ChatPhotos.orgOfTrip(FakeData.ACME_TRIP_ID), FakeData.ACME_ORG_ID);
        Assert.assertNull(ChatPhotos.orgOfTrip("no-such-trip"), "unknown trip: a site-level row, not a failure");
        Assert.assertNull(ChatPhotos.orgOfTrip(null), "a broken lookup never fails the send");
        Assert.assertEquals(ChatPhotos.tripOfSlot("tripChat-abc"), "abc");
        Assert.assertNull(ChatPhotos.tripOfSlot("home-docs"));
        Assert.assertNull(ChatPhotos.tripOfSlot(null));
    }
}
