package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.FloorMap;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Room;

import static org.paulsens.trip.action.PageFeedback.callbackParam;
import static org.paulsens.trip.action.PageFeedback.refuse;
import static org.paulsens.trip.action.PageFeedback.warn;

/**
 * A hotel's floor plans: which media-library image is each floor's picture, and where its rooms sit on it.
 * Split out of {@link LodgingCommands}, which keeps the read helpers the board also needs; pages still bind
 * to {@code #{lodging}}.
 *
 * <p>Floor NAMES are free text in two places (a room names its floor, a plan names its floor) and nothing
 * keeps them in step, so renaming a floor orphans its plan. That is why moving and removing a plan exist at
 * all -- see {@code docs/lodging.md}, "Floors are free text".
 */
final class LodgingFloorMaps {
    private final LodgingCommands lodging;
    private final MediaCommands media;

    LodgingFloorMaps(final LodgingCommands lodging, final MediaCommands media) {
        this.lodging = lodging;
        this.media = media;
    }

    /** The media id of this floor's plan image, or "" when none. */
    public String floorMapMediaId(final String accId, final String floor) {
        final Accommodation acc = lodging.findAccommodation(accId);
        final FloorMap map = (acc == null) ? null : acc.floorMap(floor);
        return (map == null || map.getMediaId() == null) ? "" : map.getMediaId();
    }

    /**
     * The URL of a media-library image, or "" when the id no longer resolves: the CDN when a bucket is
     * configured, else this app's own {@code /lodging-photos/*} GET (local mode, every webtest).
     */
    public String mediaUrl(final String mediaId) {
        final MediaItem item = media.get(mediaId);
        if (item == null) {
            return "";
        }
        return media.isUploadEnabled() ? LodgingCommands.nullSafe(media.getUrl(item))
                : contextPath() + "/lodging-photos/" + item.getS3Key();
    }

    private static String contextPath() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return (ctx == null) ? "" : ctx.getExternalContext().getRequestContextPath();
    }

    public boolean setFloorMap(final String accId, final String floor, final String mediaId) {
        if (!lodging.canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        if (floor == null || floor.isBlank()) {
            return refuse("Which floor?");
        }
        final Accommodation acc = lodging.freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        final FloorMap existing = acc.floorMap(floor.trim());
        if (existing == null) {
            acc.getFloorMaps().add(new FloorMap(floor.trim(), mediaId));
        } else {
            existing.setMediaId(mediaId);
        }
        if (acc.roomsOnFloor(floor.trim()).isEmpty()) {
            // Silently attaching a plan to a floor no room is on is how a mismatch hides: the plan looks
            // uploaded, nothing can be mapped on it, and the floor list grows an entry with no rooms.
            warn("No rooms are on floor '" + floor.trim() + "', so this plan has nothing to map yet.");
        }
        return lodging.store(acc) && lodging.audited(acc, "Floor plan set for floor " + floor.trim());
    }

    /**
     * Takes the plan off a floor. The image stays in the media library, exactly as removing a gallery photo
     * does, and a floor that existed only because a plan named it leaves the floor list with it.
     */
    public boolean removeFloorMap(final String accId, final String floor) {
        if (!lodging.canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        if (floor == null || floor.isBlank()) {
            return refuse("Which floor?");
        }
        final Accommodation acc = lodging.freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        final String wanted = floor.trim();
        if (!acc.getFloorMaps().removeIf(map -> wanted.equals(map.getFloor()))) {
            return refuse("Floor " + wanted + " has no plan to remove.");
        }
        return lodging.store(acc) && lodging.audited(acc, "Floor plan removed for floor " + wanted);
    }

    /**
     * The floors a plan on {@code from} could be moved to: every floor of the hotel except the one it is on
     * and any floor that already has its own plan.
     */
    public List<String> moveTargets(final String accId, final String from) {
        final Accommodation acc = lodging.findAccommodation(accId);
        if (acc == null || from == null) {
            return List.of();
        }
        final List<String> targets = new ArrayList<>();
        for (final String floor : acc.floors()) {
            if (!floor.equals(from.trim()) && acc.floorMap(floor) == null) {
                targets.add(floor);
            }
        }
        return targets;
    }

    /**
     * Re-points a plan at another floor, keeping the image. This is the repair for a plan and its rooms
     * ending up on differently named floors -- renaming the rooms' floor orphans the plan on the old name,
     * leaving a floor with a picture nothing can be mapped on and rooms with no picture (2026-09-07).
     * Both floors' regions are cleared: boxes on the source floor were drawn against an image it no longer
     * has, and boxes on the target floor were drawn against the plan this one replaces.
     */
    public boolean moveFloorMap(final String accId, final String from, final String to) {
        if (!lodging.canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return refuse("Which floor should this plan move to?");
        }
        final String source = from.trim();
        final String target = to.trim();
        if (source.equals(target)) {
            return refuse("That plan is already on floor " + target + ".");
        }
        final Accommodation acc = lodging.freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        final FloorMap moving = acc.floorMap(source);
        if (moving == null) {
            return refuse("Floor " + source + " has no plan to move.");
        }
        if (acc.floorMap(target) != null) {
            return refuse("Floor " + target + " already has a plan. Remove that one first.");
        }
        moving.setFloor(target);
        for (final Room room : acc.getRooms()) {
            if (source.equals(room.getFloor()) || target.equals(room.getFloor())) {
                room.setMapRegion(null);
            }
        }
        return lodging.store(acc)
                && lodging.audited(acc, "Floor plan moved from floor " + source + " to floor " + target);
    }

    /**
     * Saves the annotator's regions for one floor: {@code [{roomId, kind, x, y, w, h}, ...]} in percent.
     * Every listed room must be on that floor, every box inside the image; rooms on the floor that are not
     * listed lose their region (the annotator deleted them). Answers the {@code saved} callback param.
     */
    public boolean saveFloorRegions(final String accId, final String floor, final String json) {
        if (!lodging.canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = lodging.freshAccommodation(accId);
        if (acc == null || floor == null) {
            return refuse("This accommodation no longer exists.");
        }
        final Map<String, Room.MapRegion> regions;
        try {
            regions = parseRegions(json);
        } catch (final IllegalArgumentException | IOException ex) {
            return refuse("The floor plan could not be read: " + ex.getMessage());
        }
        for (final String roomId : regions.keySet()) {
            final Room room = acc.room(roomId);
            if (room == null || !Objects.equals(floor, room.getFloor())) {
                return refuse("A box points at a room that is not on floor " + floor + ".");
            }
        }
        for (final Room room : acc.roomsOnFloor(floor)) {
            room.setMapRegion(regions.get(room.getId()));
        }
        final boolean saved = lodging.store(acc)
                && lodging.audited(acc, "Floor " + floor + " plan: " + regions.size() + " rooms mapped");
        callbackParam("saved", saved);
        return saved;
    }

    static Map<String, Room.MapRegion> parseRegions(final String json) throws IOException {
        final Map<String, Room.MapRegion> regions = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return regions;
        }
        final List<Map<String, Object>> raw = DAO.getInstance().getMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        for (final Map<String, Object> entry : raw) {
            final Object roomId = entry.get("roomId");
            if (roomId == null || roomId.toString().isBlank()) {
                continue;
            }
            final Room.MapRegion region = Room.MapRegion.rect(number(entry.get("x")), number(entry.get("y")),
                    number(entry.get("w")), number(entry.get("h")));
            if (!region.isValid()) {
                throw new IllegalArgumentException("a box is outside the image or has no size");
            }
            if (regions.put(roomId.toString(), region) != null) {
                throw new IllegalArgumentException("room " + roomId + " is mapped twice");
            }
        }
        return regions;
    }

    private static double number(final Object value) {
        if (value instanceof Number n) {
            return Math.round(n.doubleValue() * 100.0) / 100.0;
        }
        throw new IllegalArgumentException("a coordinate is missing");
    }
}
