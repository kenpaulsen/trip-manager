package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;

/**
 * Checks an import (docs/migrations/conference-import.md) two ways, and says PASS or FAIL per check:
 * <ul>
 *   <li>{@link #checkPlan} before any write: nothing an old row holds is lost on the way into the plan (every
 *       field of every person, answer, and ledger row is either carried or deliberately transformed).</li>
 *   <li>{@link #checkTarget} after the apply: the target holds exactly the planned rows, AND the totals recomputed
 *       from the EXPORT itself (not from the plan) match it: registrations per trip and status, check-ins, and the
 *       ledger per trip and per person. A planner mistake cannot hide by agreeing with itself.</li>
 * </ul>
 */
public class ConferenceImportVerifier {

    /** Answer keys the planner moves or folds on purpose, so their absence is not a loss. */
    private static final Set<String> MOVED_ANSWERS = Set.of(ConferenceImportPlanner.ADMISSION_KEY,
            ConferenceImportPlanner.CHECKED_IN_KEY);

    private final ObjectMapper mapper;
    private final ImportMapping mapping;
    private final List<String> lines = new ArrayList<>();
    private int failures;

    public ConferenceImportVerifier(final ObjectMapper mapper, final ImportMapping mapping) {
        this.mapper = mapper;
        this.mapping = mapping;
    }

    /** The report so far, one line per check. */
    public List<String> lines() {
        return List.copyOf(lines);
    }

    /** How many checks failed. */
    public int failures() {
        return failures;
    }

    // ------------------------------------------------------------------ before the apply

    /** Nothing is lost between the export and the plan. */
    public void checkPlan(final LegacyExport export, final ImportPlan plan) {
        int peopleLost = 0;
        for (final ObjectNode old : export.people()) {
            final ImportPlan.PersonStep step = plan.stepFor(ConferenceImportPlanner.text(old, "id"));
            if (step != null && step.action() == ImportPlan.Action.CREATE) {
                peopleLost += lostFields(old, mapper.valueToTree(step.person()),
                        Set.of("email", "managedUsers", "familyId", "orgIds"), "person " + step.oldId());
            }
        }
        check(peopleLost == 0, "every created person carries every old field (" + peopleLost + " lost)");
        int answersLost = 0;
        final Map<String, Registration> planned = new LinkedHashMap<>();
        plan.registrations().forEach(reg -> planned.put(reg.getTripId() + "|" + reg.getUserId().getValue(), reg));
        for (final ObjectNode old : export.registrations()) {
            final Person.Id user = plan.idMap().get(ConferenceImportPlanner.text(old, "userId"));
            final Registration reg = user == null ? null
                    : planned.get(ConferenceImportPlanner.text(old, "tripId") + "|" + user.getValue());
            if (reg != null && reg.getStatus().getDescription().equals(ConferenceImportPlanner.text(old, "status"))) {
                answersLost += lostAnswers(old, reg);   // a superseded row (one person now) is in the notes
            }
        }
        check(answersLost == 0, "every registration answer is carried or moved on purpose (" + answersLost
                + " lost)");
        int txLost = 0;
        for (final ImportPlan.TxStep step : plan.transactions()) {
            final ObjectNode old = export.transactions().stream()
                    .filter(row -> sameLedgerRow(plan, step, row))
                    .findFirst().orElse(null);
            txLost += old == null ? 1 : lostFields(old, mapper.valueToTree(step.tx()), Set.of("userId", "note"),
                    "transaction " + step.tx().getTxId());
        }
        check(txLost == 0, "every ledger row keeps every old field (" + txLost + " lost)");
        check(plan.blockers().isEmpty(), "no decisions pending (" + plan.blockers().size() + " blockers)");
    }

    /** The export row a planned ledger row came from: the same txId AND person (txIds repeat across people). */
    private static boolean sameLedgerRow(final ImportPlan plan, final ImportPlan.TxStep step, final ObjectNode row) {
        return step.tx().getTxId().equals(ConferenceImportPlanner.text(row, "txId"))
                && step.tx().getUserId().equals(plan.idMap().get(ConferenceImportPlanner.text(row, "userId")));
    }

    /** Old fields missing or changed in the planned row; each one is reported. */
    private int lostFields(final ObjectNode old, final JsonNode planned, final Set<String> ignored,
            final String what) {
        int lost = 0;
        final java.util.Iterator<Map.Entry<String, JsonNode>> fields = old.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            if (ignored.contains(field.getKey()) || ConferenceImportPlanner.isBlank(field.getValue())) {
                continue;
            }
            if (!sameValue(field.getValue(), planned.get(field.getKey()))) {
                lines.add("    LOST " + what + "." + field.getKey() + ": " + field.getValue() + " -> "
                        + planned.get(field.getKey()));
                lost++;
            }
        }
        return lost;
    }

    private int lostAnswers(final ObjectNode old, final Registration reg) {
        int lost = 0;
        final java.util.Iterator<Map.Entry<String, JsonNode>> answers = old.path("options").fields();
        while (answers.hasNext()) {
            final Map.Entry<String, JsonNode> answer = answers.next();
            final String key = answer.getKey();
            final boolean moved = MOVED_ANSWERS.contains(key) || key.matches("opt\\d+")
                    && reg.getOptions().containsKey(key.substring(3));
            if (!moved && !answer.getValue().asText().equals(reg.getOptions().get(key))) {
                lines.add("    LOST registration " + reg.getTripId() + "/" + reg.getUserId().getValue() + " answer "
                        + key);
                lost++;
            }
        }
        return lost;
    }

    /** Equal as a value: numbers by amount, dates by instant to the second, text exactly. */
    private static boolean sameValue(final JsonNode old, final JsonNode now) {
        if (now == null || now.isNull()) {
            return false;
        }
        if (old.isNumber() || now.isNumber()) {
            return new BigDecimal(old.asText()).compareTo(new BigDecimal(now.asText())) == 0;
        }
        if (old.isTextual() && now.isTextual() && ConferenceImportPlanner.timestamp(old.asText()) != null) {
            return Objects.equals(ConferenceImportPlanner.timestamp(old.asText()),
                    ConferenceImportPlanner.timestamp(now.asText()));
        }
        if (old.isContainerNode()) {
            return ConferenceImportPlanner.isBlank(old) && ConferenceImportPlanner.isBlank(now) || old.equals(now)
                    || containsAll(old, now);
        }
        return old.asText().trim().equals(now.asText().trim());   // the model trims text on purpose
    }

    /** Every non-blank old field of an object is in the new one (the new one may carry more defaults). */
    private static boolean containsAll(final JsonNode old, final JsonNode now) {
        if (!old.isObject() || !now.isObject()) {
            return false;
        }
        final java.util.Iterator<Map.Entry<String, JsonNode>> fields = old.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            if (!ConferenceImportPlanner.isBlank(field.getValue()) && !sameValue(field.getValue(),
                    now.get(field.getKey()))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ after the apply

    /** The target holds the plan, and the export's own totals. */
    public void checkTarget(final LegacyExport export, final ImportPlan plan) {
        checkPeople(plan);
        checkFamilies(plan);
        checkTrips(plan);
        checkRegistrations(export, plan);
        checkLedger(export, plan);
        checkRoles(plan);
    }

    private void checkPeople(final ImportPlan plan) {
        final Organization.Id org = Organization.Id.from(mapping.targetOrgId());
        int missing = 0;
        int wrong = 0;
        int notMembers = 0;
        for (final ImportPlan.PersonStep step : plan.people()) {
            if (step.targetId() == null || step.action() == ImportPlan.Action.SAME_AS) {
                continue;
            }
            final Optional<Person> live = DAO.getInstance().getPerson(step.targetId(), Cached.NO);
            if (live.isEmpty()) {
                missing++;
                lines.add("    MISSING person " + step.targetId().getValue() + " (" + step.oldId() + ")");
                continue;
            }
            wrong += samePerson(step, live.get()) ? 0 : 1;
            if (DAO.getInstance().getOrgMember(org, step.targetId(), Cached.NO).isEmpty()) {
                notMembers++;
            }
        }
        check(missing == 0, "every imported person exists (" + missing + " missing)");
        check(wrong == 0, "every person holds the planned values (" + wrong + " differ)");
        check(notMembers == 0, "every imported person is a member of the organization (" + notMembers + " not)");
    }

    /** The planned fields hold their planned values; a merge's existing values are untouched. */
    private boolean samePerson(final ImportPlan.PersonStep step, final Person live) {
        final JsonNode want = mapper.valueToTree(step.person());
        final JsonNode have = mapper.valueToTree(live);
        for (final String field : List.of("first", "last", "middle", "nickname", "email", "birthdate", "cell",
                "sex", "notes", "address", "passport")) {
            if (!Objects.equals(want.get(field), have.get(field))) {
                lines.add("    DIFF person " + step.targetId().getValue() + "." + field + ": planned "
                        + want.get(field) + ", found " + have.get(field));
                return false;
            }
        }
        return true;
    }

    private void checkFamilies(final ImportPlan plan) {
        int wrong = 0;
        for (final ImportPlan.FamilyLink link : plan.families()) {
            final Person anchor = DAO.getInstance().getPerson(link.anchor(), Cached.NO).orElse(null);
            final Person member = DAO.getInstance().getPerson(link.member(), Cached.NO).orElse(null);
            final Family family = anchor == null || anchor.getFamilyId() == null ? null
                    : DAO.getInstance().getFamily(anchor.getFamilyId(), Cached.NO).orElse(null);
            final boolean ok = family != null && member != null && family.getId().equals(member.getFamilyId())
                    && family.getMemberIds().contains(link.member()) && !family.getManagerIds().contains(link.member());
            if (!ok) {
                wrong++;
                lines.add("    FAMILY not linked: " + link.member().getValue() + " under " + link.anchor().getValue());
            }
        }
        check(wrong == 0, "every planned family link exists, as a member (" + wrong + " of " + plan.families().size()
                + " wrong)");
    }

    private void checkTrips(final ImportPlan plan) {
        for (final Trip want : plan.trips()) {
            final Trip have = DAO.getInstance().getTrip(want.getId(), Cached.NO).orElse(null);
            final boolean ok = have != null && have.getKind() == TripKind.CONFERENCE
                    && mapping.targetOrgId().equals(have.getOrgId())
                    && Set.copyOf(have.getPeople()).equals(Set.copyOf(want.getPeople()))
                    && have.getRegOptions().size() == want.getRegOptions().size();
            check(ok, "trip '" + want.getTitle() + "': conference of the organization, roster of "
                    + want.getPeople().size() + ", " + want.getRegOptions().size() + " questions");
        }
    }

    private void checkRegistrations(final LegacyExport export, final ImportPlan plan) {
        int differ = 0;
        for (final Registration want : plan.registrations()) {
            final Registration have = DAO.getInstance()
                    .getRegistration(want.getTripId(), want.getUserId(), Cached.NO).orElse(null);
            final boolean ok = have != null && have.getStatus() == want.getStatus()
                    && have.getOptions().equals(want.getOptions())
                    && Objects.equals(have.getCheckedInAt(), want.getCheckedInAt())
                    && Objects.equals(have.getRegistrationType(), want.getRegistrationType())
                    && (have.getStatus() != Registration.Status.CONFIRMED || have.getTicketCode() != null);
            if (!ok) {
                differ++;
                lines.add("    DIFF registration " + want.getTripId() + "/" + want.getUserId().getValue());
            }
        }
        check(differ == 0, "every registration is stored as planned, CONFIRMED with a ticket (" + differ
                + " differ)");
        for (final ObjectNode trip : export.trips()) {
            final String tripId = ConferenceImportPlanner.text(trip, "id");
            final Map<String, Integer> expected = new TreeMap<>();
            int checkIns = 0;
            for (final ObjectNode old : mostAdvanced(export, plan, tripId).values()) {
                expected.merge(ConferenceImportPlanner.text(old, "status"), 1, Integer::sum);
                checkIns += old.path("options").has(ConferenceImportPlanner.CHECKED_IN_KEY) ? 1 : 0;
            }
            final List<Registration> live = DAO.getInstance().getRegistrations(tripId, Cached.NO);
            final Map<String, Integer> found = new TreeMap<>();
            live.forEach(reg -> found.merge(reg.getStatus().getDescription(), 1, Integer::sum));
            final long liveCheckIns = live.stream().filter(reg -> reg.getCheckedInAt() != null).count();
            check(expected.equals(found), "'" + ConferenceImportPlanner.text(trip, "title") + "' registrations by "
                    + "status: export " + expected + ", target " + found);
            check(checkIns == liveCheckIns, "'" + ConferenceImportPlanner.text(trip, "title") + "' check-ins: export "
                    + checkIns + ", target " + liveCheckIns);
        }
    }

    /**
     * Each imported person's most advanced old registration on a trip (two old accounts that are one person now
     * keep one registration): computed from the export, independently of the planner.
     */
    private static Map<String, ObjectNode> mostAdvanced(final LegacyExport export, final ImportPlan plan,
            final String tripId) {
        final Map<String, ObjectNode> best = new LinkedHashMap<>();
        for (final ObjectNode old : export.registrations()) {
            if (!tripId.equals(ConferenceImportPlanner.text(old, "tripId")) || !imported(plan, old)) {
                continue;
            }
            final String person = plan.idMap().get(ConferenceImportPlanner.text(old, "userId")).getValue();
            best.merge(person, old, ConferenceImportVerifier::moreAdvanced);
        }
        return best;
    }

    private static ObjectNode moreAdvanced(final ObjectNode one, final ObjectNode two) {
        return rank(two) > rank(one) ? two : one;
    }

    private static int rank(final ObjectNode reg) {
        return switch (String.valueOf(ConferenceImportPlanner.text(reg, "status"))) {
            case "Confirmed" -> 2;
            case "Pending" -> 1;
            default -> 0;
        };
    }

    /**
     * The ledger, from the export's own rows and bindings: per trip, the count and the sum of the rows bound to it,
     * and per person the sum of their rows; then the same from the target's bindings and rows.
     */
    private void checkLedger(final LegacyExport export, final ImportPlan plan) {
        final Map<String, BigDecimal> expectedByTrip = new TreeMap<>();
        final Map<String, Integer> expectedCount = new TreeMap<>();
        final Map<String, BigDecimal> expectedByPerson = new TreeMap<>();
        final Map<String, String> tripOf = new LinkedHashMap<>();
        for (final LegacyExport.Binding binding : export.bindings()) {
            if ("TRANSACTION".equals(binding.id1Type()) && "TRIP".equals(binding.id2Type())) {
                tripOf.put(binding.id1().substring(2), binding.id2().substring(2));
            }
        }
        mapping.transactionTrips().forEach(tripOf::putIfAbsent);
        for (final ObjectNode old : export.transactions()) {
            if (!imported(plan, old)) {
                continue;
            }
            final String trip = tripOf.getOrDefault(ConferenceImportPlanner.text(old, "userId") + ","
                    + ConferenceImportPlanner.text(old, "txId"), "(unbound)");
            final BigDecimal amount = live(old) ? money(old.path("amount").asText("0")) : BigDecimal.ZERO;
            expectedByTrip.merge(trip, amount, BigDecimal::add);
            expectedCount.merge(trip, 1, Integer::sum);
            expectedByPerson.merge(plan.idMap().get(ConferenceImportPlanner.text(old, "userId")).getValue() + "@"
                    + trip, amount, BigDecimal::add);
        }
        final Map<String, BigDecimal> foundByTrip = new TreeMap<>();
        final Map<String, Integer> foundCount = new TreeMap<>();
        final Map<String, BigDecimal> foundByPerson = new TreeMap<>();
        for (final Trip trip : plan.trips()) {
            for (final String key : DAO.getInstance().getBindings(trip.getId(), BindingType.TRIP,
                    BindingType.TRANSACTION, Cached.NO)) {
                final String[] parts = key.split(",", 2);
                final Transaction tx = DAO.getInstance().getTransaction(Person.Id.from(parts[0]), parts[1], Cached.NO)
                        .orElse(null);
                final BigDecimal amount = tx == null || tx.getDeleted() != null || tx.getAmount() == null
                        ? BigDecimal.ZERO : money(tx.getAmount().toString());
                foundByTrip.merge(trip.getId(), amount, BigDecimal::add);
                foundCount.merge(trip.getId(), 1, Integer::sum);
                foundByPerson.merge(parts[0] + "@" + trip.getId(), amount, BigDecimal::add);
            }
        }
        expectedByTrip.remove("(unbound)");
        expectedCount.remove("(unbound)");
        expectedByPerson.keySet().removeIf(key -> key.endsWith("(unbound)"));
        check(expectedCount.equals(foundCount), "ledger rows per trip: export " + expectedCount + ", target "
                + foundCount);
        check(expectedByTrip.equals(foundByTrip), "ledger totals per trip (live rows): export " + expectedByTrip
                + ", target " + foundByTrip);
        final Set<String> people = new TreeSet<>(expectedByPerson.keySet());
        people.addAll(foundByPerson.keySet());
        int differ = 0;
        for (final String person : people) {
            if (expectedByPerson.getOrDefault(person, BigDecimal.ZERO)
                    .compareTo(foundByPerson.getOrDefault(person, BigDecimal.ZERO)) != 0) {
                differ++;
                lines.add("    DIFF ledger " + person + ": export " + expectedByPerson.get(person) + ", target "
                        + foundByPerson.get(person));
            }
        }
        check(differ == 0, "every person's trip ledger total matches the export (" + people.size() + " checked, "
                + differ + " differ)");
    }

    private void checkRoles(final ImportPlan plan) {
        int missing = 0;
        for (final ImportPlan.RoleStep role : plan.roles()) {
            final Privilege live = DAO.getInstance().getPrivilege(Privilege.idFor(role.base(), role.tripId()),
                    Cached.NO).orElse(null);
            for (final Person.Id person : role.people()) {
                if (live == null || !live.getPeople().contains(person)) {
                    missing++;
                }
            }
        }
        check(missing == 0, "every migrated trip role is held (" + missing + " missing)");
    }

    // ------------------------------------------------------------------ helpers

    /** Whether the row's person is imported (the plan decides who is; the totals still come from the export). */
    private static boolean imported(final ImportPlan plan, final ObjectNode row) {
        return plan.idMap().containsKey(ConferenceImportPlanner.text(row, "userId"));
    }

    private static boolean live(final ObjectNode tx) {
        return ConferenceImportPlanner.isBlank(tx.get("deleted"));
    }

    /** Dollars to the cent: the export holds doubles, the target floats. */
    private static BigDecimal money(final String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private void check(final boolean ok, final String what) {
        lines.add((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
