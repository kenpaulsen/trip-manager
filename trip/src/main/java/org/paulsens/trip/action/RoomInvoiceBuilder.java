package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import org.paulsens.trip.action.ReportCommands.BilledVsCost;
import org.paulsens.trip.action.ReportCommands.BilledVsCostOption;
import org.paulsens.trip.action.ReportCommands.BilledVsCostPerson;
import org.paulsens.trip.action.ReportCommands.BilledVsCostSection;
import org.paulsens.trip.action.ReportCommands.InvoiceLine;
import org.paulsens.trip.action.ReportCommands.InvoiceOffer;
import org.paulsens.trip.action.ReportCommands.InvoicePersonLine;
import org.paulsens.trip.action.ReportCommands.InvoiceSection;
import org.paulsens.trip.action.ReportCommands.LineKind;
import org.paulsens.trip.action.ReportCommands.RoomInvoice;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.CostExtra;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.pay.CostExtras;
import org.paulsens.trip.pay.LodgingPricing;
import org.paulsens.trip.pay.LodgingPricing.Side;
import org.paulsens.trip.pay.MoneyMath;

/**
 * The arithmetic behind the Room Invoice, on either {@link Side} of a lodging option: the COST build is what
 * the hotels charge (the invoice the page and the PDF show, {@code offer.getCost()} STRICTLY -- never the price
 * in its place), the PRICE build is what the participants are billed ({@code offer.getPrice()}), and
 * {@link #compare} zips the two into the Billed vs Cost report. Everything a line says is derived from
 * {@link LodgingPricing}'s rules on the chosen side, so the per-person table equals the ledger (PRICE) or the
 * hotel's own bill (COST) to the cent.
 *
 * <p>An active-stay option with NO cost has two readings (owner decision 2026-10-26). The invoice is STRICT:
 * one such option refuses the whole build ({@code RoomInvoice.blocked}, {@code missingCost} naming the options
 * hotel-qualified, empty sections, zero totals), because an invoice with a hole in it would be sent to a
 * hotel as if complete. The Billed vs Cost comparison is LENIENT ({@code strict = false}): the option is left
 * out of the cost side (0 cost, its row reads "not set") and still named in {@code missingCost}, because that
 * report exists to show exactly such gaps.
 *
 * <p>One section per accommodation, one GROUP per lodging option, and lines whose arithmetic a reader can
 * check: {@code quantity x nights x rate == amount}. A per-person option bills GUESTS, and the single
 * supplement is a line of its own (the guests who were alone, for the nights they were alone); a per-room
 * option bills ROOMS, a room shared by a late arriver counting every night anybody sleeps in it. Guests or
 * rooms whose nights differ, or nights the schedule prices differently, go on separate lines.
 *
 * <p>Two kinds of line sit outside that arithmetic. An {@link LineKind#EXTRA} (COST side only) is one of the
 * option's {@code costExtras}: quantity = the count its basis selects ({@link CostExtras#quantity}), nights 1,
 * rate = the signed unit amount, so the product still holds and a coupon comes out negative. A
 * {@link LineKind#CONCESSION} is one stay's discount on this side: quantity 1, no nights or rate, a negative
 * amount equal to what {@link LodgingPricing} forgave on that stay's lines, and a note naming the guests, the
 * {@link Concession#label()} and the reason. The per-person rows show NET amounts, their rate suffixed with
 * the label, so the people total equals the option subtotal less the extras (which are never attributed to a
 * person); {@link InvoiceSection#getExtrasCents()} is that difference.
 *
 * <p>Package-private and static: {@link ReportCommands} is the only caller, and the tests drive it through
 * the package-private overloads there.
 */
final class RoomInvoiceBuilder {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ROOT);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
    private static final String UNKNOWN_HOTEL = "Unknown accommodation";

    /** The per-person table: by name, then a person's stays in date order. */
    private static final Comparator<InvoicePersonLine> PERSON_ORDER = Comparator
            .comparing(InvoicePersonLine::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(InvoicePersonLine::getFirstNight);
    private static final Comparator<BilledVsCostPerson> COMPARED_PERSON_ORDER = Comparator
            .comparing(BilledVsCostPerson::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(BilledVsCostPerson::getFirstNight, Comparator.nullsLast(Comparator.naturalOrder()));

    private RoomInvoiceBuilder() {
    }

    /** The STRICT invoice on one side: {@link #build(Side, List, Function, Function, Function, boolean)}. */
    static RoomInvoice build(final Side side, final List<Reservation> reservations,
            final Function<String, ReservationOffer> offers, final Function<String, Accommodation> accommodations,
            final Function<Person.Id, String> names) {
        return build(side, reservations, offers, accommodations, names, true);
    }

    /**
     * The invoice on one side. A stay whose option is missing, or (on PRICE) whose option is unpriced, is
     * counted as {@code unpriced} and otherwise left out, exactly as a missing option always was. On COST an
     * option with no cost is named in {@code missingCost}; {@code strict} then REFUSES the whole invoice
     * ({@code blocked}, nothing else filled), lenient leaves that option out and builds the rest. Cancelled
     * stays are left out entirely.
     *
     * @param offers         lodging option by id string; null for a deleted one.
     * @param accommodations accommodation by id string; null for a deleted one.
     * @param names          person id to display name; a blank answer falls back to the id.
     * @param strict         COST side: refuse the invoice when any active-stay option lacks a cost.
     */
    static RoomInvoice build(final Side side, final List<Reservation> reservations,
            final Function<String, ReservationOffer> offers, final Function<String, Accommodation> accommodations,
            final Function<Person.Id, String> names, final boolean strict) {
        final List<Reservation> active = reservations.stream().filter(Reservation::isActive).toList();
        final Map<String, List<Reservation>> byRoom = active.stream().filter(Reservation::isAssigned)
                .collect(Collectors.groupingBy(Reservation::getRoomId, LinkedHashMap::new, Collectors.toList()));
        final Map<String, OfferTally> tallies = new LinkedHashMap<>();
        final Map<String, String> missing = new LinkedHashMap<>();
        final RoomInvoice invoice = new RoomInvoice();
        for (final Reservation res : active) {
            final ReservationOffer offer = offers.apply(LodgingCommands.idValue(res.getOfferId()));
            if (offer != null && side == Side.COST && offer.getCost() == null) {
                final Accommodation acc = accommodations.apply(accommodationIdOf(res, offer));
                missing.put(offer.getId().getValue(), qualifiedName(offer, acc));
                continue;
            }
            final RateSchedule schedule = (offer == null) ? null : scheduleOf(side, offer);
            if (schedule == null) {
                invoice.setUnpriced(invoice.getUnpriced() + 1);
                continue;
            }
            final Accommodation acc = accommodations.apply(accommodationIdOf(res, offer));
            tallies.computeIfAbsent(offer.getId().getValue(), k -> new OfferTally(side, offer, schedule, acc, names))
                    .add(res, byRoom.getOrDefault(res.getRoomId(), List.of(res)));
        }
        invoice.getMissingCost().addAll(missing.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        if (strict && !missing.isEmpty()) {
            invoice.setBlocked(true);
            return invoice; // Refused whole: an invoice with a hole in it reads as complete to the hotel.
        }
        return invoiceOf(invoice, tallies.values());
    }

    /** The schedule a side reads: the participant price, or what the hotel charges -- never one for the other. */
    static RateSchedule scheduleOf(final Side side, final ReservationOffer offer) {
        return (side == Side.PRICE) ? offer.getPrice() : offer.getCost();
    }

    /** {@code "Double room (Pansion)"}: how the refusal names an option, since two hotels may share a name. */
    static String qualifiedName(final ReservationOffer offer, final Accommodation acc) {
        return trimmed(offer.getName()) + " (" + (acc == null ? UNKNOWN_HOTEL : trimmed(acc.getName())) + ")";
    }

    /** The stay's own hotel, else its option's; the key an operator's filter and a section both use. */
    static String accommodationIdOf(final Reservation res, final ReservationOffer offer) {
        return LodgingCommands.idValue(res.getAccommodationId() != null ? res.getAccommodationId()
                : offer.getAccommodationId());
    }

    /** Offers gather under their hotel; each hotel and the whole invoice total their lines. */
    private static RoomInvoice invoiceOf(final RoomInvoice invoice, final Collection<OfferTally> tallies) {
        final Map<String, InvoiceSection> sections = new LinkedHashMap<>();
        final Set<Person.Id> guests = new LinkedHashSet<>();
        final Set<String> rooms = new LinkedHashSet<>();
        for (final OfferTally tally : tallies) {
            final InvoiceOffer group = tally.toGroup();
            if (group.getLines().isEmpty()) {
                continue; // Zero-night stays only: nobody is charged for a night nobody slept.
            }
            final InvoiceSection section = sections.computeIfAbsent(tally.accKey(), k -> sectionFor(tally.acc));
            section.getOffers().add(group);
            section.getPeople().addAll(tally.people);
            guests.addAll(tally.guests);
            rooms.addAll(tally.rooms.keySet());
        }
        for (final InvoiceSection section : sections.values()) {
            total(section);
            invoice.getSections().add(section);
        }
        // Same arrival: by name, so the order does not depend on which reservation happened to be read first.
        invoice.getSections().sort(Comparator.comparing(RoomInvoiceBuilder::firstNightOf)
                .thenComparing(InvoiceSection::getAccommodation, String.CASE_INSENSITIVE_ORDER));
        invoice.setGuests(guests.size());
        invoice.setRooms(rooms.size());
        invoice.setCents(invoice.getSections().stream().mapToLong(InvoiceSection::getCents).sum());
        invoice.setAmount(MoneyMath.formatCents(invoice.getCents()));
        return invoice;
    }

    private static void total(final InvoiceSection section) {
        section.getOffers().sort(Comparator.comparing(InvoiceOffer::getFirstNight)
                .thenComparing(InvoiceOffer::getName, String.CASE_INSENSITIVE_ORDER));
        section.setCents(section.getOffers().stream().mapToLong(InvoiceOffer::getCents).sum());
        section.setAmount(MoneyMath.formatCents(section.getCents()));
        section.setSeveralOptions(section.getOffers().size() > 1);
        section.getPeople().sort(PERSON_ORDER);
        section.setPeopleCents(section.getPeople().stream().mapToLong(InvoicePersonLine::getCents).sum());
        section.setPeopleAmount(MoneyMath.formatCents(section.getPeopleCents()));
        section.setExtrasCents(section.getOffers().stream().flatMap(group -> group.getLines().stream())
                .filter(InvoiceLine::isExtra).mapToLong(InvoiceLine::getCents).sum());
        section.setExtrasAmount(CostExtras.formatSigned(section.getExtrasCents()));
    }

    private static LocalDate firstNightOf(final InvoiceSection section) {
        return section.getOffers().get(0).getFirstNight();
    }

    private static InvoiceSection sectionFor(final Accommodation acc) {
        final InvoiceSection section = new InvoiceSection();
        section.setAccommodation(acc == null ? UNKNOWN_HOTEL : trimmed(acc.getName()));
        section.setAccommodationId(acc == null ? "" : acc.getId().getValue());
        return section;
    }

    /**
     * Splits one guest's (or room's) nights by what the schedule charges for each, adding one to the quantity
     * of the line for that rate and exactly those nights. Two guests share a line only when they were billed
     * for the same nights at the same rate, which is what makes {@code quantity x nights x rate} the amount.
     */
    private static List<InvoiceLine> countInto(final Map<String, InvoiceLine> lines, final LineKind kind,
            final Set<LocalDate> nights, final Function<LocalDate, Long> rate) {
        final List<InvoiceLine> touched = new ArrayList<>();
        final Map<Long, TreeSet<LocalDate>> byRate = new LinkedHashMap<>();
        for (final LocalDate night : nights) {
            byRate.computeIfAbsent(rate.apply(night), p -> new TreeSet<>()).add(night);
        }
        for (final Map.Entry<Long, TreeSet<LocalDate>> entry : byRate.entrySet()) {
            final TreeSet<LocalDate> run = entry.getValue();
            final String key = String.join("|", kind.name(), entry.getKey().toString(), run.first().toString(),
                    run.last().toString(), String.valueOf(run.size()));
            final InvoiceLine line = lines.computeIfAbsent(key, k -> newLine(kind, entry.getKey(), run));
            line.setQuantity(line.getQuantity() + 1);
            touched.add(line);
        }
        return touched;
    }

    private static InvoiceLine newLine(final LineKind kind, final long rateCents, final TreeSet<LocalDate> nights) {
        final InvoiceLine line = new InvoiceLine();
        line.setKind(kind);
        line.setItem(kind.item());
        line.setRateCents(rateCents);
        line.setRate(MoneyMath.formatCents(rateCents));
        line.setFirstNight(nights.first());
        line.setNights(nights.size());
        line.setDates(stayWindowOf(nights.first(), nights.last().plusDays(1)));
        return line;
    }

    /** {@code "Sep 21 - Sep 26, 2026"}: arrival to departure, the year once unless the stay crosses one. */
    static String stayWindowOf(final LocalDate arrive, final LocalDate depart) {
        return (arrive.getYear() == depart.getYear() ? SHORT_DAY.format(arrive) : DAY.format(arrive))
                + " - " + DAY.format(depart);
    }

    /** What one night costs under the schedule, in the words the option editor uses. */
    static String rateOf(final RateSchedule schedule) {
        final String nightly = schedule.hasOverrides() ? "Varies by night"
                : MoneyMath.formatCents(schedule.getNightlyCents());
        if (!schedule.isPerPerson()) {
            return nightly + " per room per night";
        }
        return nightly + " per person per night" + (schedule.getSingleSupplementCents() > 0
                ? ", single supplement " + MoneyMath.formatCents(schedule.getSingleSupplementCents()) + " per night"
                : "");
    }

    /**
     * A per-person rate as one guest pays it: {@code "$50.00"}, plus {@code " + $10.00 single supplement"} when
     * they were alone, and how many nights that was when it was not all of them.
     */
    static String personRateOf(final RateSchedule schedule, final List<LocalDate> nights,
            final int supplementNights) {
        final Set<Long> rates = nights.stream().map(schedule::nightlyCents).collect(Collectors.toSet());
        final String base = rates.size() == 1 ? MoneyMath.formatCents(rates.iterator().next()) : "Varies by night";
        if (supplementNights <= 0) {
            return base;
        }
        return base + " + " + MoneyMath.formatCents(schedule.getSingleSupplementCents()) + " single supplement"
                + (supplementNights < nights.size() ? " (" + supplementNights
                        + (supplementNights == 1 ? " night)" : " nights)") : "");
    }

    /**
     * A per-room rate as one occupant pays it, which is their SHARE of the room each night (LodgingPricing's
     * even split, remainder cents to the lowest ids): {@code "$60.00 room, alone"}, {@code "$30.00 (share of a
     * $60.00 room)"}, or, when the share changed because someone arrived or left, {@code "Share of a $60.00
     * room, varies by night"}.
     */
    static String roomShareOf(final RateSchedule schedule, final List<LocalDate> nights, final Person.Id person,
            final Map<LocalDate, List<Person.Id>> occupancy) {
        final Set<Long> rates = new LinkedHashSet<>();
        final Set<Long> shares = new LinkedHashSet<>();
        boolean alone = true;
        for (final LocalDate night : nights) {
            final List<Person.Id> present = occupancy.getOrDefault(night, List.of(person));
            final long rate = schedule.nightlyCents(night);
            rates.add(rate);
            shares.add(MoneyMath.splitEvenly(rate, present.size())[Math.max(0, present.indexOf(person))]);
            alone = alone && present.size() == 1;
        }
        final String room = rates.size() == 1 ? "a " + MoneyMath.formatCents(rates.iterator().next()) + " room"
                : "a room priced by night";
        if (alone) {
            return rates.size() == 1 ? MoneyMath.formatCents(rates.iterator().next()) + " room, alone"
                    : "Room alone, priced by night";
        }
        return shares.size() == 1 ? MoneyMath.formatCents(shares.iterator().next()) + " (share of " + room + ")"
                : "Share of " + room + ", varies by night";
    }

    /**
     * {@code "3 Double rooms, 1 Single room"}: the rooms an option's guests sleep in, most-used type first,
     * plus how many reservations still wait for a room. Price never depends on the type, so this is a note
     * under the option rather than a split of its lines.
     */
    static String roomMixOf(final Map<String, Integer> byType, final int unplaced) {
        final List<String> parts = byType.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)))
                .map(RoomInvoiceBuilder::roomMixPart)
                .collect(Collectors.toCollection(ArrayList::new));
        if (unplaced > 0) {
            parts.add(unplaced + (unplaced == 1 ? " reservation" : " reservations") + " not yet placed in a room");
        }
        return String.join(", ", parts);
    }

    private static String roomMixPart(final Map.Entry<String, Integer> entry) {
        return entry.getValue() + (entry.getKey().isEmpty() ? "" : " " + entry.getKey())
                + (entry.getValue() == 1 ? " room" : " rooms");
    }

    private static String trimmed(final String value) {
        return value == null ? "" : value.trim();
    }

    // ================================================================== billed vs cost

    /**
     * The PRICE build and the LENIENT COST build of the same stays, zipped: sections by accommodation id,
     * options by offer id, people by (reservation, person). A row present on one side only shows zero on the
     * other (an unpriced option has no billed row; an option with neither schedule has no row at all). An
     * option whose offer has no cost reads "not set" in its cost column ({@code costAssumed}) at 0; the report
     * counts and names those from the cost build's {@code missingCost}. The option rows carry the extras and
     * the cost concessions in their cost column and the price concessions in their billed column, because each
     * side's group total already includes them; the people rows are net amounts.
     *
     * @param offers lodging option by id string; null for a deleted one (which has no cost to miss).
     */
    static BilledVsCost compare(final RoomInvoice billed, final RoomInvoice cost,
            final Function<String, ReservationOffer> offers) {
        final Map<String, BilledVsCostSection> sections = new LinkedHashMap<>();
        for (final InvoiceSection section : billed.getSections()) {
            fold(sections.computeIfAbsent(section.getAccommodationId(), k -> comparedSection(section)), section,
                    Side.PRICE);
        }
        for (final InvoiceSection section : cost.getSections()) {
            fold(sections.computeIfAbsent(section.getAccommodationId(), k -> comparedSection(section)), section,
                    Side.COST);
        }
        final BilledVsCost report = new BilledVsCost();
        for (final BilledVsCostSection section : sections.values()) {
            for (final BilledVsCostOption option : section.getOptions()) {
                settle(option.getBilledCents(), option.getCostCents(), option::setDifferenceCents, option::setBilled,
                        option::setCost, option::setDifference);
                final ReservationOffer offer = offers.apply(option.getOfferId());
                option.setCostAssumed(offer != null && offer.getCost() == null);
                if (option.isCostAssumed()) {
                    option.setCost(OfferSchedules.COST_NOT_SET);
                }
            }
            section.getPeople().sort(COMPARED_PERSON_ORDER);
            for (final BilledVsCostPerson who : section.getPeople()) {
                settle(who.getBilledCents(), who.getCostCents(), who::setDifferenceCents, who::setBilled,
                        who::setCost, who::setDifference);
            }
            section.setBilledCents(section.getOptions().stream().mapToLong(BilledVsCostOption::getBilledCents).sum());
            section.setCostCents(section.getOptions().stream().mapToLong(BilledVsCostOption::getCostCents).sum());
            settle(section.getBilledCents(), section.getCostCents(), section::setDifferenceCents,
                    section::setBilled, section::setCost, section::setDifference);
            report.getSections().add(section);
        }
        report.setBilledCents(report.getSections().stream().mapToLong(BilledVsCostSection::getBilledCents).sum());
        report.setCostCents(report.getSections().stream().mapToLong(BilledVsCostSection::getCostCents).sum());
        settle(report.getBilledCents(), report.getCostCents(), report::setDifferenceCents, report::setBilled,
                report::setCost, report::setDifference);
        report.setMissingCost(cost.getMissingCost().size());
        report.getMissingCostNames().addAll(cost.getMissingCost());
        return report;
    }

    private static BilledVsCostSection comparedSection(final InvoiceSection section) {
        final BilledVsCostSection compared = new BilledVsCostSection();
        compared.setAccommodation(section.getAccommodation());
        compared.setAccommodationId(section.getAccommodationId());
        return compared;
    }

    /** One side's section folded into the comparison: its option groups and its person rows. */
    private static void fold(final BilledVsCostSection into, final InvoiceSection section, final Side side) {
        for (final InvoiceOffer group : section.getOffers()) {
            final BilledVsCostOption option = optionRow(into, group);
            if (side == Side.PRICE) {
                option.setBilledCents(option.getBilledCents() + group.getCents());
            } else {
                option.setCostCents(option.getCostCents() + group.getCents());
            }
        }
        for (final InvoicePersonLine line : section.getPeople()) {
            final BilledVsCostPerson who = personRow(into, line);
            if (side == Side.PRICE) {
                who.setBilledCents(who.getBilledCents() + line.getCents());
            } else {
                who.setCostCents(who.getCostCents() + line.getCents());
            }
        }
    }

    private static BilledVsCostOption optionRow(final BilledVsCostSection section, final InvoiceOffer group) {
        for (final BilledVsCostOption option : section.getOptions()) {
            if (option.getOfferId().equals(group.getOfferId())) {
                return option;
            }
        }
        final BilledVsCostOption option = new BilledVsCostOption();
        option.setOfferId(group.getOfferId());
        option.setName(group.getName());
        section.getOptions().add(option);
        return option;
    }

    private static BilledVsCostPerson personRow(final BilledVsCostSection section, final InvoicePersonLine line) {
        for (final BilledVsCostPerson who : section.getPeople()) {
            if (who.getReservationId().equals(line.getReservationId())
                    && who.getPersonId().equals(line.getPersonId())) {
                return who;
            }
        }
        final BilledVsCostPerson who = new BilledVsCostPerson();
        who.setReservationId(line.getReservationId());
        who.setPersonId(line.getPersonId());
        who.setName(line.getName());
        who.setOption(line.getOption());
        who.setDates(line.getDates());
        who.setFirstNight(line.getFirstNight());
        who.setNights(line.getNights());
        section.getPeople().add(who);
        return who;
    }

    /** Difference = billed - cost (positive when the participants cover the hotel), and the three strings. */
    private static void settle(final long billed, final long cost, final LongConsumer difference,
            final Consumer<String> billedText, final Consumer<String> costText,
            final Consumer<String> differenceText) {
        difference.accept(billed - cost);
        billedText.accept(MoneyMath.formatCents(billed));
        costText.accept(MoneyMath.formatCents(cost));
        differenceText.accept(CostExtras.formatSigned(billed - cost));
    }

    // ================================================================== the tally

    /**
     * One lodging option while the invoice is gathered: every guest's nights, every alone night that carries
     * the supplement, every room's nights, the room-type mix, the per-person rows, and the counts the cost
     * extras multiply.
     */
    private static final class OfferTally {
        private final Side side;
        private final ReservationOffer offer;
        private final RateSchedule schedule;
        private final Accommodation acc;
        private final Map<Person.Id, TreeSet<LocalDate>> guestNights = new LinkedHashMap<>();
        private final Map<Person.Id, TreeSet<LocalDate>> aloneNights = new LinkedHashMap<>();
        /** Room id, or {@code res:<id>} for a reservation not placed in a room, which is billed as its own. */
        private final Map<String, TreeSet<LocalDate>> rooms = new LinkedHashMap<>();
        private final Map<String, Set<String>> roomsByType = new LinkedHashMap<>();
        private final Set<Person.Id> guests = new LinkedHashSet<>();
        private final Map<String, Set<Person.Id>> guestsByRoom = new LinkedHashMap<>();
        /** One row per occupant per stay, for the hotel's per-person table. */
        private final List<InvoicePersonLine> people = new ArrayList<>();
        /** One line per stay with a concession on this side. */
        private final List<InvoiceLine> concessions = new ArrayList<>();
        private final Function<Person.Id, String> names;
        /** The counts a cost extra multiplies: occupants x nights over the stays, and stays with a night. */
        private int guestNightCount;
        private int stays;

        private OfferTally(final Side side, final ReservationOffer offer, final RateSchedule schedule,
                final Accommodation acc, final Function<Person.Id, String> names) {
            this.side = side;
            this.offer = offer;
            this.schedule = schedule;
            this.acc = acc;
            this.names = names;
        }

        private void add(final Reservation res, final List<Reservation> onRoom) {
            final Room room = (acc == null) ? null : acc.room(res.getRoomId());
            final String roomKey = (room == null) ? "res:" + res.getId().getValue() : room.getId();
            final List<LocalDate> nights = LodgingPricing.nightsOf(res.getStart(), res.getEnd());
            if (nights.isEmpty()) {
                return; // A zero-night stay sleeps nobody anywhere, so it is neither a guest nor a room.
            }
            rooms.computeIfAbsent(roomKey, k -> new TreeSet<>()).addAll(nights);
            guestsByRoom.computeIfAbsent(roomKey, k -> new LinkedHashSet<>()).addAll(res.getOccupants());
            if (room != null) {
                roomsByType.computeIfAbsent(typeName(room.getRoomTypeId()), k -> new LinkedHashSet<>()).add(roomKey);
            }
            guests.addAll(res.getOccupants());
            guestNightCount += res.getOccupants().size() * nights.size();
            stays++;
            // Each occupant's charge IS their line on this side: the same call, against the same room -- the
            // stays under THIS option on it, so a room holding two options (old data) charges each its whole
            // room here exactly as the option table does above, and the two tables agree.
            final List<Reservation> context = res.isAssigned() ? LodgingPricing.underOption(onRoom, res)
                    : List.of(res);
            final Concession concession = (side == Side.PRICE) ? res.getPriceConcession() : res.getCostConcession();
            final List<LodgingPricing.Line> lines = LodgingPricing.lines(side, res, offer, acc, context, null);
            for (final LodgingPricing.Line line : lines) {
                people.add(personLine(line, nights, context, concession));
            }
            if (concession != null) {
                concessions.add(concessionLine(res, concession, nights, lines));
            }
            if (!schedule.isPerPerson()) {
                return;
            }
            // Alone is LodgingPricing's test: the only occupant of the room that night, across every stay on it.
            // The waiver is a PRICE-side concession: the hotel still charges the supplement.
            final Map<LocalDate, List<Person.Id>> occupancy = LodgingPricing.occupancyByNight(context);
            final boolean waived = (side == Side.PRICE) && res.isSupplementWaived();
            final boolean supplemented = schedule.getSingleSupplementCents() > 0 && !waived;
            for (final Person.Id person : res.getOccupants()) {
                guestNights.computeIfAbsent(person, k -> new TreeSet<>()).addAll(nights);
                for (final LocalDate night : nights) {
                    if (supplemented && occupancy.getOrDefault(night, List.of(person)).size() == 1) {
                        aloneNights.computeIfAbsent(person, k -> new TreeSet<>()).add(night);
                    }
                }
            }
        }

        private InvoicePersonLine personLine(final LodgingPricing.Line bill, final List<LocalDate> nights,
                final List<Reservation> context, final Concession concession) {
            final InvoicePersonLine line = new InvoicePersonLine();
            line.setReservationId(bill.reservationId().getValue());
            line.setPersonId(bill.personId().getValue());
            line.setName(nameOf(bill.personId()));
            line.setOption(trimmed(offer.getName()));
            line.setFirstNight(nights.get(0));
            line.setDates(stayWindowOf(nights.get(0), nights.get(nights.size() - 1).plusDays(1)));
            line.setNights(bill.nights());
            final String rate = schedule.isPerPerson() ? personRateOf(schedule, nights, bill.supplementNights())
                    : roomShareOf(schedule, nights, bill.personId(), LodgingPricing.occupancyByNight(context));
            line.setRate(bill.concessionCents() > 0 && concession != null
                    ? rate + " (" + concession.label() + ")" : rate);
            line.setCents(bill.amountCents());
            line.setAmount(MoneyMath.formatCents(bill.amountCents()));
            return line;
        }

        /** The stay's discount as a line of its own: what the per-person rows net out, taken off the group. */
        private InvoiceLine concessionLine(final Reservation res, final Concession concession,
                final List<LocalDate> nights, final List<LodgingPricing.Line> lines) {
            final long forgiven = lines.stream().mapToLong(LodgingPricing.Line::concessionCents).sum();
            final InvoiceLine line = new InvoiceLine();
            line.setKind(LineKind.CONCESSION);
            line.setItem(LineKind.CONCESSION.item());
            line.setFirstNight(nights.get(0));
            line.setDates(stayWindowOf(nights.get(0), nights.get(nights.size() - 1).plusDays(1)));
            line.setQuantity(1);
            line.setUnit("");
            line.setRate("");
            line.setCents(-forgiven);
            line.setAmount(CostExtras.formatSigned(-forgiven));
            final String reason = trimmed(concession.getReason());
            line.setNote(guestNamesOf(res) + ": " + concession.label() + (reason.isEmpty() ? "" : " -- " + reason));
            return line;
        }

        private String guestNamesOf(final Reservation res) {
            return res.sortedOccupants().stream().map(this::nameOf).sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(", "));
        }

        private String nameOf(final Person.Id person) {
            final String name = names.apply(person);
            return name == null || name.isBlank() ? person.getValue() : name;
        }

        private String typeName(final String typeId) {
            final RoomType type = acc.roomType(typeId);
            return type == null ? "" : trimmed(type.getName());
        }

        private String accKey() {
            return acc == null ? "" : acc.getId().getValue();
        }

        private InvoiceOffer toGroup() {
            final Map<String, InvoiceLine> lines = new LinkedHashMap<>();
            // Per-room lines name how many guests slept in THEIR rooms, so remember which rooms landed where.
            final Map<InvoiceLine, Set<Person.Id>> sleptOn = new IdentityHashMap<>();
            if (schedule.isPerPerson()) {
                guestNights.values().forEach(n -> countInto(lines, LineKind.GUESTS, n, schedule::nightlyCents));
                aloneNights.values().forEach(n -> countInto(lines, LineKind.SUPPLEMENT, n,
                        night -> schedule.getSingleSupplementCents()));
            } else {
                for (final Map.Entry<String, TreeSet<LocalDate>> room : rooms.entrySet()) {
                    for (final InvoiceLine line : countInto(lines, LineKind.ROOMS, room.getValue(),
                            schedule::nightlyCents)) {
                        sleptOn.computeIfAbsent(line, k -> new LinkedHashSet<>())
                                .addAll(guestsByRoom.get(room.getKey()));
                    }
                }
            }
            final InvoiceOffer group = new InvoiceOffer();
            group.setOfferId(offer.getId().getValue());
            group.setName(trimmed(offer.getName()));
            group.setPricing(rateOf(schedule));
            group.setRoomMix(roomMixOf(roomsByType.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey, e -> e.getValue().size())), unplaced()));
            for (final InvoiceLine line : lines.values()) {
                finish(line, sleptOn.getOrDefault(line, Set.of()).size());
                group.getLines().add(line);
            }
            if (!rooms.isEmpty() && side == Side.COST) {
                for (final CostExtra extra : offer.getCostExtras()) {
                    group.getLines().add(extraLine(extra));
                }
            }
            group.getLines().addAll(concessions);
            // Extras keep the order they were entered in (the hotel's own bill order); the sort is stable.
            group.getLines().sort(Comparator.comparing(InvoiceLine::getKind)
                    .thenComparing(InvoiceLine::getFirstNight)
                    .thenComparingLong(OfferTally::rateOrderOf));
            group.setFirstNight(group.getLines().isEmpty() ? null : group.getLines().stream()
                    .map(InvoiceLine::getFirstNight).min(Comparator.naturalOrder()).orElseThrow());
            group.setCents(group.getLines().stream().mapToLong(InvoiceLine::getCents).sum());
            group.setAmount(CostExtras.formatSigned(group.getCents()));
            return group;
        }

        /** The rate as a sort key for the arithmetic kinds; extras all read 0 so their entered order holds. */
        private static long rateOrderOf(final InvoiceLine line) {
            return line.getKind() == LineKind.EXTRA ? 0L : line.getRateCents();
        }

        /**
         * One cost extra over the whole option group: the count its basis selects, one "night", the signed unit
         * amount as the rate, so the line's product is its amount like every other line's.
         */
        private InvoiceLine extraLine(final CostExtra extra) {
            final int roomNights = rooms.values().stream().mapToInt(Set::size).sum();
            final int quantity = CostExtras.quantity(extra, guestNightCount, roomNights, stays);
            final LocalDate first = rooms.values().stream().map(TreeSet::first).min(Comparator.naturalOrder())
                    .orElseThrow();
            final LocalDate last = rooms.values().stream().map(TreeSet::last).max(Comparator.naturalOrder())
                    .orElseThrow();
            final InvoiceLine line = new InvoiceLine();
            line.setKind(LineKind.EXTRA);
            line.setItem(trimmed(extra.getLabel()).isEmpty() ? LineKind.EXTRA.item() : trimmed(extra.getLabel()));
            line.setFirstNight(first);
            line.setDates(stayWindowOf(first, last.plusDays(1)));
            line.setQuantity(quantity);
            line.setUnit(extra.unit(quantity));
            line.setNights(1);
            line.setRateCents(extra.getAmountCents());
            line.setRate(CostExtras.formatSigned(extra.getAmountCents()));
            finish(line, 0);
            return line;
        }

        private int unplaced() {
            return (int) rooms.keySet().stream().filter(key -> key.startsWith("res:")).count();
        }

        /** The amount IS the arithmetic; a per-room line also says how many guests slept in those rooms. */
        private static void finish(final InvoiceLine line, final int slept) {
            if (line.getKind() != LineKind.EXTRA) {
                line.setUnit(line.getKind().unit(line.getQuantity()));
            }
            line.setCents(line.getQuantity() * line.getNights() * line.getRateCents());
            line.setAmount(CostExtras.formatSigned(line.getCents()));
            if (line.getKind() == LineKind.ROOMS) {
                line.setNote(slept + (slept == 1 ? " guest" : " guests"));
            }
        }
    }
}
