package org.paulsens.trip.action;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.paulsens.trip.action.LodgingViews.RoomForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.util.TestPeople;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The accommodation write gate ({@code LodgingAccessCommands.editableAccommodation}) at each of the seven writes
 * that go through it: who may not edit is told so before anything else, and a hotel that is gone is reported
 * before the form is validated (owner, 2026-11-24; validation used to run between the two at five of them).
 */
public class LodgingWriteGateTest {
    private static final String NOT_YOURS = "Not allowed: you do not manage this accommodation.";
    private static final String GONE = "This accommodation no longer exists.";

    private final LodgingCommands admin = new LodgingCommands(TestCallers::siteAdmin);
    private LodgingCommands stranger;
    private String accId;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        accId = LodgingFixtures.hotel(admin, FakeData.CFPW_ORG_ID, "Gate");
        final Caller nobody = TestCallers.person(TestPeople.savedPerson("gate").getId());
        stranger = new LodgingCommands(() -> nobody);
    }

    /**
     * Every write with a form that would ALSO fail validation, so the message says which check ran first. The
     * key is the name the failure prints.
     */
    private static Map<String, Function<LodgingCommands, BooleanSupplier>> invalidWrites(final String hotel) {
        final Map<String, Function<LodgingCommands, BooleanSupplier>> writes = new LinkedHashMap<>();
        writes.put("retireAccommodation", bean -> () -> bean.retireAccommodation(hotel, true));
        writes.put("saveRoomType", bean -> () -> bean.saveRoomType(hotel, new RoomTypeForm()));
        writes.put("saveRoom", bean -> () -> bean.saveRoom(hotel, new RoomForm()));
        writes.put("setFloorMap", bean -> () -> bean.setFloorMap(hotel, " ", "media"));
        writes.put("removeFloorMap", bean -> () -> bean.removeFloorMap(hotel, " "));
        writes.put("moveFloorMap", bean -> () -> bean.moveFloorMap(hotel, "1", "1"));
        writes.put("saveFloorRegions", bean -> () -> bean.saveFloorRegions(hotel, "1", "not json"));
        return writes;
    }

    private static List<String> growlsOfRefused(final BooleanSupplier write, final String name) {
        return TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertFalse(write.getAsBoolean(), name));
    }

    @Test
    public void aCallerWhoMayNotEditIsToldSoBeforeAnythingElse() {
        final String gone = Accommodation.Id.newInstance().getValue();
        for (final String hotel : List.of(accId, gone)) {
            for (final Map.Entry<String, Function<LodgingCommands, BooleanSupplier>> write
                    : invalidWrites(hotel).entrySet()) {
                Assert.assertEquals(growlsOfRefused(write.getValue().apply(stranger), write.getKey()),
                        List.of(NOT_YOURS), write.getKey() + ": the permission is the first check, so a caller"
                                + " who may not edit a hotel never learns whether it exists");
            }
        }
    }

    @Test
    public void aVanishedHotelIsReportedBeforeTheFormIsValidated() {
        final String gone = Accommodation.Id.newInstance().getValue();
        for (final Map.Entry<String, Function<LodgingCommands, BooleanSupplier>> write
                : invalidWrites(gone).entrySet()) {
            Assert.assertEquals(growlsOfRefused(write.getValue().apply(admin), write.getKey()), List.of(GONE),
                    write.getKey());
        }
    }

    @Test
    public void validationStillAnswersForAHotelThatExists() {
        final Map<String, Function<LodgingCommands, BooleanSupplier>> writes = invalidWrites(accId);
        Assert.assertEquals(growlsOfRefused(writes.get("saveRoomType").apply(admin), "saveRoomType"),
                List.of("A room type needs a name."));
        Assert.assertEquals(growlsOfRefused(writes.get("saveRoom").apply(admin), "saveRoom"),
                List.of("A room needs a number."));
        Assert.assertEquals(growlsOfRefused(writes.get("setFloorMap").apply(admin), "setFloorMap"),
                List.of("Which floor?"));
        Assert.assertEquals(growlsOfRefused(writes.get("removeFloorMap").apply(admin), "removeFloorMap"),
                List.of("Which floor?"));
        Assert.assertEquals(growlsOfRefused(writes.get("moveFloorMap").apply(admin), "moveFloorMap"),
                List.of("That plan is already on floor 1."));
        Assert.assertEquals(growlsOfRefused(() -> admin.saveFloorRegions(accId, null, ""), "saveFloorRegions"),
                List.of(GONE), "a regions save that names no floor keeps its old answer");
    }
}
