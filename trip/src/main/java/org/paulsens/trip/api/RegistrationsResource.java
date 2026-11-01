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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.CheckinCommands;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.CheckoutCommands;
import org.paulsens.trip.action.PaymentCommands;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.action.WaiverCommands;
import org.paulsens.trip.api.dto.CheckinCountsDto;
import org.paulsens.trip.api.dto.CheckoutRequest;
import org.paulsens.trip.api.dto.RegisterPartyRequest;
import org.paulsens.trip.api.dto.RegisterPartyResponse;
import org.paulsens.trip.api.dto.RegistrationDto;
import org.paulsens.trip.api.mapper.RegistrationMapper;
import org.paulsens.trip.checkout.CheckoutProblem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationDraft;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;

/**
 * Registrations for one trip.
 *
 * <p>Nested under the trip because a registration has no identity without one -- its key is (tripId, userId), and
 * there is no meaningful "all registrations" query for a client to make.
 */
@Slf4j
@Path("trips/{tripId}/registrations")
@TripApi
public class RegistrationsResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.REGISTRATIONS_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * Everyone registered on this trip. Trip staff only.
     *
     * <p>Answers are stripped from every entry: this is the roster view, and a list endpoint is the easiest
     * place to leak a hundred people's dietary and medical notes at once. A caller who needs one person's
     * answers fetches that one registration, where the check is made per person. The ticket goes too: it is a
     * bearer secret, and a roster is exactly the listing that gets exported and forwarded.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list(@PathParam("tripId") final String tripId) {
        if (!isTripStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip staff required.");
        }
        return ok(Beans.get(RegistrationCommands.class).getRegistrations(tripId).stream()
                .map(reg -> RegistrationMapper.INSTANCE.toDto(reg).withoutSecrets())
                .toList());
    }

    /**
     * The trip's priced-checkout policy (docs/checkout.md): on/off, auto-confirm, the window and the age bands, for
     * any signed-in caller (the registration form shows them); the discount CODES only to the trip's managers and
     * registration admins, since a code is a secret its holders were given. Prices are the trip's typed questions
     * ({@code GET /api/trips/{id}}), never duplicated here.
     */
    @GET
    @Path("pricing")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response pricing(@PathParam("tripId") final String tripId) {
        final Trip trip = requireTrip(tripId);
        final org.paulsens.trip.model.CheckoutConfig policy = trip.getCheckout();
        if (mayManagePricing(tripId) || isTripStaff(tripId)) {
            return ok(policy);
        }
        return ok(new org.paulsens.trip.model.CheckoutConfig(policy.getEnabled(), policy.getAutoConfirmPaid(),
                policy.getOpensAt(), policy.getClosesAt(), policy.getAgeBands(), List.of()));
    }

    /**
     * Replaces the trip's priced-checkout policy. Trip managers (and site admins) only, like the trip editor's
     * dialog; a policy with problems (overlapping age bands, a duplicate code, a window that closes before it opens)
     * is refused whole with every problem in the message.
     */
    @PUT
    @Path("pricing")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response savePricing(
            @PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final org.paulsens.trip.model.CheckoutConfig body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!mayManagePricing(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip manager required.");
        }
        if (body == null) {
            return error(400, ApiErrors.VALIDATION_FAILED, "A pricing policy is required.");
        }
        final List<String> problems = body.problems();
        if (!problems.isEmpty()) {
            return error(400, ApiErrors.VALIDATION_FAILED, String.join(" ", problems));
        }
        // Load, mutate, save the SAME object: a DAO read hands back a copy.
        final Trip trip = requireTrip(tripId);
        trip.setCheckout(body);
        if (!Beans.get(org.paulsens.trip.action.TripCommands.class).saveTrip(trip)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the pricing policy.");
        }
        return ok(trip.getCheckout());
    }

    private boolean mayManagePricing(final String tripId) {
        return privileges().has(ApiPrivileges.TRIP_MGR, tripId);
    }

    /** How many registrations are awaiting approval. Trip staff only. */
    @GET
    @Path("pending-count")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response pendingCount(@PathParam("tripId") final String tripId) {
        if (!isTripStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip staff required.");
        }
        return ok(Map.of("pending", Beans.get(RegistrationCommands.class).getNumPending(tripId)));
    }

    // ------------------------------------------------------------------ check-in (docs/checkin.md)

    /**
     * Staff search for the check-in desk: a whole ticket code matches exactly that ticket, anything else matches
     * names and email by token ({@code CheckinCommands.lookup}). Listing entries: no answers, no ticket. Whoever may
     * check people in here (site admin, tripMgr, checkinStaff).
     */
    @GET
    @Path("lookup")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response lookup(@PathParam("tripId") final String tripId, @QueryParam("q") final String query) {
        requireTrip(tripId);
        final CheckinCommands checkin = callerBoundCheckin();
        if (!checkin.canCheckIn(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Check-in staff required.");
        }
        return ok(checkin.lookup(tripId, query).stream().map(row -> rowDto(tripId, row)).toList());
    }

    /**
     * The registration a scanned ticket belongs to (the app parses {@code t} from the QR's URL and asks here), with
     * its ticket, stamp, type and name; answers only for trip staff who may read them. 404 for no such ticket.
     */
    @GET
    @Path("ticket/{code}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response ticket(@PathParam("tripId") final String tripId, @PathParam("code") final String code) {
        final Trip trip = requireTrip(tripId);
        final CheckinCommands checkin = callerBoundCheckin();
        if (!checkin.canCheckIn(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Check-in staff required.");
        }
        final Registration reg = checkin.byTicket(tripId, code);
        if (reg == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such ticket for this event.");
        }
        return ok(checkinDto(checkin, trip, reg));
    }

    /**
     * Checks the person in. 409 {@code ALREADY_CHECKED_IN} (the first stamp is kept; the message says when and by
     * whom) or {@code NOT_CONFIRMED}; 404 when they are not registered at all.
     */
    @POST
    @Path("{personId}/checkin")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response checkIn(@PathParam("tripId") final String tripId,
            @PathParam("personId") final String personIdParam, @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Trip trip = requireTrip(tripId);
        requireSubject(Person.Id.from(personIdParam));
        final CheckinCommands checkin = callerBoundCheckin();
        final CheckinCommands.Result result = checkin.checkInOutcome(tripId, personIdParam);
        return switch (result.outcome()) {
            case CHECKED_IN -> ok(checkinDto(checkin, trip, result.registration()));
            case ALREADY_CHECKED_IN -> error(409, ApiErrors.ALREADY_CHECKED_IN,
                    "Already checked in " + checkin.stampText(result.registration()) + ".");
            case NOT_CONFIRMED -> error(409, ApiErrors.NOT_CONFIRMED, "The registration is "
                    + result.registration().getStatus().getDescription() + ", not Confirmed.");
            case NOT_FOUND -> error(404, ApiErrors.NOT_FOUND, "Not registered for this event.");
            case NOT_ALLOWED -> error(403, ApiErrors.FORBIDDEN, "Check-in staff required.");
            default -> error(500, ApiErrors.STORE_FAILED, "Could not save the check-in.");
        };
    }

    /** Clears a check-in (the wrong person was scanned): the trip's managers only. Idempotent. */
    @DELETE
    @Path("{personId}/checkin")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response clearCheckIn(@PathParam("tripId") final String tripId,
            @PathParam("personId") final String personIdParam, @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Trip trip = requireTrip(tripId);
        requireSubject(Person.Id.from(personIdParam));
        final CheckinCommands checkin = callerBoundCheckin();
        final CheckinCommands.Result result = checkin.clearCheckInOutcome(tripId, personIdParam);
        return switch (result.outcome()) {
            case CLEARED, NOT_CHECKED_IN -> ok(checkinDto(checkin, trip, result.registration()));
            case NOT_FOUND -> error(404, ApiErrors.NOT_FOUND, "Not registered for this event.");
            case NOT_ALLOWED -> error(403, ApiErrors.FORBIDDEN, "Only the event's managers may clear a check-in.");
            default -> error(500, ApiErrors.STORE_FAILED, "Could not clear the check-in.");
        };
    }

    /** The check-in counts; the money row only for a caller who sees the trip's finances. */
    @GET
    @Path("counts")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response counts(@PathParam("tripId") final String tripId) {
        requireTrip(tripId);
        final CheckinCommands checkin = callerBoundCheckin();
        if (!checkin.canViewCounts(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this event's counts.");
        }
        return ok(CheckinCountsDto.of(checkin.counts(tripId), checkin.money(tripId)));
    }

    /** Seam: the check-in bean bound to THIS caller (bearer or session); tests substitute. */
    protected CheckinCommands callerBoundCheckin() {
        return new CheckinCommands(this::caller);
    }

    /** One check-in registration on the wire: absolute ticket URL, type, name; answers for trip staff only. */
    private RegistrationDto checkinDto(final CheckinCommands checkin, final Trip trip, final Registration reg) {
        final RegistrationDto dto = RegistrationMapper.INSTANCE.toDto(reg);
        final Person person = findPerson(reg.getUserId());
        return (isTripStaff(trip.getId()) ? dto : dto.withoutOptions()).withCheckin(
                absoluteUrl(checkin.ticketUrl(trip, reg.getTicketCode())),
                CheckinCommands.registrationType(trip, reg),
                person == null ? null : CheckinCommands.displayName(person));
    }

    private static RegistrationDto rowDto(final String tripId, final RegistrationCommands.RegRow row) {
        final Registration.Status status = Registration.Status.fromDescription(row.getStatus());
        return new RegistrationDto(tripId, row.getUserId(), row.getCreated(), status == null ? null : status.name(),
                null, null, null, null, row.getCheckedInAt(), null, row.getRegistrationType(), row.getName());
    }

    /**
     * One person's registration, answers included when the caller is entitled to them.
     *
     * <p>{@code getRegistration} invents a transient NOT_REGISTERED record rather than returning null, so this
     * never 404s -- "not registered" is a state, not a missing resource, and a client rendering a Register
     * button needs to be told which it is.
     */
    @GET
    @Path("{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(
            @PathParam("tripId") final String tripId, @PathParam("personId") final String personIdParam) {
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canSeeAnswers(tripId, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this registration.");
        }
        // The registration may be transient, but the TRIP and PERSON must be real: without these checks the
        // invented record makes a nonexistent trip -- or a mistyped person id -- look like someone who simply
        // hasn't registered yet.
        final Trip trip = requireTrip(tripId);
        requireSubject(subject);
        final Registration reg = Beans.get(RegistrationCommands.class).getRegistration(tripId, subject);
        // The owner's own ticket (code and absolute URL) rides along: the app shows its QR at the door.
        return ok(RegistrationMapper.INSTANCE.toDto(reg)
                .withWaiversSatisfied(new WaiverCommands(this::caller).isSatisfied(trip, subject))
                .withCheckin(absoluteUrl(callerBoundCheckin().ticketUrl(trip, reg.getTicketCode())),
                        CheckinCommands.registrationType(trip, reg), null));
    }

    /**
     * Creates or updates a registration.
     *
     * <p>Status is only writable by trip staff. A traveller may edit their own ANSWERS at any time, but letting
     * them post a status would let anyone approve their own registration, which is the one decision this whole
     * workflow exists to put in a human's hands.
     */
    @PUT
    @Path("{personId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response save(
            @PathParam("tripId") final String tripId,
            @PathParam("personId") final String personIdParam,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final RegistrationDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canSeeAnswers(tripId, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to edit this registration.");
        }
        final boolean staff = isTripStaff(tripId);
        if (body != null && body.status() != null && !staff) {
            return error(403, ApiErrors.FORBIDDEN, "Only trip staff may change a registration's status.");
        }
        // getRegistration invents a record keyed to whatever was asked for, so without these checks a PUT to a
        // trip nobody created -- or a staff PUT with a mistyped person id -- saves a junk row under that key.
        final Trip trip = requireTrip(tripId);
        final Person person = requireSubject(subject);
        final RegistrationCommands registrations = Beans.get(RegistrationCommands.class);
        final Registration existing = registrations.getRegistration(tripId, subject);
        final Registration toSave = merge(existing, body, staff ? optionsOf(body)
                : withPaidAnswersKept(trip, existing, optionsOf(body)));
        if (!registrations.saveRegistration(toSave)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the registration.");
        }
        audit(trip, person, existing, toSave);
        // Echo the object just saved; a re-read can still serve the pre-save value while the cache catches up.
        return ok(RegistrationMapper.INSTANCE.toDto(toSave));
    }

    /**
     * Self-registration, the way the join page does it: a party of travelers the caller may act for (themselves
     * and the family members they manage; site admins anyone), each filed PENDING with their answers, one
     * party stamp across the submit, the digest choice parked, the office notified and the registrant mailed
     * once for the whole party. Already-registered travelers in the party get their answers updated instead,
     * when the {@code reg.allowEdits} setting permits. Refusals are per traveler, in the response, not a
     * status: one refused traveler never blocks the rest of the family.
     */
    @POST
    @Path("party")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response registerParty(
            @PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final RegisterPartyRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (body == null || body.travelers() == null || body.travelers().isEmpty()) {
            return error(400, ApiErrors.VALIDATION_FAILED, "Name at least one traveler.");
        }
        final Trip trip = requireTrip(tripId);
        final RegistrationCommands registrations = callerBoundRegistrations();
        final ChatCommands chat = new ChatCommands();
        final Map<String, Registration> regs = new LinkedHashMap<>();
        final Map<String, Object> selected = new LinkedHashMap<>();
        final Map<String, Object> digests = new LinkedHashMap<>();
        final Map<String, List<WaiverCommands.Agreement>> agreements = new LinkedHashMap<>();
        for (final RegisterPartyRequest.TravelerRequest traveler : body.travelers()) {
            if (traveler == null || traveler.personId() == null || traveler.personId().isBlank()) {
                return error(400, ApiErrors.VALIDATION_FAILED, "Every traveler needs a personId.");
            }
            final String key = traveler.personId().trim();
            if (regs.containsKey(key)) {
                return error(400, ApiErrors.VALIDATION_FAILED, "A traveler is named twice: " + key + ".");
            }
            final Person.Id travelerId = Person.Id.from(key);
            requireSubject(travelerId);
            final Registration existing = registrations.getRegistration(tripId, travelerId);
            regs.put(key, traveler.options() == null ? existing : withAnswers(existing, trip, traveler.options()));
            selected.put(key, Boolean.TRUE);
            digests.put(key, traveler.dailyDigest() != null ? traveler.dailyDigest()
                    : chat.digestChoiceOrDefault(existing));
            agreements.put(key, agreementsOf(traveler.waivers()));
        }
        final RegistrationCommands.PartyOutcome outcome = registrations.registerPartyOutcome(trip, selected, regs,
                digests, agreements, WaiverCommands.ClientInfo.of(TripWaiversResource.clientOf(body.client()),
                        request));
        final AuditCommands audit = Beans.get(AuditCommands.class);
        final List<RegistrationDto> registered = new ArrayList<>();
        for (final Person person : outcome.registered()) {
            audit.registered(person, trip, actor());
            // The saved row is the party's transient row with the stamps and the status the seam gave it
            // (Confirmed for someone already on the roster) -- echoed, not re-read.
            registered.add(RegistrationMapper.INSTANCE.toDto(regs.get(person.getId().getValue())
                    .withStatus(RegistrationCommands.initialStatus(trip, person.getId()))));
        }
        registrations.sendRegistrationMail(trip, outcome.registered());
        final List<RegistrationDto> updated = outcome.updated().stream()
                .map(person -> RegistrationMapper.INSTANCE.toDto(regs.get(person.getId().getValue())))
                .toList();
        return ok(new RegisterPartyResponse(registered, updated, outcome.refused()));
    }

    /**
     * The party's row for one traveler with the posted answers on it: only the trip's own question keys are
     * taken (the page's form can send nothing else), so a client cannot write the reserved stamps.
     */
    private static Registration withAnswers(final Registration existing, final Trip trip,
            final Map<String, String> posted) {
        final Map<String, String> options = new LinkedHashMap<>(existing.getOptions());
        if (trip.getRegOptions() != null) {
            for (final RegistrationOption option : trip.getRegOptions()) {
                if (posted.containsKey(option.getKey())) {
                    options.put(option.getKey(), posted.get(option.getKey()));
                }
            }
        }
        // withOptions, never the constructor: a rebuild would drop the ticket, payment and check-in fields.
        return existing.withOptions(options);
    }

    /** A traveler's document answers as the bean takes them; none given is an empty list. */
    private static List<WaiverCommands.Agreement> agreementsOf(final List<RegisterPartyRequest.WaiverAnswer> waivers) {
        if (waivers == null) {
            return List.of();
        }
        return waivers.stream()
                .filter(java.util.Objects::nonNull)
                .map(w -> new WaiverCommands.Agreement(w.waiverId(), w.version(), w.agreed(), w.authorityAttested()))
                .toList();
    }

    /** Seam: the bean bound to THIS caller (its per-traveler authorization needs one); tests substitute. */
    protected RegistrationCommands callerBoundRegistrations() {
        return new RegistrationCommands(this::caller);
    }

    // ------------------------------------------------------------------ priced checkout (docs/checkout.md)

    /**
     * The live price of a checkout party: each traveler's breakdown and problems, the party's credits, the fee when
     * the payer pays it, and the total. Writes nothing. The page's totals panel, through the same bean.
     */
    @POST
    @Path("party/quote")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response quoteCheckout(@PathParam("tripId") final String tripId, final CheckoutRequest body) {
        final Trip trip = requireTrip(tripId);
        final CheckoutCommands checkout = callerBoundCheckout();
        final CheckoutParty party = checkoutParty(trip, checkout, body);
        final CheckoutCommands.Quote quote = checkout.quote(trip, party.draft(), party.people());
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("persons", quote.persons());
        result.put("problems", quote.problems());
        result.put("creditCents", quote.creditCents());
        result.put("feeCents", quote.feeCents());
        result.put("totalCents", quote.totalCents());
        result.put("payable", quote.payable());
        result.put("ready", quote.isReady());
        return ok(result);
    }

    /**
     * Starts the party's checkout (pay-first: nobody is registered until the payment is recorded). Answers how to
     * pay exactly like {@code POST /payments} ({@code paymentId} plus {@code approvalUrl}, or {@code clientSecret}
     * and {@code publishableKey} for the native sheet), completed through {@code POST /payments/{id}/complete},
     * which registers the party. A party that owes nothing is registered at once and answers the completion
     * ({@code status}, {@code message}). Every refusal is a 400 listing each problem with its code.
     */
    @POST
    @Path("party/checkout")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response startCheckout(
            @PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final CheckoutRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Trip trip = requireTrip(tripId);
        final CheckoutCommands checkout = callerBoundCheckout();
        final CheckoutParty party = checkoutParty(trip, checkout, body);
        if (body.returnUrl() == null || !PaymentsResource.isAllowed(body.returnUrl())) {
            return error(400, ApiErrors.BAD_REQUEST, "returnUrl must start with a configured allowed prefix.");
        }
        final Response refused = refusedBeforeStart(trip, checkout, party, body);
        if (refused != null) {
            return refused;
        }
        final CheckoutCommands.Started started = checkout.start(trip, party.draft(), party.people(),
                payerOf(body), Boolean.TRUE.equals(body.sandbox()), body.returnUrl());
        if (started == null) {
            return error(400, ApiErrors.BAD_REQUEST,
                    "The checkout could not be started (check the payer and the trip's payment setup).");
        }
        if (started.completion() != null) {
            return ok(completionOf(started.completion()));
        }
        final PaymentCommands.Start start = started.start();
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId", start.getPaymentId());
        result.put("approvalUrl", start.getApprovalUrl());
        result.put("publishableKey", start.getPublishableKey());
        result.put("clientSecret", start.getClientSecret());
        result.put("merchantName", start.getMerchantName());
        return ok(result);
    }

    /**
     * Registers the party as paid OUTSIDE any processor: a check, cash, a comp or other. Registration admins only
     * (the website's "Record offline payment"). The amount must equal the party's total unless {@code partial}.
     * Answers the completion.
     */
    @POST
    @Path("party/offline")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response recordOfflineCheckout(
            @PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final CheckoutRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Trip trip = requireTrip(tripId);
        final CheckoutCommands checkout = callerBoundCheckout();
        if (!checkout.canAdminister(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Registration admin required.");
        }
        final CheckoutParty party = checkoutParty(trip, checkout, body);
        final CheckoutRequest.Offline offline = body.offline();
        if (offline == null) {
            return error(400, ApiErrors.VALIDATION_FAILED, "Say how the payment was made (offline).");
        }
        final Response refused = refusedBeforeStart(trip, checkout, party, body);
        if (refused != null) {
            return refused;
        }
        final PaymentCommands.Completion completion = checkout.recordOffline(trip, party.draft(), party.people(),
                payerOf(body), offline.method(), offline.reference(),
                offline.amountCents() == null ? null : offline.amountCents() / 100.0, offline.receivedOn(),
                Boolean.TRUE.equals(offline.partial()));
        if (completion == null) {
            return error(400, ApiErrors.VALIDATION_FAILED, "The payment could not be recorded: the method must be "
                    + String.join(", ", CheckoutCommands.OFFLINE_METHODS)
                    + " and the amount the party's total (or less when partial).");
        }
        return ok(completionOf(completion));
    }

    /** A request's party as the page's draft holds it, and the people it names. */
    private record CheckoutParty(RegistrationDraft draft, List<Person> people,
            Map<String, List<WaiverCommands.Agreement>> agreements) {
    }

    /**
     * Turns the request into the page's draft: every named traveler selected, their answers (the trip's own
     * question keys only), code, ticked attestations and digest choice. Refuses (by throwing the response, like
     * {@code requireTrip}) a malformed party, a trip that does not use checkout, and a traveler the caller may
     * not act for: even a quote reveals the person's age band.
     */
    private CheckoutParty checkoutParty(final Trip trip, final CheckoutCommands checkout,
            final CheckoutRequest body) {
        if (body == null || body.travelers() == null || body.travelers().isEmpty()) {
            throw new WebApplicationException(error(400, ApiErrors.VALIDATION_FAILED, "Name at least one traveler."));
        }
        if (!checkout.isEnabled(trip)) {
            throw new WebApplicationException(error(409, ApiErrors.CONFLICT,
                    "This trip does not register through checkout; use POST .../registrations/party."));
        }
        final boolean admin = checkout.canAdminister(trip);
        final RegistrationDraft draft = new RegistrationDraft();
        final List<Person> people = new ArrayList<>();
        final Map<String, List<WaiverCommands.Agreement>> agreements = new LinkedHashMap<>();
        for (final CheckoutRequest.Traveler traveler : body.travelers()) {
            if (traveler == null || traveler.personId() == null || traveler.personId().isBlank()) {
                throw new WebApplicationException(error(400, ApiErrors.VALIDATION_FAILED,
                        "Every traveler needs a personId."));
            }
            final String key = traveler.personId().trim();
            if (draft.getSel().containsKey(key)) {
                throw new WebApplicationException(error(400, ApiErrors.VALIDATION_FAILED,
                        "A traveler is named twice: " + key + "."));
            }
            final Person.Id id = Person.Id.from(key);
            final Person person = requireSubject(id);
            if (!admin && !actsFor(id)) {
                throw new WebApplicationException(error(403, ApiErrors.FORBIDDEN,
                        "You cannot register " + key + "."));
            }
            people.add(person);
            draft.getSel().put(key, Boolean.TRUE);
            draft.getRegs().put(key, withAnswers(new Registration(trip.getId(), id), trip,
                    traveler.options() == null ? Map.of() : traveler.options()));
            if (traveler.code() != null && !traveler.code().isBlank()) {
                draft.getCodes().put(key, traveler.code().trim());
            }
            if (traveler.attested() != null) {
                traveler.attested().forEach(choiceId -> draft.getAttest()
                        .put(RegistrationDraft.attestKey(key, choiceId), Boolean.TRUE));
            }
            if (traveler.dailyDigest() != null) {
                draft.getDigest().put(key, traveler.dailyDigest());
            }
            agreements.put(key, agreementsOf(traveler.waivers()));
        }
        return new CheckoutParty(draft, people, agreements);
    }

    /**
     * Records the party's document answers (consent stands even if the payment is abandoned, as in party
     * registration), then answers a 400 listing every reason the bean would refuse to go on, or null.
     */
    private Response refusedBeforeStart(final Trip trip, final CheckoutCommands checkout, final CheckoutParty party,
            final CheckoutRequest body) {
        final WaiverCommands waivers = callerBoundWaivers();
        final WaiverCommands.ClientInfo client = WaiverCommands.ClientInfo.of(
                TripWaiversResource.clientOf(body.client()), request);
        party.agreements().forEach((personId, agreements) -> applyAgreements(waivers, trip, personId, agreements,
                client));
        final List<CheckoutProblem> problems = checkout.problemsBeforeStart(trip, party.draft(), party.people());
        if (problems.isEmpty()) {
            return null;
        }
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("error", ApiErrors.VALIDATION_FAILED);
        entity.put("message", String.join(" ", problems.stream().map(CheckoutProblem::message).toList()));
        entity.put("problems", problems);
        return Response.status(400).type(negotiatedType()).header("Vary", "Accept").entity(entity).build();
    }

    private static void applyAgreements(final WaiverCommands waivers, final Trip trip, final String personId,
            final List<WaiverCommands.Agreement> agreements, final WaiverCommands.ClientInfo client) {
        if (!agreements.isEmpty()) {
            waivers.applyAgreements(trip, Person.Id.from(personId), agreements, client);
        }
    }

    private static Person.Id payerOf(final CheckoutRequest body) {
        return body.payerId() == null || body.payerId().isBlank() ? null : Person.Id.from(body.payerId().trim());
    }

    private static Map<String, Object> completionOf(final PaymentCommands.Completion completion) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", completion.getStatus());
        result.put("message", completion.getMessage());
        result.put("dryRunLines", completion.getDryRunLines());
        return result;
    }

    /** Seam: the checkout bean bound to THIS caller; tests substitute. */
    protected CheckoutCommands callerBoundCheckout() {
        return new CheckoutCommands(this::caller);
    }

    /** Seam: the documents bean bound to THIS caller; tests substitute. */
    protected WaiverCommands callerBoundWaivers() {
        return new WaiverCommands(this::caller);
    }

    /** The room this person is assigned to on this trip. */
    @GET
    @Path("{personId}/room")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response room(
            @PathParam("tripId") final String tripId, @PathParam("personId") final String personIdParam) {
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canSeeAnswers(tripId, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this room assignment.");
        }
        // getRoomPDV CREATES AND SAVES a blank record on a miss -- convenient for the JSF room editor, but here
        // it means a GET with a garbage tripId OR a garbage personId writes a junk person_data row. Refuse both
        // before it can.
        requireTrip(tripId);
        requireSubject(subject);
        final PersonDataValue pdv = Beans.get(RegistrationCommands.class).getRoomPDV(tripId, subject);
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("tripId", tripId);
        result.put("personId", personIdParam);
        result.put("room", pdv == null ? null : pdv.getContent());
        return ok(result);
    }

    /** Assigns a room. Trip staff only -- travellers do not room themselves. */
    @PUT
    @Path("{personId}/room")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveRoom(
            @PathParam("tripId") final String tripId,
            @PathParam("personId") final String personIdParam,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!isTripStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip staff required.");
        }
        requireTrip(tripId);
        requireSubject(Person.Id.from(personIdParam));
        final Object room = body == null ? null : body.get("room");
        // The value is passed in rather than read back out of the PDV on purpose: saveRoom documents that a
        // re-read here can hand back the pre-save value in production.
        if (!Beans.get(RegistrationCommands.class)
                .saveRoom(tripId, Person.Id.from(personIdParam), room == null ? "" : room.toString())) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the room.");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("tripId", tripId);
        result.put("personId", personIdParam);
        result.put("room", room);
        return ok(result);
    }

    /**
     * Applies the submitted fields to the stored registration.
     *
     * <p>{@code Registration} is immutable in the ways that matter ({@code withStatus} returns a copy), so this
     * builds the object to save rather than mutating one. Absent fields are left as they were.
     */
    private static Registration merge(final Registration existing, final RegistrationDto body,
            final Map<String, String> options) {
        if (body == null) {
            return existing;
        }
        final Registration withOptions = (options == null) ? existing : existing.withOptions(options);
        return (body.status() == null) ? withOptions : withOptions.withStatus(parseStatus(body.status()));
    }

    private static Map<String, String> optionsOf(final RegistrationDto body) {
        return body == null ? null : body.options();
    }

    /** A traveler's PUT never changes what they paid for (docs/checkout.md); staff edits go through as posted. */
    private static Map<String, String> withPaidAnswersKept(final Trip trip, final Registration existing,
            final Map<String, String> posted) {
        return posted == null ? null : CheckoutCommands.keepPaidAnswers(trip, existing.getOptions(), posted);
    }

    /**
     * Reads a status off the wire.
     *
     * <p>Deliberately NOT {@code withStatusString}, which is {@code Status.fromDescription} and matches the
     * DISPLAY text ("Pending"). The wire carries {@code name()} ("PENDING"), so routing input through that
     * method means a client that GETs a registration and PUTs it back unchanged parses its own status as null
     * and silently wipes it -- the most ordinary request a client can make, and a corrupting one.
     *
     * <p>Lenient on input, strict on output: the display form is still accepted, because it is what the model's
     * own {@code @JsonValue} produces and something is bound to send it.
     */
    private static Registration.Status parseStatus(final String status) {
        try {
            return Registration.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            final Registration.Status byDescription = Registration.Status.fromDescription(status.trim());
            if (byDescription == null) {
                throw new IllegalArgumentException("Unknown registration status: " + status);
            }
            return byDescription;
        }
    }

    /**
     * Records the registration events the XHTML pages record, since {@code saveRegistration} does not audit.
     *
     * <p>Only a STATUS change is worth a record -- a traveller correcting a typo in their answers is not a
     * registration event, and auditing every save would bury the approvals nobody could then find.
     */
    private void audit(
            final Trip trip, final Person person, final Registration before, final Registration after) {
        if (before.getStatus() == after.getStatus()) {
            return;
        }
        final AuditCommands audit = Beans.get(AuditCommands.class);
        if (after.getStatus() == Registration.Status.NOT_REGISTERED) {
            audit.registrationRemoved(person, trip, actor());
        } else {
            audit.registered(person, trip, actor());
        }
    }

    /** Staff on this trip: a manager, a viewer, a registration admin, or a site admin. */
    private boolean isTripStaff(final String tripId) {
        final ApiPrivileges privileges = privileges();
        return privileges.has(ApiPrivileges.TRIP_MGR, tripId) || privileges.has(ApiPrivileges.TRIP_VIEW, tripId)
                || privileges.has(ApiPrivileges.REGISTRATION_ADMIN, tripId);
    }

    /** The traveller themselves, whoever manages their booking, or trip staff. */
    private boolean canSeeAnswers(final String tripId, final Person.Id subject) {
        return actsFor(subject) || isTripStaff(tripId);
    }
}
