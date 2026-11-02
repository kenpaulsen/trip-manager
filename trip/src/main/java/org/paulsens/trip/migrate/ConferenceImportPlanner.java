package org.paulsens.trip.migrate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;

/**
 * Decides an import (docs/migrations/conference-import.md) without writing anything: who each old person is in the
 * target, which families the address-less ones join, and the exact trips, registrations, ledger rows and roles to
 * write. It READS the target only through {@link Target}, so a test runs it against the in-memory store and the
 * dry run against production.
 *
 * <p>The owner's rules (2026-11-01), in the order they apply to a person:
 * <ol>
 *   <li>Skipped people (mapping) are left out with everything that references them.</li>
 *   <li>An address an existing person holds is the SAME person when the first and last names match (case and
 *       spacing aside), or the mapping says so: blanks are filled from the old row, an existing value always wins.
 *       A name that does not match is a blocker: the owner decides.</li>
 *   <li>Everyone else with an address is created with their old id (no id is shared with the target).</li>
 *   <li>A person whose "email" is a sign-in name, not an address, joins the family of the one person the evidence
 *       points to (same phone, same home address, or the same surname registering within a few hours on the same
 *       trip), as a member and without an email. With no candidate they keep the sign-in name as their email;
 *       with several the owner decides.</li>
 * </ol>
 */
public class ConferenceImportPlanner {

    /** What the planner reads from the target deployment. */
    public interface Target {
        /** The person holding this address (case-insensitive), if any. */
        Optional<Person> personByEmail(String email);

        /** The person with this id, if any. */
        Optional<Person> personById(Person.Id id);

        /** Whether a trip with this id exists already (an earlier run). */
        boolean tripExists(String tripId);
    }

    /** Fields a merge never fills: identity, address, and what the families and orgs writers own. */
    static final Set<String> NEVER_FILLED = Set.of("id", "email", "first", "last", "managedUsers", "familyId",
            "orgIds", "deleted", "privacy", "profilePhotoSlot", "defaultOrgId");

    /** Fields never carried from the export: a person's family and orgs are written by their own writers. */
    static final Set<String> NOT_CARRIED = Set.of("managedUsers", "familyId", "orgIds");

    /** The legacy trip fields today's model has no place for; their counts go in the report. */
    static final List<String> LEGACY_TRIP_FIELDS = List.of("admissionOptions", "discountCodes", "childPriceCap",
            "tripEvents");

    static final String ADMISSION_KEY = "_admission";
    static final String CHECKED_IN_KEY = "_checkedIn";
    static final String REG_TYPE_KEY = "_regType";
    private static final String TRANSACTION_PREFIX = "4_";
    private static final String TRIP_PREFIX = "5_";

    private final ObjectMapper mapper;
    private final ImportMapping mapping;
    private final Target target;

    public ConferenceImportPlanner(final ObjectMapper mapper, final ImportMapping mapping, final Target target) {
        this.mapper = mapper;
        this.mapping = mapping;
        this.target = target;
    }

    /** The whole import, decided. */
    public ImportPlan plan(final LegacyExport export) {
        final Work work = new Work(export);
        resolvePeople(work);
        resolveDuplicates(work);
        resolveFamilies(work);
        checkUniqueEmails(work);
        planTrips(work);
        planRegistrations(work);
        planTransactions(work);
        planRoles(work);
        return work.toPlan();
    }

    // ------------------------------------------------------------------ people

    private void resolvePeople(final Work work) {
        for (final ObjectNode old : work.export.people()) {
            final String id = text(old, "id");
            final String email = trimmed(text(old, "email"));
            final String forced = mapping.mergeIntoEmail().get(id);
            if (mapping.skipPeople().contains(id)) {
                work.step(new ImportPlan.PersonStep(id, null, ImportPlan.Action.SKIP, null, null, List.of(),
                        List.of(), "skipped by the mapping"));
            } else if (!isBlank(old.get("deleted"))) {
                skipDeleted(work, old);
            } else if (mapping.sameAs().containsKey(id)) {
                continue;   // resolved once the person it duplicates is
            } else if (forced != null) {
                mergeInto(work, old, forced, "mapping mergeIntoEmail: " + forced, true);
            } else if (isAddress(email)) {
                resolveAddressed(work, old, email);
            } else {
                work.addressless.add(id);
            }
        }
    }

    /**
     * The owner's rule (2026-11-01): an account the old site had soft-deleted is not imported. One that a live
     * registration or a payment still references is a blocker instead: something the old admins deleted is in use.
     */
    private static void skipDeleted(final Work work, final ObjectNode old) {
        final String id = text(old, "id");
        final boolean registered = work.export.registrations().stream()
                .anyMatch(reg -> id.equals(text(reg, "userId")) && !"Not Registered".equals(text(reg, "status")));
        final boolean paid = work.export.transactions().stream()
                .anyMatch(tx -> id.equals(text(tx, "userId")) && isBlank(tx.get("deleted")));
        if (registered || paid) {
            work.blockers.add("Person " + describe(old) + " was deleted on the old site but still has "
                    + (registered ? "a registration" : "a payment") + ": decide (mapping skipPeople or sameAs).");
            return;
        }
        work.step(new ImportPlan.PersonStep(id, null, ImportPlan.Action.SKIP, null, null, List.of(), List.of(),
                "deleted on the old site " + text(old, "deleted").substring(0, 10)));
    }

    private void resolveAddressed(final Work work, final ObjectNode old, final String email) {
        final String id = text(old, "id");
        final Optional<Person> existing = target.personByEmail(email);
        if (existing.isPresent()) {
            mergeInto(work, old, email, "same address as an existing person",
                    mapping.nameMatchOverrides().contains(id));
        } else if (target.personById(Person.Id.from(id)).isPresent()) {
            work.blockers.add("Person " + describe(old) + ": their old id is already used by a different person in "
                    + "the target. Decide who they are.");
        } else {
            work.step(created(old, email, "new person (address not in the target)"));
        }
    }

    /** The old row merged into the existing person holding {@code email}, or a blocker. */
    private void mergeInto(final Work work, final ObjectNode old, final String email, final String why,
            final boolean nameWaived) {
        final Person existing = target.personByEmail(email).orElse(null);
        if (existing == null) {
            work.blockers.add("Person " + describe(old) + ": the mapping merges them into " + email
                    + ", which no person in the target holds.");
            return;
        }
        if (!nameWaived && !sameName(old, existing)) {
            work.blockers.add("Person " + describe(old) + " has the address of the existing " + existing.getFirst()
                    + " " + existing.getLast() + " (" + existing.getId().getValue() + ") but a different name. "
                    + "Decide whether they are the same person (mapping nameMatchOverrides or mergeIntoEmail).");
            return;
        }
        final ObjectNode merged = mapper.valueToTree(existing);
        final ObjectNode incoming = normalized(old);
        final List<String> filled = new ArrayList<>();
        final List<String> kept = new ArrayList<>();
        final Iterator<Map.Entry<String, JsonNode>> fields = incoming.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            fillOne(merged, field.getKey(), field.getValue(), filled, kept);
        }
        for (final String name : List.of("first", "last")) {
            if (!sameText(merged.get(name), incoming.get(name))) {
                kept.add(name);
            }
        }
        work.step(new ImportPlan.PersonStep(text(old, "id"), existing.getId(), ImportPlan.Action.MERGE,
                toPerson(merged), existing, filled, kept, why));
    }

    private static void fillOne(final ObjectNode merged, final String name, final JsonNode value,
            final List<String> filled, final List<String> kept) {
        if (NEVER_FILLED.contains(name) || isBlank(value)) {
            return;
        }
        final JsonNode current = merged.get(name);
        if (isBlank(current)) {
            merged.set(name, value);
            filled.add(name);
        } else if (!sameText(current, value)) {
            kept.add(name);
        }
    }

    private ImportPlan.PersonStep created(final ObjectNode old, final String email, final String why) {
        final ObjectNode tree = normalized(old);
        tree.put("email", email);
        return new ImportPlan.PersonStep(text(old, "id"), Person.Id.from(text(old, "id")), ImportPlan.Action.CREATE,
                toPerson(tree), null, List.of(), List.of(), why);
    }

    /**
     * The mapping's duplicates: each becomes the person it duplicates, whose blanks are filled from it (a
     * duplicate created later sometimes holds the newer phone number).
     */
    private void resolveDuplicates(final Work work) {
        for (final Map.Entry<String, String> dup : mapping.sameAs().entrySet()) {
            final ImportPlan.PersonStep main = work.steps.get(dup.getValue());
            final ObjectNode old = work.oldPeople.get(dup.getKey());
            final ImportPlan.PersonStep own = work.steps.get(dup.getKey());
            if (own != null && own.action() == ImportPlan.Action.SKIP) {
                work.notes.add("mapping sameAs " + dup.getKey() + ": not imported (" + own.note() + ").");
                continue;
            }
            if (old == null) {
                work.notes.add("mapping sameAs names " + dup.getKey() + ", which is not in the export.");
                continue;
            }
            if (main == null || main.targetId() == null) {
                work.blockers.add("Person " + describe(old) + " duplicates " + dup.getValue()
                        + ", which is not imported: fix the mapping's sameAs.");
                continue;
            }
            work.steps.put(main.oldId(), withBlanksFrom(main, old));
            work.step(new ImportPlan.PersonStep(dup.getKey(), main.targetId(), ImportPlan.Action.SAME_AS, null, null,
                    List.of(), List.of(), "duplicate of " + dup.getValue()));
        }
    }

    private ImportPlan.PersonStep withBlanksFrom(final ImportPlan.PersonStep main, final ObjectNode duplicate) {
        final ObjectNode merged = mapper.valueToTree(main.person());
        final List<String> filled = new ArrayList<>(main.filled());
        final List<String> kept = new ArrayList<>(main.kept());
        final Iterator<Map.Entry<String, JsonNode>> fields = normalized(duplicate).fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            fillOne(merged, field.getKey(), field.getValue(), filled, kept);
        }
        return new ImportPlan.PersonStep(main.oldId(), main.targetId(), main.action(), toPerson(merged),
                main.before(), filled, kept, main.note());
    }

    // ------------------------------------------------------------------ families

    private void resolveFamilies(final Work work) {
        final FamilyEvidence evidence = new FamilyEvidence(work, mapping.familyWindowHours());
        final Set<String> linked = new LinkedHashSet<>();
        for (final ObjectNode manager : work.export.people()) {
            for (final JsonNode managed : manager.path("managedUsers")) {
                linkExplicit(work, text(manager, "id"), managed.asText(), linked);
            }
        }
        for (final String id : work.addressless) {
            if (linked.contains(id)) {
                work.step(created(work.oldPeople.get(id), null, "family member (the old site's managed account)"));
            } else {
                resolveAddressless(work, evidence, id);
            }
        }
    }

    /**
     * The old site's own "managed" link: the managed person joins the manager's family. A member who already EXISTS
     * in the target keeps the family the target gives them (the existing data wins), so that link is only reported.
     */
    private void linkExplicit(final Work work, final String managerId, final String memberId,
            final Set<String> linked) {
        final Person.Id anchor = work.idMap.get(managerId);
        final ImportPlan.PersonStep member = work.steps.get(memberId);
        final boolean memberResolved = work.idMap.containsKey(memberId) || work.addressless.contains(memberId);
        if (anchor == null || !memberResolved || mapping.skipPeople().contains(memberId)) {
            work.notes.add("Old managed link " + managerId + " -> " + memberId + " not migrated: one side is not "
                    + "imported.");
            return;
        }
        if (member != null && member.action() == ImportPlan.Action.MERGE) {
            work.notes.add("Old managed link " + work.describeOld(managerId) + " -> " + work.describeOld(memberId)
                    + " not migrated: the member already exists in the target, whose family is kept.");
            return;
        }
        linked.add(memberId);
        final Person.Id target = work.idMap.getOrDefault(memberId, Person.Id.from(memberId));
        work.families.add(new ImportPlan.FamilyLink(anchor, target, "the old site's managed link"));
    }

    private void resolveAddressless(final Work work, final FamilyEvidence evidence, final String id) {
        final ObjectNode old = work.oldPeople.get(id);
        final String decision = mapping.familyDecisions().get(id);
        if (ImportMapping.NO_FAMILY.equals(decision)) {
            work.step(created(old, trimmed(text(old, "email")), "no family (mapping familyDecisions)"));
            return;
        }
        if (decision != null) {
            final Person.Id anchor = work.idMap.get(decision);
            if (anchor == null) {
                work.blockers.add("Person " + describe(old) + ": the mapping links them under " + decision
                        + ", who is not imported with an address.");
                return;
            }
            work.step(created(old, null, "family member (mapping familyDecisions)"));
            work.families.add(new ImportPlan.FamilyLink(anchor, Person.Id.from(id), "the owner's decision"));
            return;
        }
        final Map<Person.Id, Set<String>> candidates = evidence.candidatesFor(id);
        final List<Person.Id> sameName = candidates.keySet().stream()
                .filter(anchor -> samePerson(old, work.personOf(anchor)))
                .toList();
        if (sameName.size() == 1) {
            sameAsAccount(work, old, sameName.get(0), candidates.get(sameName.get(0)));
        } else if (!sameName.isEmpty()) {
            work.step(created(old, trimmed(text(old, "email")), sameName.size() + " accounts of the same name: "
                    + "kept separate with the old sign-in name"));
        } else {
            final Person.Id anchor = strongest(candidates);
            if (anchor == null) {
                work.step(created(old, trimmed(text(old, "email")), candidates.isEmpty()
                        ? "no family found: keeps the old sign-in name"
                        : "candidate families tie: no family, keeps the old sign-in name"));
                if (!candidates.isEmpty()) {
                    work.review.add(reviewLine(work, old, candidates));
                }
            } else {
                work.step(created(old, null, "family member (" + String.join(", ", candidates.get(anchor)) + ")"));
                work.families.add(new ImportPlan.FamilyLink(anchor, Person.Id.from(id),
                        String.join(", ", candidates.get(anchor))));
            }
        }
    }

    /**
     * The owner's rule for a sign-in-name record with ONE candidate of the very same name: it is that person
     * registering again, not a relative. It becomes that account (its blanks filled from this row), and its
     * registrations and payments move there.
     */
    private void sameAsAccount(final Work work, final ObjectNode old, final Person.Id account,
            final Set<String> why) {
        final ImportPlan.PersonStep main = work.stepOfTarget(account);
        work.steps.put(main.oldId(), withBlanksFrom(main, old));
        work.step(new ImportPlan.PersonStep(text(old, "id"), account, ImportPlan.Action.SAME_AS, null, null,
                List.of(), List.of(), "same name as " + account.getValue() + " (" + String.join(", ", why)
                + "): the same person"));
    }

    /** The candidate with the most kinds of evidence, or null when none or several tie for the most. */
    static Person.Id strongest(final Map<Person.Id, Set<String>> candidates) {
        Person.Id best = null;
        int most = 0;
        boolean tie = false;
        for (final Map.Entry<Person.Id, Set<String>> candidate : candidates.entrySet()) {
            final int kinds = candidate.getValue().size();
            if (kinds > most) {
                best = candidate.getKey();
                most = kinds;
                tie = false;
            } else if (kinds == most) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    private String reviewLine(final Work work, final ObjectNode old, final Map<Person.Id, Set<String>> candidates) {
        final StringBuilder line = new StringBuilder(text(old, "id")).append('\t').append(describe(old))
                .append("\tsign-in '").append(trimmed(text(old, "email"))).append('\'');
        candidates.forEach((anchor, why) -> line.append("\t").append(work.nameOf(anchor)).append(" [")
                .append(anchor.getValue()).append("]: ").append(String.join(", ", why)));
        return line.toString();
    }

    /** Every address an imported person will hold is held by exactly one person, here and in the target. */
    private void checkUniqueEmails(final Work work) {
        final Map<String, String> owners = new LinkedHashMap<>();
        for (final ImportPlan.PersonStep step : work.steps.values()) {
            if (step.action() != ImportPlan.Action.CREATE || step.person().getEmail() == null) {
                continue;
            }
            final String low = step.person().getEmail().toLowerCase(Locale.ROOT);
            final String other = owners.putIfAbsent(low, step.oldId());
            if (other != null) {
                work.blockers.add("Two imported people would share the email '" + low + "': " + other + " and "
                        + step.oldId() + ". Decide (mapping sameAs, or a family decision).");
            } else if (target.personByEmail(low).isPresent()) {
                work.blockers.add("Imported person " + step.oldId() + " would take the email '" + low
                        + "', which an existing person already holds.");
            }
        }
    }

    // ------------------------------------------------------------------ trips

    private void planTrips(final Work work) {
        for (final ObjectNode old : work.export.trips()) {
            final String id = text(old, "id");
            if (target.tripExists(id)) {
                work.notes.add("Trip " + id + " is already in the target (an earlier run): it is not rewritten.");
            }
            final ObjectNode trip = mapper.createObjectNode();
            for (final String field : List.of("id", "title", "description", "startDate", "endDate")) {
                if (old.has(field)) {
                    trip.set(field, old.get(field));
                }
            }
            trip.put("openToPublic", false);
            trip.put("kind", TripKind.CONFERENCE.name());
            trip.put("orgId", mapping.targetOrgId());
            final ArrayNode people = trip.putArray("people");
            remapped(work, old.path("people")).forEach(person -> people.add(person.getValue()));
            trip.set("regOptions", questions(work, old));
            for (final String legacy : LEGACY_TRIP_FIELDS) {
                legacyNote(work, old, legacy);
            }
            work.trips.add(convert(trip, Trip.class));
        }
    }

    private void legacyNote(final Work work, final ObjectNode trip, final String field) {
        final JsonNode value = trip.path(field);
        if (!isBlank(value)) {
            work.notes.add("Trip '" + text(trip, "title") + "': legacy " + field + " not carried as a field ("
                    + (value.isArray() ? value.size() + " entries" : value.asText()) + "); the export keeps it.");
        }
    }

    /**
     * The trip's questions in today's typed form: free text as before; a hidden {@code opt<N>} true/false answer
     * makes question N a yes/no one; the legacy admission options become ONE priced pick-one question, so every
     * registration's admission shows by its label.
     */
    private ArrayNode questions(final Work work, final ObjectNode trip) {
        final String tripId = text(trip, "id");
        final Set<String> yesNo = work.yesNoQuestions(tripId);
        final ArrayNode questions = mapper.createArrayNode();
        int maxId = -1;
        for (final JsonNode old : trip.path("regOptions")) {
            final ObjectNode question = mapper.createObjectNode();
            question.put("id", old.path("id").asInt());
            question.put("shortDesc", old.path("shortDesc").asText(""));
            question.put("longDesc", old.path("longDesc").asText(""));
            question.put("show", old.path("show").asBoolean(true));
            question.put("type", yesNo.contains(old.path("id").asText()) ? "YES_NO" : "TEXT");
            questions.add(question);
            maxId = Math.max(maxId, old.path("id").asInt());
        }
        final JsonNode admissions = trip.path("admissionOptions");
        if (admissions.isArray() && !admissions.isEmpty()) {
            final ObjectNode admission = questions.addObject();
            admission.put("id", maxId + 1);
            admission.put("shortDesc", mapping.admissionQuestion());
            admission.put("longDesc", "");
            admission.put("show", true);
            admission.put("type", "CHOICE");
            final ArrayNode choices = admission.putArray("choices");
            final Map<String, String> labels = new LinkedHashMap<>();
            for (final JsonNode option : admissions) {
                final ObjectNode choice = choices.addObject();
                choice.put("id", option.path("id").asText());
                choice.put("label", option.path("label").asText());
                choice.put("priceCents", Math.round(option.path("price").asDouble() * 100));
                choice.put("enabled", option.path("show").asBoolean(true));
                labels.put(option.path("id").asText(), option.path("label").asText());
            }
            work.admissions.put(tripId, new Admission(String.valueOf(maxId + 1), labels));
        }
        return questions;
    }

    /** A trip's admission question: its key, and each legacy option id's label. */
    record Admission(String key, Map<String, String> labels) {
    }

    // ------------------------------------------------------------------ registrations

    private void planRegistrations(final Work work) {
        final Map<String, Registration> byKey = new LinkedHashMap<>();
        for (final ObjectNode old : work.export.registrations()) {
            final String tripId = text(old, "tripId");
            final Person.Id user = work.idMap.get(text(old, "userId"));
            if (!work.tripIds().contains(tripId)) {
                work.notes.add("Registration of " + text(old, "userId") + " on unknown trip " + tripId + " skipped.");
            } else if (user == null) {
                work.notes.add("Registration of " + work.describeOld(text(old, "userId")) + " on '"
                        + work.titleOf(tripId) + "' skipped (" + text(old, "status") + "): the person is not "
                        + "imported.");
            } else {
                keepBest(work, byKey, registration(work, old, tripId, user));
            }
        }
        work.registrations.addAll(byKey.values());
    }

    /** Two old people who are one person now (a merge, a duplicate) keep their most advanced registration. */
    private static void keepBest(final Work work, final Map<String, Registration> byKey, final Registration reg) {
        final String key = reg.getTripId() + "|" + reg.getUserId().getValue();
        final Registration other = byKey.get(key);
        if (other == null) {
            byKey.put(key, reg);
            return;
        }
        if (rank(reg) == rank(other)) {
            work.blockers.add("Two " + reg.getStatus().getDescription() + " registrations for one person ("
                    + reg.getUserId().getValue() + ") on '" + work.titleOf(reg.getTripId()) + "', created "
                    + reg.getCreated() + " and " + other.getCreated() + ": decide which stands, or keep the two "
                    + "people apart (mapping).");
            return;
        }
        final Registration best = rank(reg) > rank(other) ? reg : other;
        byKey.put(key, best);
        work.notes.add("Two registrations for " + reg.getUserId().getValue() + " on '" + work.titleOf(reg.getTripId())
                + "' (one person now): kept the " + best.getStatus().getDescription() + " one.");
    }

    private static int rank(final Registration reg) {
        return switch (reg.getStatus()) {
            case CONFIRMED -> 2;
            case PENDING -> 1;
            default -> 0;
        };
    }

    private Registration registration(final Work work, final ObjectNode old, final String tripId,
            final Person.Id user) {
        final Map<String, String> options = new LinkedHashMap<>();
        old.path("options").fields().forEachRemaining(entry -> options.put(entry.getKey(), entry.getValue().asText()));
        final Admission admission = work.admissions.get(tripId);
        final String admissionChoice = options.remove(ADMISSION_KEY);
        if (admission != null && admissionChoice != null) {
            options.put(admission.key(), admissionChoice);
        } else if (admissionChoice != null) {
            options.put(ADMISSION_KEY, admissionChoice);
        }
        for (final String question : work.yesNoQuestions(tripId)) {
            final String answer = options.remove("opt" + question);
            if (answer != null) {
                options.put(question, Boolean.parseBoolean(answer) ? "Yes" : "No");
            }
        }
        final String checkedIn = options.remove(CHECKED_IN_KEY);
        final ObjectNode tree = mapper.createObjectNode();
        tree.put("tripId", tripId);
        tree.put("userId", user.getValue());
        tree.set("created", old.get("created"));
        tree.set("status", old.get("status"));
        tree.set("options", mapper.valueToTree(options));
        Registration reg = convert(tree, Registration.class)
                .withRegistrationType(typeOf(admission, admissionChoice, options.get(REG_TYPE_KEY)));
        if (checkedIn != null) {
            reg = reg.withCheckedInAt(timestamp(checkedIn));
        }
        return reg.getStatus() == Registration.Status.CONFIRMED ? RegistrationCommands.withTicket(reg) : reg;
    }

    /** The registration's type: its admission option's label, else the legacy type's mapped label, else none. */
    private String typeOf(final Admission admission, final String admissionChoice, final String legacyType) {
        if (admission != null && admissionChoice != null && admission.labels().containsKey(admissionChoice)) {
            return admission.labels().get(admissionChoice);
        }
        return legacyType == null ? null : mapping.registrationTypeLabels().get(legacyType);
    }

    // ------------------------------------------------------------------ transactions

    private void planTransactions(final Work work) {
        final Map<String, String> tripOfTx = tripOfTransaction(work.export.bindings());
        mapping.transactionTrips().forEach((key, tripId) -> repairBinding(work, tripOfTx, key, tripId));
        final List<ObjectNode> kept = new ArrayList<>();
        for (final ObjectNode old : work.export.transactions()) {
            if (work.idMap.containsKey(text(old, "userId"))) {
                kept.add(old);
            } else {
                work.notes.add("Transaction " + text(old, "txId") + " (" + old.path("amount").asText() + ", "
                        + text(old, "category") + ") of " + work.describeOld(text(old, "userId"))
                        + " skipped: the person is not imported.");
            }
        }
        final Map<String, List<String>> groups = groupMembers(work, kept);
        for (final ObjectNode old : kept) {
            final ObjectNode tree = old.deepCopy();
            tree.put("userId", work.idMap.get(text(old, "userId")).getValue());
            tree.put("orgId", mapping.targetOrgId());
            if (old.has("note") && !old.has("notes")) {
                tree.set("notes", old.get("note"));   // the creator's name for it; the getter writes "note"
            }
            final String group = text(old, "groupId");
            if (group != null && groups.containsKey(group)) {
                tree.set("groupPeople", mapper.valueToTree(groups.get(group)));
            }
            final String tripId = tripOfTx.get(text(old, "userId") + "," + text(old, "txId"));
            if (tripId == null) {
                work.notes.add("Transaction " + text(old, "txId") + " has no trip binding in the export.");
            }
            work.transactions.add(new ImportPlan.TxStep(convert(tree, Transaction.class), tripId));
        }
    }

    /** The mapping's repair of a ledger row the export holds no trip binding for. */
    private static void repairBinding(final Work work, final Map<String, String> tripOfTx, final String key,
            final String tripId) {
        final String was = tripOfTx.putIfAbsent(key, tripId);
        work.notes.add(was == null ? "Transaction " + key + " bound to trip " + tripId + " (mapping transactionTrips)."
                : "mapping transactionTrips: " + key + " is already bound to " + was + "; the mapping is ignored.");
    }

    /** {@code userId,txId} -> trip id, from the TRANSACTION -> TRIP binding rows. */
    private static Map<String, String> tripOfTransaction(final List<LegacyExport.Binding> bindings) {
        final Map<String, String> trips = new LinkedHashMap<>();
        for (final LegacyExport.Binding binding : bindings) {
            if ("TRANSACTION".equals(binding.id1Type()) && "TRIP".equals(binding.id2Type())
                    && binding.id1().startsWith(TRANSACTION_PREFIX) && binding.id2().startsWith(TRIP_PREFIX)) {
                trips.put(binding.id1().substring(TRANSACTION_PREFIX.length()),
                        binding.id2().substring(TRIP_PREFIX.length()));
            }
        }
        return trips;
    }

    /**
     * A group's membership as {@code docs/migrations/group-tx-membership.md} stamps it: the sorted ids of its
     * live (not deleted) rows' people, here after the id mapping. A row that already names its group is remapped.
     */
    private static Map<String, List<String>> groupMembers(final Work work, final List<ObjectNode> rows) {
        final Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (final ObjectNode row : rows) {
            final String group = text(row, "groupId");
            if (group == null || !isBlank(row.get("deleted"))) {
                continue;
            }
            groups.computeIfAbsent(group, key -> new TreeSet<>()).add(work.idMap.get(text(row, "userId")).getValue());
        }
        final Map<String, List<String>> members = new LinkedHashMap<>();
        groups.forEach((group, ids) -> members.put(group, List.copyOf(ids)));
        return members;
    }

    // ------------------------------------------------------------------ roles

    private void planRoles(final Work work) {
        for (final ObjectNode old : work.export.privileges()) {
            final String name = text(old, "name");
            final String tripId = work.tripIds().stream().filter(name::endsWith).findFirst().orElse(null);
            if (tripId == null) {
                work.notes.add("Privilege '" + name + "' is not scoped to an imported trip: not migrated.");
                continue;
            }
            final String base = mapping.roleFor(name.substring(0, name.length() - tripId.length()));
            work.roles.add(new ImportPlan.RoleStep(base, tripId, remapped(work, old.path("people"))));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The listed old person ids as their target ids, in order, without duplicates or unimported ones. */
    private static List<Person.Id> remapped(final Work work, final JsonNode ids) {
        final Set<Person.Id> out = new LinkedHashSet<>();
        for (final JsonNode id : ids) {
            final Person.Id mapped = work.idMap.get(id.asText());
            if (mapped != null) {
                out.add(mapped);
            }
        }
        return List.copyOf(out);
    }

    /** The old row as today's model writes a person: the same field names an existing row has. */
    private ObjectNode normalized(final ObjectNode old) {
        final ObjectNode tree = old.deepCopy();
        NOT_CARRIED.forEach(tree::remove);
        return mapper.valueToTree(toPerson(tree));
    }

    private Person toPerson(final ObjectNode tree) {
        return convert(tree, Person.class);
    }

    private <T> T convert(final ObjectNode tree, final Class<T> type) {
        try {
            return mapper.treeToValue(tree, type);
        } catch (final JsonProcessingException ex) {
            throw new IllegalArgumentException("Cannot read a " + type.getSimpleName() + " from " + tree, ex);
        }
    }

    static boolean isAddress(final String email) {
        final int at = email == null ? -1 : email.indexOf('@');
        return at > 0 && at < email.length() - 1;
    }

    /**
     * The same name AND no conflicting birthdate: a mother and the baby named after her share a name and a phone,
     * so the name alone is not a person.
     */
    private static boolean samePerson(final ObjectNode old, final Person existing) {
        final String born = text(old, "birthdate");
        return sameName(old, existing) && (born == null || existing.getBirthdate() == null
                || born.startsWith(existing.getBirthdate().toString()));
    }

    private static boolean sameName(final ObjectNode old, final Person existing) {
        return existing != null && norm(text(old, "first")).equals(norm(existing.getFirst()))
                && norm(text(old, "last")).equals(norm(existing.getLast()));
    }

    static boolean isBlank(final JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().isBlank();
        }
        if (node.isContainerNode()) {
            for (final JsonNode child : node) {
                if (!isBlank(child)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    static boolean sameText(final JsonNode one, final JsonNode two) {
        if (isBlank(one) || isBlank(two)) {
            return isBlank(one) && isBlank(two);
        }
        return one.isTextual() && two.isTextual() ? norm(one.asText()).equals(norm(two.asText())) : one.equals(two);
    }

    static String norm(final String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String text(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String trimmed(final String text) {
        return text == null ? null : text.trim();
    }

    static LocalDateTime timestamp(final String text) {
        try {
            return text == null ? null : LocalDateTime.parse(text.length() > 19 ? text.substring(0, 19) : text);
        } catch (final DateTimeParseException ex) {
            return null;
        }
    }

    static String describe(final JsonNode old) {
        return text(old, "first") + " " + text(old, "last") + " (" + text(old, "id") + ")";
    }

    /** The planner's working state, turned into the immutable plan at the end. */
    final class Work {
        final LegacyExport export;
        final Map<String, ObjectNode> oldPeople = new LinkedHashMap<>();
        final Map<String, ImportPlan.PersonStep> steps = new LinkedHashMap<>();
        final Map<String, Person.Id> idMap = new LinkedHashMap<>();
        final Set<String> addressless = new LinkedHashSet<>();
        final List<ImportPlan.FamilyLink> families = new ArrayList<>();
        final List<Trip> trips = new ArrayList<>();
        final List<Registration> registrations = new ArrayList<>();
        final List<ImportPlan.TxStep> transactions = new ArrayList<>();
        final List<ImportPlan.RoleStep> roles = new ArrayList<>();
        final Map<String, Admission> admissions = new LinkedHashMap<>();
        final List<String> review = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> blockers = new ArrayList<>();

        Work(final LegacyExport export) {
            this.export = export;
            export.people().forEach(person -> oldPeople.put(text(person, "id"), person));
        }

        void step(final ImportPlan.PersonStep step) {
            steps.put(step.oldId(), step);
            if (step.targetId() != null) {
                idMap.put(step.oldId(), step.targetId());
            }
        }

        Set<String> tripIds() {
            final Set<String> ids = new LinkedHashSet<>();
            export.trips().forEach(trip -> ids.add(text(trip, "id")));
            return ids;
        }

        String titleOf(final String tripId) {
            return export.trips().stream().filter(trip -> tripId.equals(text(trip, "id")))
                    .map(trip -> text(trip, "title")).findFirst().orElse(tripId);
        }

        /** The ids N of a trip's questions answered by a hidden {@code opt<N>} true/false key. */
        Set<String> yesNoQuestions(final String tripId) {
            final Set<String> ids = new TreeSet<>();
            for (final ObjectNode reg : export.registrations()) {
                if (tripId.equals(text(reg, "tripId"))) {
                    reg.path("options").fieldNames().forEachRemaining(key -> addYesNo(ids, reg, key));
                }
            }
            return ids;
        }

        private void addYesNo(final Set<String> ids, final ObjectNode reg, final String key) {
            final String value = reg.path("options").path(key).asText();
            if (key.matches("opt\\d+") && ("true".equals(value) || "false".equals(value))) {
                ids.add(key.substring(3));
            }
        }

        /** The person a target id resolves to (the step that creates or merges them). */
        Person personOf(final Person.Id targetId) {
            final ImportPlan.PersonStep step = stepOfTarget(targetId);
            return step == null ? null : step.person();
        }

        /** The step that creates or merges the person with this target id. */
        ImportPlan.PersonStep stepOfTarget(final Person.Id targetId) {
            return steps.values().stream()
                    .filter(step -> targetId.equals(step.targetId()) && step.person() != null)
                    .findFirst().orElse(null);
        }

        String describeOld(final String oldId) {
            final ObjectNode old = oldPeople.get(oldId);
            return old == null ? oldId : describe(old);
        }

        String nameOf(final Person.Id targetId) {
            return steps.values().stream().filter(step -> targetId.equals(step.targetId()) && step.person() != null)
                    .map(step -> step.person().getFirst() + " " + step.person().getLast())
                    .findFirst().orElse(targetId.getValue());
        }

        ImportPlan toPlan() {
            final List<ImportPlan.PersonStep> ordered = new ArrayList<>();
            for (final ObjectNode old : export.people()) {
                final ImportPlan.PersonStep step = steps.get(text(old, "id"));
                if (step != null) {
                    ordered.add(step);
                }
            }
            return new ImportPlan(Map.copyOf(idMap), List.copyOf(ordered), List.copyOf(families), List.copyOf(trips),
                    List.copyOf(registrations), List.copyOf(transactions), List.copyOf(roles), List.copyOf(review),
                    List.copyOf(notes), List.copyOf(blockers));
        }
    }

    /**
     * The evidence that an address-less person belongs to a family: someone imported WITH an address who shares
     * their phone, their home address, or their surname with a registration on the same trip within the window.
     */
    static final class FamilyEvidence {
        private final Work work;
        private final Duration window;
        private final Map<String, Set<String>> byPhone = new LinkedHashMap<>();
        private final Map<String, Set<String>> byHome = new LinkedHashMap<>();

        FamilyEvidence(final Work work, final int windowHours) {
            this.work = work;
            this.window = Duration.ofHours(windowHours);
            for (final Map.Entry<String, ObjectNode> entry : work.oldPeople.entrySet()) {
                if (isAnchor(entry.getKey())) {
                    index(byPhone, phone(entry.getValue()), entry.getKey());
                    index(byHome, home(entry.getValue()), entry.getKey());
                }
            }
        }

        /** Each candidate's target id with the evidence for them, without the person themself. */
        Map<Person.Id, Set<String>> candidatesFor(final String oldId) {
            final ObjectNode person = work.oldPeople.get(oldId);
            final Map<Person.Id, Set<String>> found = new LinkedHashMap<>();
            add(found, byPhone.get(phone(person)), "same phone");
            add(found, byHome.get(home(person)), "same home address");
            for (final ObjectNode mine : registrationsOf(oldId)) {
                for (final ObjectNode other : work.export.registrations()) {
                    if (sameFamilyRegistration(person, mine, other)) {
                        add(found, Set.of(text(other, "userId")), "same surname registering within "
                                + window.toHours() + "h on '" + work.titleOf(text(mine, "tripId")) + "'");
                    }
                }
            }
            return found;
        }

        private boolean sameFamilyRegistration(final ObjectNode person, final ObjectNode mine,
                final ObjectNode other) {
            final String otherId = text(other, "userId");
            final ObjectNode otherPerson = work.oldPeople.get(otherId);
            final LocalDateTime when = timestamp(text(mine, "created"));
            final LocalDateTime otherWhen = timestamp(text(other, "created"));
            return otherPerson != null && isAnchor(otherId) && text(mine, "tripId").equals(text(other, "tripId"))
                    && !norm(text(person, "last")).isEmpty()
                    && norm(text(person, "last")).equals(norm(text(otherPerson, "last")))
                    && when != null && otherWhen != null
                    && Duration.between(when, otherWhen).abs().compareTo(window) <= 0;
        }

        private List<ObjectNode> registrationsOf(final String oldId) {
            return work.export.registrations().stream().filter(reg -> oldId.equals(text(reg, "userId"))).toList();
        }

        private void add(final Map<Person.Id, Set<String>> found, final Set<String> oldIds, final String why) {
            if (oldIds == null) {
                return;
            }
            for (final String oldId : oldIds) {
                found.computeIfAbsent(work.idMap.get(oldId), key -> new LinkedHashSet<>()).add(why);
            }
        }

        /** Someone a family can form around: imported, with a real address. */
        private boolean isAnchor(final String oldId) {
            final ImportPlan.PersonStep step = work.steps.get(oldId);
            return step != null && step.targetId() != null && step.person() != null
                    && isAddress(step.person().getEmail());
        }

        private static void index(final Map<String, Set<String>> map, final String key, final String oldId) {
            if (key != null) {
                map.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(oldId);
            }
        }

        static String phone(final JsonNode person) {
            final String digits = text(person, "cell") == null ? "" : text(person, "cell").replaceAll("\\D", "");
            return digits.length() < 10 ? null : digits.substring(digits.length() - 10);
        }

        static String home(final JsonNode person) {
            final JsonNode address = person.path("address");
            final String street = norm(text(address, "street"));
            final String zip = norm(text(address, "zip"));
            return street.isEmpty() || zip.isEmpty() ? null : street + "|" + zip;
        }
    }
}
