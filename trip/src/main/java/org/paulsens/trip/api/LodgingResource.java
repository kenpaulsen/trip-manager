package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.HotelCommands;
import org.paulsens.trip.action.LodgingCommands;
import org.paulsens.trip.action.LodgingViews;
import org.paulsens.trip.action.RatePlanCommands;
import org.paulsens.trip.action.RatePlanViews;
import org.paulsens.trip.i18n.Text;
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

    private RatePlanCommands ratePlans() {
        return new RatePlanCommands(this::caller);
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canCreateAccommodation()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.adminRequired"));
        }
        final LodgingViews.AccommodationForm form = new LodgingViews.AccommodationForm();
        form.setName(JsonBody.str(body, "name"));
        form.setEmail(JsonBody.str(body, "email"));
        form.setPhone(JsonBody.str(body, "phone"));
        form.setWebsite(JsonBody.str(body, "website"));
        form.setStreet(JsonBody.str(body, "street"));
        form.setCity(JsonBody.str(body, "city"));
        form.setZip(JsonBody.str(body, "zip"));
        form.setCountry(JsonBody.str(body, "country"));
        form.setContactEmail(JsonBody.str(body, "contactEmail"));
        form.setContactFirst(JsonBody.str(body, "contactFirst"));
        form.setContactLast(JsonBody.str(body, "contactLast"));
        form.setForce(true);
        final String accId = lodging.saveAccommodation(form, JsonBody.str(body, "orgId"));
        if (accId.isEmpty()) {
            return error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.lodging.accommodationNotCreated"));
        }
        final Map<String, String> typeIds = new LinkedHashMap<>();
        for (final Map<String, Object> type : list(body, "roomTypes")) {
            final LodgingViews.RoomTypeForm rt = new LodgingViews.RoomTypeForm();
            rt.setName(JsonBody.str(type, "name"));
            rt.setMinPeople(JsonBody.intOf(type, "minPeople", 1));
            rt.setMaxPeople(JsonBody.intOf(type, "maxPeople", 2));
            if (!lodging.saveRoomType(accId, rt)) {
                return error(400, ApiErrors.VALIDATION_FAILED,
                        Text.get("api.lodging.roomTypeNotCreated", String.valueOf(rt.getName())));
            }
        }
        for (final LodgingViews.RoomTypeRow row : lodging.roomTypeRows(accId)) {
            typeIds.put(row.getName(), row.getId());
        }
        for (final Map<String, Object> room : list(body, "rooms")) {
            final LodgingViews.RoomForm rf = new LodgingViews.RoomForm();
            rf.setRoomNumber(JsonBody.str(room, "number"));
            rf.setFloor(JsonBody.str(room, "floor"));
            rf.setRoomTypeId(typeIds.get(JsonBody.str(room, "type")));
            if (!lodging.saveRoom(accId, rf)) {
                return error(400, ApiErrors.VALIDATION_FAILED,
                        Text.get("api.lodging.roomNotCreated", String.valueOf(rf.getRoomNumber())));
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
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.adminRequired"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final HotelCommands hotel = hotel();
        if (!hotel.canEdit(accId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.accommodationAdminRequired"));
        }
        final LodgingViews.BlockForm form = new LodgingViews.BlockForm();
        form.getRoomIds().addAll(JsonBody.strings(body, "roomIds"));
        final LocalDate start = JsonBody.date(body, "start");
        final LocalDate end = JsonBody.date(body, "end");
        if (start != null && end != null) {
            form.setRange(List.of(start, end));
        }
        form.setReason(JsonBody.str(body, "reason"));
        if (!hotel.saveBlock(accId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.lodging.blockNotSaved"));
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

    /**
     * Makes a person a Hotel Operator of the accommodation ({@code accommodationAdmin@acc}, the hotel page's
     * operators roster). Body: {@code {personId}}. Gate: an operator or global lodging admin of that hotel.
     * Answers {@code {ok:true}}; 404 for an unknown hotel or person.
     */
    @POST
    @Path("accommodations/{accId}/operators")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response addOperator(@PathParam("accId") final String accId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canEditAccommodation(accId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.accommodationAdminRequired"));
        }
        if (lodging.findAccommodation(accId) == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.lodging.noSuchAccommodation"));
        }
        final String personId = JsonBody.str(body, "personId");
        if (personId == null || personId.isBlank() || findPerson(Person.Id.from(personId)) == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.shared.noSuchPerson"));
        }
        return lodging.addManagerById(accId, personId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.lodging.operatorNotAdded"));
    }

    /**
     * Takes the Hotel Operator role off a person for the accommodation: the roster's Remove, for the fixtures
     * that granted it over the API and must hand the state back (a webtest's grant on a seeded persona would
     * otherwise outlive the test and open doors for a later class). Gate as {@link #addOperator}. Answers
     * {@code {ok:true}} whether or not the person held the role; 404 for an unknown hotel.
     */
    @DELETE
    @Path("accommodations/{accId}/operators/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response removeOperator(@PathParam("accId") final String accId,
            @PathParam("personId") final String personId, @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canEditAccommodation(accId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.accommodationAdminRequired"));
        }
        if (lodging.findAccommodation(accId) == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.lodging.noSuchAccommodation"));
        }
        lodging.removeManagerById(accId, personId);
        return ok(Map.of("ok", true));
    }

    /**
     * Creates a rate plan on a hotel. Body (dollars, ISO dates): {@code {name, roomTypeIds:[...], validFrom,
     * validUntil, costKnown, costPricingModel, costNightlyPrice, costSupplement, costExtras:[{label, basis,
     * amount}], notes}}. {@code costKnown} defaults to true. A plan carries the HOTEL side only: there are no
     * price keys (a participant price belongs to the trip's option, see {@code from-plan}). Gate: an operator
     * or global lodging admin of the hotel. Answers {@code {id}}.
     */
    @POST
    @Path("accommodations/{accId}/rate-plans")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createRatePlan(@PathParam("accId") final String accId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final RatePlanCommands ratePlans = ratePlans();
        if (!ratePlans.canEditCost(accId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.accommodationAdminRequired"));
        }
        final RatePlanViews.RatePlanForm form = ratePlans.planFormFor(accId, null);
        form.setName(JsonBody.str(body, "name"));
        form.getRoomTypeIds().addAll(JsonBody.strings(body, "roomTypeIds"));
        if (JsonBody.has(body, "validFrom")) {
            form.setValidFrom(JsonBody.date(body, "validFrom"));
        }
        if (JsonBody.has(body, "validUntil")) {
            form.setValidUntil(JsonBody.date(body, "validUntil"));
        }
        form.setCostKnown(!JsonBody.has(body, "costKnown") || JsonBody.bool(body, "costKnown", false));
        form.setCostPricingModel(JsonBody.str(body, "costPricingModel", "PER_ROOM"));
        form.setCostNightlyPrice(JsonBody.dbl(body, "costNightlyPrice"));
        form.setCostSupplement(JsonBody.dbl(body, "costSupplement"));
        form.setCostExtras(extraForms(list(body, "costExtras")));
        form.setNotes(JsonBody.str(body, "notes"));
        if (!ratePlans.savePlan(form)) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    JsonBody.problemOr(form.getProblem(), Text.get("api.lodging.ratePlanNotSaved")));
        }
        // The command writes the minted id back onto the form.
        return ok(Map.of("id", form.getPlanId() == null ? "" : form.getPlanId()));
    }

    /** The hotel's rate plans as rows (the hotel's side only, so the same rows for every reader). */
    @GET
    @Path("accommodations/{accId}/rate-plans")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response ratePlans(@PathParam("accId") final String accId) {
        if (!hotel().canRead(accId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.adminRequired"));
        }
        return ok(ratePlans().rows(accId));
    }

    /** How full a hotel is, day by day, over every trip staying there. Query: {@code ?month=yyyy-MM}. */
    @GET
    @Path("accommodations/{accId}/calendar")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response calendar(@PathParam("accId") final String accId,
            @QueryParam("month") final String month) {
        final HotelCommands hotel = hotel();
        if (!hotel.canRead(accId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.adminRequired"));
        }
        return ok(hotel.calendar(accId, month));
    }

    /**
     * Creates an offer on a trip. Body: {@code {name, accommodationId, roomTypeIds:[...] (or roomTypeId),
     * pricingModel, nightlyPrice, singleSupplement, minNights, validFrom, validUntil, defaultStart, defaultEnd,
     * tripEventId ("NEW" default), cancelFeeKind, cancelFeeAmount}}; dates default to the trip's. The price
     * side keeps its legacy meaning (a missing {@code nightlyPrice} is $0, still PRICED); {@code priceKnown:false}
     * saves the option as "needs participant pricing". The cost side is optional: {@code costPricingModel,
     * costNightlyPrice, costSupplement, costExtras:[{label, basis, amount}]} (dollars), stated when any is given.
     * Answers {@code {id, tripEventId}}.
     */
    @POST
    @Path("trips/{tripId}/offers")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createOffer(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.tripManagerRequired"));
        }
        final LodgingViews.OfferForm form = lodging.offerFormFor(tripId, null);
        form.setName(JsonBody.str(body, "name"));
        form.setAccommodationId(JsonBody.str(body, "accommodationId"));
        form.getRoomTypeIds().addAll(JsonBody.strings(body, "roomTypeIds"));
        if (JsonBody.has(body, "roomTypeId")) {
            form.getRoomTypeIds().add(JsonBody.str(body, "roomTypeId"));
        }
        form.setTripEventId(JsonBody.str(body, "tripEventId", LodgingViews.OfferForm.NEW_EVENT));
        form.setPricingModel(JsonBody.str(body, "pricingModel", "PER_ROOM"));
        form.setNightlyPrice(JsonBody.dbl(body, "nightlyPrice"));
        form.setSingleSupplement(JsonBody.dbl(body, "singleSupplement"));
        form.setPriceKnown(!JsonBody.has(body, "priceKnown") || JsonBody.bool(body, "priceKnown", false));
        applyCostSide(form, body);
        form.setMinNights(JsonBody.intOf(body, "minNights", 1));
        if (JsonBody.has(body, "validFrom")) {
            form.setValidFrom(JsonBody.dateTime(body, "validFrom"));
        }
        if (JsonBody.has(body, "validUntil")) {
            form.setValidUntil(JsonBody.dateTime(body, "validUntil"));
        }
        if (JsonBody.has(body, "defaultStart")) {
            form.setDefaultStart(JsonBody.dateTime(body, "defaultStart"));
        }
        if (JsonBody.has(body, "defaultEnd")) {
            form.setDefaultEnd(JsonBody.dateTime(body, "defaultEnd"));
        }
        if (JsonBody.has(body, "cancelFeeKind")) {
            form.setCancelFeeKind(JsonBody.str(body, "cancelFeeKind"));
        }
        form.setCancelFeeAmount(JsonBody.dbl(body, "cancelFeeAmount"));
        // Which ids existed BEFORE the save is how this call knows which offer is its own; see createdOffer.
        final Set<String> before = offerIds(lodging.getOffers(tripId));
        if (!lodging.saveOffer(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, JsonBody.problemOr(form.getProblem(),
                    Text.get("api.lodging.offerNotCreated")));
        }
        final ReservationOffer offer = createdOffer(lodging.getOffers(tripId), before, form.getName());
        return offer == null ? error(500, ApiErrors.INTERNAL, Text.get("api.lodging.offerSavedNotFound"))
                : ok(Map.of("id", offer.getId().getValue(), "tripEventId", offer.getTripEventId()));
    }

    /**
     * Puts a hotel's rate plan on the trip as a new lodging option that FOLLOWS the plan (its COST side is
     * copied from the plan now and on every plan save). Body: {@code {accId, planId}} plus the option's
     * optional PARTICIPANT PRICE, {@code priceKnown, pricingModel, nightlyPrice, singleSupplement} (dollars):
     * the option is priced when {@code priceKnown} is true, or when it is absent and any price key is present;
     * otherwise it is created "needs participant pricing". The same plan may be attached again. Gate: trip
     * lodging manager. Answers {@code {id, tripEventId}} like a plain offer create.
     */
    @POST
    @Path("trips/{tripId}/offers/from-plan")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createOfferFromPlan(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.tripManagerRequired"));
        }
        final String accId = JsonBody.str(body, "accId");
        final String planId = JsonBody.str(body, "planId");
        final Set<String> before = offerIds(lodging.getOffers(tripId));
        final RatePlanViews.AttachForm price = attachPrice(tripId, body);
        if (!ratePlans().attach(tripId, accId, planId, price)) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    JsonBody.problemOr(price.getProblem(), Text.get("api.lodging.ratePlanNotAttached")));
        }
        ReservationOffer created = null;
        for (final ReservationOffer offer : lodging.getOffers(tripId)) {
            if (!before.contains(offer.getId().getValue()) && planId != null
                    && planId.equals(offer.getRatePlanId())) {
                created = offer;
            }
        }
        return created == null ? error(500, ApiErrors.INTERNAL, Text.get("api.lodging.offerSavedNotFound"))
                : ok(Map.of("id", created.getId().getValue(), "tripEventId", created.getTripEventId()));
    }

    /**
     * Cuts a lodging option loose from the rate plan it follows: its schedules stay as they are, the link
     * clears, no bill moves. Gate: trip lodging manager. Answers {@code {ok:true}}.
     */
    @POST
    @Path("trips/{tripId}/offers/{offerId}/detach")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response detachOffer(@PathParam("tripId") final String tripId,
            @PathParam("offerId") final String offerId, @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.tripManagerRequired"));
        }
        if (lodging.findOffer(tripId, offerId) == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.lodging.noSuchOffer"));
        }
        return ratePlans().detach(tripId, offerId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.lodging.optionNotDetached"));
    }

    /** The optional price side of a from-plan body: priced on {@code priceKnown}, or when any price key is present. */
    private static RatePlanViews.AttachForm attachPrice(final String tripId, final Map<String, Object> body) {
        final RatePlanViews.AttachForm price = new RatePlanViews.AttachForm();
        price.setTripId(tripId);
        final boolean anyKey = JsonBody.has(body, "pricingModel") || JsonBody.has(body, "nightlyPrice")
                || JsonBody.has(body, "singleSupplement");
        price.setPriceKnown(JsonBody.has(body, "priceKnown") ? JsonBody.bool(body, "priceKnown", false) : anyKey);
        price.setPricingModel(JsonBody.str(body, "pricingModel", "PER_ROOM"));
        price.setNightlyPrice(JsonBody.dbl(body, "nightlyPrice"));
        price.setSingleSupplement(JsonBody.dbl(body, "singleSupplement"));
        return price;
    }

    /** The optional cost side of a create body: stated as soon as any cost key is present. */
    private static void applyCostSide(final LodgingViews.OfferForm form, final Map<String, Object> body) {
        final boolean stated = JsonBody.has(body, "costNightlyPrice") || JsonBody.has(body, "costPricingModel")
                || JsonBody.has(body, "costSupplement") || JsonBody.has(body, "costExtras");
        form.setCostKnown(stated);
        if (!stated) {
            return;
        }
        form.setCostPricingModel(JsonBody.str(body, "costPricingModel", "PER_ROOM"));
        form.setCostNightlyPrice(JsonBody.dbl(body, "costNightlyPrice"));
        form.setCostSupplement(JsonBody.dbl(body, "costSupplement"));
        form.setCostExtras(extraForms(list(body, "costExtras")));
    }

    private static Set<String> offerIds(final List<ReservationOffer> offers) {
        final Set<String> ids = new HashSet<>();
        for (final ReservationOffer offer : offers) {
            ids.add(offer.getId().getValue());
        }
        return ids;
    }

    /**
     * The offer a create just added: the one whose id was not there before it ran.
     *
     * <p>This used to be found by NAME, and two offers on one trip may share one: a trip that uses two hotels
     * has a "Double room" option at each. The last match in list order won, that order is not guaranteed, and
     * so the endpoint could hand back the OTHER hotel's offer. A caller that reserved on the id it was given
     * then booked the wrong hotel, and assigning a room from the one it meant was refused with "That room is
     * not at the accommodation" -- which is how this was found, as a webtest that failed about half the time.
     *
     * <p>The name is kept only to choose between offers created CONCURRENTLY, which is the one case the id
     * difference cannot settle on its own; the old code was in that case on every single call.
     */
    private static ReservationOffer createdOffer(final List<ReservationOffer> after, final Set<String> before,
            final String name) {
        ReservationOffer found = null;
        for (final ReservationOffer offer : after) {
            if (before.contains(offer.getId().getValue())) {
                continue;
            }
            if (found == null || (name != null && name.equals(offer.getName()))) {
                found = offer;
            }
        }
        return found;
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.tripManagerRequired"));
        }
        final LodgingViews.ReservationForm form = new LodgingViews.ReservationForm();
        form.setOfferId(JsonBody.str(body, "offerId"));
        final ReservationOffer offer = lodging.findOffer(tripId, form.getOfferId());
        if (offer == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.lodging.noSuchOffer"));
        }
        lodging.applyOfferDefaults(form, offer);
        form.getPersonIds().addAll(JsonBody.strings(body, "personIds"));
        if (JsonBody.has(body, "start")) {
            form.setStart(JsonBody.dateTime(body, "start"));
        }
        if (JsonBody.has(body, "end")) {
            form.setEnd(JsonBody.dateTime(body, "end"));
        }
        form.setRoomId(JsonBody.str(body, "roomId"));
        form.setNotes(JsonBody.str(body, "notes"));
        form.setShareOneRoom(JsonBody.bool(body, "shareOneRoom", false));
        final int created = lodging.createReservations(tripId, form);
        if (created == 0) {
            // The command puts WHY on the form (a page shows it in the dialog); a bare "no reservation was
            // created" sent a webtest failure hunting through the server log for the actual refusal.
            final String why = form.getProblem();
            return error(400, ApiErrors.VALIDATION_FAILED,
                    (why == null || why.isBlank()) ? Text.get("api.lodging.noReservationCreated") : why);
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
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.tripManagerRequired"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        if (!lodging.canAssignRooms(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.tripManagerOrOperatorRequired"));
        }
        final Reservation res = lodging.findReservation(tripId, reservationId);
        if (res == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.lodging.noSuchReservation"));
        }
        // The bean refuses too, but as a growl-shaped outcome; the API owes the caller a 403, not a 409.
        final String accId = (res.getAccommodationId() == null) ? null : res.getAccommodationId().getValue();
        if (!lodging.canAssignAt(tripId, accId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.notYourHotel"));
        }
        final String roomId = JsonBody.str(body, "roomId");
        if (roomId == null || roomId.isBlank()) {
            return lodging.unassignRoom(tripId, reservationId) ? ok(Map.of("assigned", false))
                    : error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.lodging.roomNotCleared"));
        }
        final LodgingViews.AssignOutcome outcome = lodging.assignRoom(tripId, reservationId, roomId,
                JsonBody.bool(body, "force", false), null, null);
        return outcome.isAssigned() ? ok(outcome) : error(409, ApiErrors.CONFLICT, outcome.getMessage());
    }

    /**
     * Sets the HOTEL COST side of one lodging option -- the operator's path, which can never touch the price.
     * Body (dollars): {@code {costKnown, costPricingModel, costNightlyPrice, costSupplement,
     * costExtras:[{label, basis, amount}]}}; {@code costKnown} defaults to true, false clears the stated cost
     * (back to "not set", which blocks the Room Invoice). Gate: trip lodging manager or an operator of the
     * option's hotel. Answers {@code {ok:true, cost}} with the cost label.
     */
    @PUT
    @Path("trips/{tripId}/offers/{offerId}/cost")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response setOfferCost(@PathParam("tripId") final String tripId,
            @PathParam("offerId") final String offerId, @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final LodgingCommands lodging = lodging();
        final ReservationOffer offer = lodging.findOffer(tripId, offerId);
        if (offer == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.lodging.noSuchOffer"));
        }
        if (!lodging.canManageTripLodging(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.lodging.costNeedsTripManager"));
        }
        final LodgingViews.OfferCostForm form = lodging.offerCostFormFor(tripId, offerId);
        form.setCostKnown(!JsonBody.has(body, "costKnown") || JsonBody.bool(body, "costKnown", false));
        if (JsonBody.has(body, "costPricingModel")) {
            form.setCostPricingModel(JsonBody.str(body, "costPricingModel"));
        }
        if (JsonBody.has(body, "costNightlyPrice")) {
            form.setCostNightlyPrice(JsonBody.dbl(body, "costNightlyPrice"));
        }
        if (JsonBody.has(body, "costSupplement")) {
            form.setCostSupplement(JsonBody.dbl(body, "costSupplement"));
        }
        if (JsonBody.has(body, "costExtras")) {
            form.setCostExtras(extraForms(list(body, "costExtras")));
        }
        if (!lodging.setOfferCost(form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, JsonBody.problemOr(form.getProblem(),
                    Text.get("api.lodging.costNotSaved")));
        }
        final LodgingViews.OfferCostForm saved = lodging.offerCostFormFor(tripId, offerId);
        return ok(Map.of("ok", true, "costKnown", saved.isCostKnown(), "costExtras", saved.getCostExtras().size()));
    }

    private static List<LodgingViews.CostExtraForm> extraForms(final List<Map<String, Object>> rows) {
        final List<LodgingViews.CostExtraForm> forms = new ArrayList<>();
        for (final Map<String, Object> row : rows) {
            forms.add(new LodgingViews.CostExtraForm(JsonBody.str(row, "label"), JsonBody.str(row, "basis"),
                    JsonBody.dbl(row, "amount")));
        }
        return forms;
    }

    /**
     * Sets or clears a stay's concession on ONE side. Path {@code side} is {@code price} (the participants'
     * bills shrink in place; trip lodging manager) or {@code cost} (what the hotel forgave us; that or the
     * hotel's operator). Body: {@code {kind, amount, reason}} -- {@code kind} a {@code Concession.Kind} name,
     * missing or "" clears; {@code amount} in dollars, or the percent for PERCENT. Answers
     * {@code {ok:true, label}} ("" when cleared).
     */
    @PUT
    @Path("trips/{tripId}/reservations/{reservationId}/concessions/{side}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response setConcession(@PathParam("tripId") final String tripId,
            @PathParam("reservationId") final String reservationId, @PathParam("side") final String side,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final boolean price = "price".equalsIgnoreCase(side);
        if (!price && !"cost".equalsIgnoreCase(side)) {
            return error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.lodging.sideInvalid"));
        }
        final LodgingCommands lodging = lodging();
        final Reservation res = lodging.findReservation(tripId, reservationId);
        if (res == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.lodging.noSuchReservation"));
        }
        final String accId = (res.getAccommodationId() == null) ? null : res.getAccommodationId().getValue();
        if (price ? !lodging.canManageTripLodging(tripId) : !lodging.canSetCost(tripId, accId)) {
            return error(403, ApiErrors.FORBIDDEN, price ? Text.get("api.lodging.tripManagerRequired")
                    : Text.get("api.lodging.tripManagerOrOperatorRequired"));
        }
        final LodgingViews.ConcessionForm form = new LodgingViews.ConcessionForm();
        form.setTripId(tripId);
        form.setReservationId(reservationId);
        form.setSide(price ? "PRICE" : "COST");
        form.setKind(JsonBody.str(body, "kind", ""));
        form.setAmount(JsonBody.dbl(body, "amount"));
        form.setReason(JsonBody.str(body, "reason"));
        if (!lodging.setConcession(form)) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    JsonBody.problemOr(form.getProblem(), Text.get("api.lodging.concessionNotSaved")));
        }
        return ok(Map.of("ok", true, "label", form.getCurrent() == null ? "" : form.getCurrent()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value instanceof List<?> l) ? (List<Map<String, Object>>) l : List.of();
    }
}
