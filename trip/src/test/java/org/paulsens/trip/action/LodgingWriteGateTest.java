package org.paulsens.trip.action;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.action.LodgingViews.RoomForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.util.TestPeople;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The accommodation write gate ({@code LodgingAccessCommands.editableAccommodation}) at each of the thirteen
 * writes that go through it: who may not edit is told so before anything else, and a hotel that is gone is
 * reported before the form is validated (owner, 2026-11-24; validation used to run between the two at five of
 * them, and six more folded "gone" into another refusal or into silence).
 */
public class LodgingWriteGateTest {

    private static final Path ACTION_SOURCES = Path.of("src", "main", "java", "org", "paulsens", "trip", "action");
    /** A method's header at class level: its name, then everything up to the brace that opens its body. */
    private static final Pattern METHOD = Pattern.compile("(?m)^    [\\w<>,.\\[\\] ]+ (\\w+)\\([^;{]*\\{$");
    private static final Set<String> STORE_EXCEPTIONS = Set.of("saveAccommodation");
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
        writes.put("deleteRoomType", bean -> () -> bean.deleteRoomType(hotel, "nope"));
        writes.put("deleteRoom", bean -> () -> bean.deleteRoom(hotel, "nope"));
        writes.put("bulkAddRooms", bean -> () -> bean.bulkAddRooms(hotel, "", 5, 1, "1", "no-type") != 0);
        writes.put("addPhoto", bean -> () -> bean.addPhoto(hotel, null, " "));
        writes.put("movePhoto", bean -> () -> bean.movePhoto(hotel, null, "no-photo", 1));
        writes.put("removePhoto", bean -> () -> bean.removePhoto(hotel, null, "no-photo"));
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
    public void theFreshReadIsSafeOnItsOwnForAnIdThatNamesNothing() {
        Assert.assertNull(LodgingAccessCommands.freshAccommodation(null));
        Assert.assertNull(LodgingAccessCommands.freshAccommodation(" "));
        Assert.assertNull(LodgingAccessCommands.freshAccommodation(Accommodation.Id.newInstance().getValue()));
        Assert.assertEquals(LodgingAccessCommands.freshAccommodation(" " + accId + " ").getId().getValue(), accId,
                "the id is trimmed, as the gate's callers hand it over");
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

    /**
     * A source ratchet, not a count of this test's own map: every method in the two lodging write files that stores a
     * hotel must open it through the gate ({@code editableAccommodation}), so a new write that reads the hotel some
     * other way fails here. {@code saveAccommodation} is the one exception: it creates the hotel the gate would look
     * up.
     */
    @Test
    public void everyMethodThatStoresAHotelOpensItThroughTheGate() throws IOException {
        final List<String> writers = new ArrayList<>();
        final List<String> ungated = new ArrayList<>();
        for (final String file : List.of("LodgingCommands.java", "LodgingFloorMaps.java")) {
            final String source = Files.readString(ACTION_SOURCES.resolve(file));
            final Matcher method = METHOD.matcher(source);
            while (method.find()) {
                final String body = source.substring(method.end(), source.indexOf("\n    }\n", method.end()));
                if (body.contains("store(acc") && !STORE_EXCEPTIONS.contains(method.group(1))) {
                    writers.add(method.group(1));
                    addIfUngated(ungated, method.group(1), body);
                }
            }
        }
        Assert.assertEquals(ungated, List.of(), "a hotel write that skips the gate");
        Assert.assertTrue(writers.size() >= invalidWrites(accId).size(), "the ratchet found the writes: " + writers);
    }

    private static void addIfUngated(final List<String> ungated, final String name, final String body) {
        if (!body.contains("editableAccommodation(")) {
            ungated.add(name);
        }
    }

    @Test
    public void validationStillAnswersForAHotelThatExists() {
        final Map<String, Function<LodgingCommands, BooleanSupplier>> writes = invalidWrites(accId);
        final Map<String, List<String>> answers = new LinkedHashMap<>();
        answers.put("deleteRoomType", List.of("Unknown room type."));
        answers.put("deleteRoom", List.of("Unknown room."));
        answers.put("bulkAddRooms", List.of("Choose a room type."));
        answers.put("addPhoto", List.of("Nothing to add."));
        // A photo that is not in the gallery is refused without a message, as it always was: only a hotel
        // that is gone, or one the caller may not edit, speaks.
        answers.put("movePhoto", List.of());
        answers.put("removePhoto", List.of());
        for (final Map.Entry<String, List<String>> answer : answers.entrySet()) {
            Assert.assertEquals(growlsOfRefused(writes.get(answer.getKey()).apply(admin), answer.getKey()),
                    answer.getValue(), answer.getKey());
        }
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
        for (final String noFloor : new String[] {null, " "}) {
            Assert.assertEquals(
                    growlsOfRefused(() -> admin.saveFloorRegions(accId, noFloor, ""), "saveFloorRegions"),
                    List.of("Which floor?"), "the hotel exists, so a save that names no floor is asked for one");
        }
    }
}
