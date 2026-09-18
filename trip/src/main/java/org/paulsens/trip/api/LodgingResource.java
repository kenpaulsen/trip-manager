package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.HotelCommands;
import org.paulsens.trip.action.LodgingCommands;
import org.paulsens.trip.action.LodgingViews;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;

/**
 * Lodging: accommodations, a trip's offers and reservations, and room assignment.
 *
 * <p>Deliberately SMALL and command-shaped: every write goes through {@link LodgingCommands} with the
 * session's caller, so the gates, the automatic bills and the audit are exactly the pages'. No DTO layer
 * yet -- the bodies are the command bean's own scalar forms in JSON, which is what the browser tests use to
 * own their fixtures; a self-service booking API is a later design (docs/lodging.md).
 */
@Slf4j
@Path("lodging")
@TripApi
public class LodgingResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.LODGING_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    private LodgingCommands lodging() {
        return new LodgingCommands(this::caller);
    }

    private HotelCommands hotel() {
        return new HotelCommands(this::caller);
    }

    /**
     * Creates an accommodation with room types and rooms in one call. Body: {@code {name, email, phone,
     * website, street, city, zip, country, orgId, roomTypes:[{name,minPeople,maxPeople}],
     * rooms:[{number,floor,type}]}} where a room's {@code type} names a room type by name. Answers
     * {@code {id, roomTypeIds:{name:id}, roomIds:{number:id}}}.
     */
    @POST
    @Path("accommodations")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createAccommodation(@HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canCreateAccommodation()) {
            return error(403, ApiErrors.FORBIDDEN, "Lodging admin required.");
        }
        final LodgingViews.AccommodationForm form = new LodgingViews.AccommodationForm();
        form.setName(str(body, "name"));
        form.setEmail(str(body, "email"));
        form.setPhone(str(body, "phone"));
        form.setWebsite(str(body, "website"));
        form.setStreet(str(body, "street"));
        form.setCity(str(body, "city"));
        form.setZip(str(body, "zip"));
        form.setCountry(str(body, "country"));
        form.setContactEmail(str(body, "contactEmail"));
        form.setContactFirst(str(body, "contactFirst"));
        form.setContactLast(str(body, "contactLast"));
        form.setForce(true);
        final String accId = lodging.saveAccommodation(form, str(body, "orgId"));
        if (accId.isEmpty()) {
            return error(400, ApiErrors.VALIDATION_FAILED, "The accommodation was not created.");
        }
        final Map<String, String> typeIds = new LinkedHashMap<>();
        for (final Map<String, Object> type : list(body, "roomTypes")) {
            final LodgingViews.RoomTypeForm rt = new LodgingViews.RoomTypeForm();
            rt.setName(str(type, "name"));
            rt.setMinPeople(intOf(type, "minPeople", 1));
            rt.setMaxPeople(intOf(type, "maxPeople", 2));
            if (!lodging.saveRoomType(accId, rt)) {
                return error(400, ApiErrors.VALIDATION_FAILED, "Room type '" + rt.getName() + "' was not created.");
            }
        }
        for (final LodgingViews.RoomTypeRow row : lodging.roomTypeRows(accId)) {
            typeIds.put(row.getName(), row.getId());
        }
        for (final Map<String, Object> room : list(body, "rooms")) {
            final LodgingViews.RoomForm rf = new LodgingViews.RoomForm();
            rf.setRoomNumber(str(room, "number"));
            rf.setFloor(str(room, "floor"));
            rf.setRoomTypeId(typeIds.get(str(room, "type")));
            if (!lodging.saveRoom(accId, rf)) {
                return error(400, ApiErrors.VALIDATION_FAILED, "Room '" + rf.getRoomNumber() + "' was not created.");
            }
        }
        final Map<String, String> roomIds = new LinkedHashMap<>();
        for (final LodgingViews.RoomRow row : lodging.roomRows(accId)) {
            roomIds.put(row.getRoomNumber(), row.getId());
        }
        return ok(Map.of("id", accId, "roomTypeIds", typeIds, "roomIds", roomIds));
    }

    /** Every accommodation the caller may use, as rows. */
    @GET
    @Path("accommodations")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response accommodations() {
        final LodgingCommands lodging = lodging();
        if (!lodging.canOpenLodgingAdmin()) {
            return error(403, ApiErrors.FORBIDDEN, "Lodging admin required.");
        }
        return ok(lodging.accommodationRows());
    }

    /**
     * Blocks rooms of a hotel for nights that are not ours (another group, maintenance). Body:
     * {@code {roomIds:[...], start:"2026-09-21", end:"2026-09-26", reason}}, dates as ISO days with the
     * stay convention (nights {@code [start, end)}). Answers {@code {id}}.
     */
    @POST
    @Path("accommodations/{accId}/blocks")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createBlock(@PathParam("accId") final String accId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final HotelCommands hotel = hotel();
        if (!hotel.canEdit(accId)) {
            return error(403, ApiErrors.FORBIDDEN, "Accommodation admin required.");
        }
        final LodgingViews.BlockForm form = new LodgingViews.BlockForm();
        for (final Object id : listOf(body, "roomIds")) {
            form.getRoomIds().add(id.toString());
        }
        final String start = str(body, "start");
        final String end = str(body, "end");
        if (start != null && end != null) {
            form.setRange(List.of(LocalDate.parse(start), LocalDate.parse(end)));
        }
        form.setReason(str(body, "reason"));
        if (!hotel.saveBlock(accId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, "The block was not saved.");
        }
        // The command mints the id, so read it back: the newest block on exactly these nights.
        String created = null;
        for (final LodgingViews.BlockRow row : hotel.blockRows(accId)) {
            if (row.getStart().equals(form.start()) && row.getEnd().equals(form.end())) {
                created = row.getId();
            }
        }
        return ok(Map.of("id", created == null ? "" : created));
    }

    /** How full a hotel is, day by day, over every trip staying there. Query: {@code ?month=yyyy-MM}. */
    @GET
    @Path("accommodations/{accId}/calendar")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response calendar(@PathParam("accId") final String accId,
            @QueryParam("month") final String month) {
        final HotelCommands hotel = hotel();
        if (!hotel.canRead(accId)) {
            return error(403, ApiErrors.FORBIDDEN, "Lodging admin required.");
        }
        return ok(hotel.calendar(accId, month));
    }

    /**
     * Creates an offer on a trip. Body: {@code {name, accommodationId, roomTypeIds:[...] (or roomTypeId),
     * pricingModel, nightlyPrice, singleSupplement, minNights, validFrom, validUntil, defaultStart, defaultEnd,
     * tripEventId ("NEW" default), cancelFeeKind, cancelFeeAmount}}; dates default to the trip's. Answers
     * {@code {id, tripEventId}}.
     */
    @POST
    @Path("trips/{tripId}/offers")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createOffer(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip lodging manager required.");
        }
        final LodgingViews.OfferForm form = lodging.offerFormFor(tripId, null);
        form.setName(str(body, "name"));
        form.setAccommodationId(str(body, "accommodationId"));
        for (final Object typeId : listOf(body, "roomTypeIds")) {
            form.getRoomTypeIds().add(typeId.toString());
        }
        if (body.containsKey("roomTypeId")) {
            form.getRoomTypeIds().add(str(body, "roomTypeId"));
        }
        form.setTripEventId(body.containsKey("tripEventId") ? str(body, "tripEventId")
                : LodgingViews.OfferForm.NEW_EVENT);
        form.setPricingModel(body.containsKey("pricingModel") ? str(body, "pricingModel") : "PER_ROOM");
        form.setNightlyPrice(dbl(body, "nightlyPrice"));
        form.setSingleSupplement(dbl(body, "singleSupplement"));
        form.setMinNights(intOf(body, "minNights", 1));
        if (body.containsKey("validFrom")) {
            form.setValidFrom(LocalDateTime.parse(str(body, "validFrom")));
        }
        if (body.containsKey("validUntil")) {
            form.setValidUntil(LocalDateTime.parse(str(body, "validUntil")));
        }
        if (body.containsKey("defaultStart")) {
            form.setDefaultStart(LocalDateTime.parse(str(body, "defaultStart")));
        }
        if (body.containsKey("defaultEnd")) {
            form.setDefaultEnd(LocalDateTime.parse(str(body, "defaultEnd")));
        }
        if (body.containsKey("cancelFeeKind")) {
            form.setCancelFeeKind(str(body, "cancelFeeKind"));
        }
        form.setCancelFeeAmount(dbl(body, "cancelFeeAmount"));
        if (!lodging.saveOffer(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, "The offer was not created.");
        }
        final ReservationOffer offer = lodging.getOffers(tripId).stream()
                .filter(o -> form.getName().equals(o.getName())).reduce((a, b) -> b).orElse(null);
        return offer == null ? error(500, ApiErrors.INTERNAL, "Offer saved but not found.")
                : ok(Map.of("id", offer.getId().getValue(), "tripEventId", offer.getTripEventId()));
    }

    /**
     * Creates reservations. Body: {@code {offerId, personIds:[...], start, end, roomId, shareOneRoom,
     * notes}}; dates default to the offer's. Answers {@code {created, ids:[...]}} (the ids of the caller's
     * new ACTIVE reservations on that offer).
     */
    @POST
    @Path("trips/{tripId}/reservations")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createReservations(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip lodging manager required.");
        }
        final LodgingViews.ReservationForm form = new LodgingViews.ReservationForm();
        form.setOfferId(str(body, "offerId"));
        final ReservationOffer offer = lodging.findOffer(tripId, form.getOfferId());
        if (offer == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such offer on this trip.");
        }
        lodging.applyOfferDefaults(form, offer);
        for (final Object id : listOf(body, "personIds")) {
            form.getPersonIds().add(id.toString());
        }
        if (body.containsKey("start")) {
            form.setStart(LocalDateTime.parse(str(body, "start")));
        }
        if (body.containsKey("end")) {
            form.setEnd(LocalDateTime.parse(str(body, "end")));
        }
        form.setRoomId(str(body, "roomId"));
        form.setNotes(str(body, "notes"));
        form.setShareOneRoom(Boolean.TRUE.equals(body.get("shareOneRoom")));
        final int created = lodging.createReservations(tripId, form);
        if (created == 0) {
            // The command puts WHY on the form (a page shows it in the dialog); a bare "no reservation was
            // created" sent a webtest failure hunting through the server log for the actual refusal.
            final String why = form.getProblem();
            return error(400, ApiErrors.VALIDATION_FAILED,
                    (why == null || why.isBlank()) ? "No reservation was created." : why);
        }
        final List<String> ids = new ArrayList<>();
        for (final String personId : form.getPersonIds()) {
            for (final Reservation res : lodging.activeReservationsFor(tripId, Person.Id.from(personId))) {
                if (offer.getId().equals(res.getOfferId()) && !ids.contains(res.getId().getValue())) {
                    ids.add(res.getId().getValue());
                }
            }
        }
        return ok(Map.of("created", created, "ids", ids));
    }

    /** The trip's reservations, as the admin table's rows (cancelled included). */
    @GET
    @Path("trips/{tripId}/reservations")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response reservations(@PathParam("tripId") final String tripId) {
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip lodging manager required.");
        }
        return ok(lodging.reservationRows(tripId, true));
    }

    /** Assigns (or, with a blank {@code roomId}, clears) a reservation's room. Body: {@code {roomId, force}}. */
    @PUT
    @Path("trips/{tripId}/reservations/{reservationId}/room")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response assignRoom(@PathParam("tripId") final String tripId,
            @PathParam("reservationId") final String reservationId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip lodging manager required.");
        }
        final Reservation res = lodging.findReservation(tripId, reservationId);
        if (res == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such reservation.");
        }
        final String roomId = str(body, "roomId");
        if (roomId == null || roomId.isBlank()) {
            return lodging.unassignRoom(tripId, reservationId) ? ok(Map.of("assigned", false))
                    : error(400, ApiErrors.VALIDATION_FAILED, "The room was not cleared.");
        }
        final LodgingViews.AssignOutcome outcome = lodging.assignRoom(tripId, reservationId, roomId,
                Boolean.TRUE.equals(body.get("force")), null, null);
        return outcome.isAssigned() ? ok(outcome) : error(409, ApiErrors.CONFLICT, outcome.getMessage());
    }

    private static String str(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value == null) ? null : value.toString();
    }

    private static Double dbl(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value instanceof Number n) ? n.doubleValue() : null;
    }

    private static int intOf(final Map<String, Object> body, final String key, final int fallback) {
        final Object value = (body == null) ? null : body.get(key);
        return (value instanceof Number n) ? n.intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value instanceof List<?> l) ? (List<Map<String, Object>>) l : List.of();
    }

    private static List<?> listOf(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value instanceof List<?> l) ? l : List.of();
    }
}
