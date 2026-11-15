package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ReminderDefaults;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ReminderSend;
import org.paulsens.trip.model.TemplateKind;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPaymentConfig;
import org.paulsens.trip.model.TripReminder;
import org.paulsens.trip.model.TripReminderPlan;
import org.paulsens.trip.security.Digests;
import org.paulsens.trip.util.EmailAddresses;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * A trip's Communications page, exposed as {@code #{reminder}} ({@code docs/reminders.md}): what the trip says
 * to its travelers and when. It lists the scheduled messages, lets a facilitator word, preview, test and
 * APPROVE each one, shows the mail the trip already sends by itself, and shows what actually went out.
 *
 * <p>The promise the page makes is that nothing scheduled goes out unseen. This bean keeps its half by writing
 * every new or edited message as a DRAFT; the other half is {@link TripReminder#isApprovedFor}, which
 * {@link ReminderSender} re-checks at the moment of sending. One gate ({@link ReminderGate}) covers every
 * method: whoever can see a trip's messages is whoever answers for them.
 */
@Slf4j
@Named("reminder")
@ApplicationScoped
public class ReminderCommands {
    static final int MAX_REMINDERS = 40;
    static final int MAX_NAME = 80;
    static final int MAX_SUBJECT = 200;
    static final int MAX_BODY = 50_000;
    static final int MAX_PUSH = 180;
    static final int MAX_DAYS = 365;
    private static final String NOT_ALLOWED = "Not allowed: only the trip's facilitators, its managers and the "
            + "organization's admins can manage its messages.";
    private static final String TOO_LATE = "A message can be scheduled at most "
            + TripReminder.MAX_DAYS_AFTER_END + " days after the trip ends.";
    private static final String CHANGED_SINCE_SHOWN = "This message changed since you opened it: review it again.";
    /** Test emails one person may send in one clock hour: plenty for wording work, useless as a mail relay. */
    static final int MAX_TESTS_PER_HOUR = 20;
    /** The raw body is refused above this before it is cleaned: the cleaner's cost grows with its input. */
    static final int MAX_RAW_BODY = 4 * MAX_BODY;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    /** The test-send counts kept here when the cache cannot count ({@link #underTestLimit}). */
    private static final Map<String, AtomicLong> LOCAL_TEST_COUNTS = new ConcurrentHashMap<>();

    private final Supplier<Caller> callerSource;
    private final Supplier<AuditCommands> auditSource;
    private final Supplier<ReminderSender> senderSource;
    private final Supplier<Instant> clock;
    private final ReminderGate gate;
    private final ReminderRecipients recipients;

    public ReminderCommands() {
        this(Caller::current);
    }

    /** Test seam (the BudgetCommands pattern): {@code Caller.current()} needs a FacesContext tests lack. */
    public ReminderCommands(final Supplier<Caller> callerSource) {
        this(callerSource, AuditCommands::new, ReminderSender::new, new ReminderRecipients(), Instant::now);
    }

    ReminderCommands(final Supplier<Caller> callerSource, final Supplier<AuditCommands> auditSource,
            final Supplier<ReminderSender> senderSource, final ReminderRecipients recipients,
            final Supplier<Instant> clock) {
        this.callerSource = callerSource;
        this.auditSource = auditSource;
        this.senderSource = senderSource;
        this.recipients = recipients;
        this.clock = clock;
        final Supplier<OrgCommands> orgSource = () -> new OrgCommands(callerSource);
        this.gate = new ReminderGate(callerSource, () -> new TripCommands(orgSource), orgSource);
    }

    // ================================================================== gate and clock

    /** May see, write, approve and send this trip's messages ({@link ReminderGate}). */
    public boolean canManage(final String tripId) {
        return gate.canManage(tripId);
    }

    /** Whether the site's daily send is switched on; off, approved messages wait (Send now still works). */
    public boolean isSendingOn() {
        return new ConfigCommands().getBoolean(KnownSettings.REMINDERS_ENABLED);
    }

    /** Today where the messages are scheduled: {@link ReminderClock}, the scheduler's own answer. */
    LocalDate today() {
        return ReminderClock.today(new ConfigCommands(), clock.get());
    }

    private Caller caller() {
        return callerSource.get();
    }

    private static TripReminderPlan planOf(final Trip trip) {
        return DAO.getInstance().getReminderPlan(trip.getId()).orElseGet(() -> TripReminderPlan.builder()
                .tripId(trip.getId()).orgId(trip.getOrgId()).build());
    }

    // ================================================================== the dashboard card

    /**
     * The Reports dashboard card, from ONE read of the plan: the big line says what needs the facilitator, else
     * what happens next; the small line how many messages are scheduled and how many of them are approved.
     */
    public ReminderViews.Card card(final String tripId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        if (trip == null) {
            return new ReminderViews.Card("", "");
        }
        final List<TripReminder> reminders = planOf(trip).getReminders();
        if (reminders.isEmpty()) {
            return new ReminderViews.Card("No messages yet", "Nothing is scheduled to go to this trip's travelers.");
        }
        final long approved = reminders.stream().filter(r -> r.isApprovedFor(trip)).count();
        final long waiting = reminders.size() - approved;
        final String subline = approved + " of " + reminders.size() + " scheduled messages approved. Only approved "
                + "messages are sent.";
        if (waiting > 0) {
            return new ReminderViews.Card(waiting + ((waiting == 1) ? " needs approval" : " need approval"), subline);
        }
        final LocalDate next = nextSend(trip, reminders);
        return new ReminderViews.Card((next == null) ? "All sent" : "Next: " + DAY.format(next), subline);
    }

    /** The earliest date an approved message is still to go out on; null when every one has been sent. */
    private LocalDate nextSend(final Trip trip, final List<TripReminder> reminders) {
        final LocalDate today = today();
        final List<ReminderSend> history = DAO.getInstance().getReminderSends(trip.getId());
        return reminders.stream().filter(r -> r.isApprovedFor(trip) && !hasRun(history, r, r.sendDate(trip)))
                .map(r -> r.sendDate(trip)).filter(date -> !date.isBefore(today))
                .min(Comparator.naturalOrder()).orElse(null);
    }

    private static boolean hasRun(final List<ReminderSend> history, final TripReminder reminder,
            final LocalDate date) {
        return history.stream()
                .anyMatch(s -> reminder.getId().equals(s.getReminderId()) && date.equals(s.getSendDate()));
    }

    // ================================================================== the schedule

    /** The trip's scheduled messages, earliest send date first, undated ones last. */
    public List<ReminderViews.Row> rows(final String tripId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        if (trip == null) {
            return List.of();
        }
        final List<ReminderSend> history = DAO.getInstance().getReminderSends(tripId);
        final LocalDate today = today();
        final List<TripReminder> ordered = new ArrayList<>(planOf(trip).getReminders());
        ordered.sort(Comparator.comparing((TripReminder r) -> r.sendDate(trip),
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(TripReminder::getName));
        final List<ReminderViews.Row> rows = new ArrayList<>();
        for (final TripReminder reminder : ordered) {
            rows.add(rowOf(trip, reminder, history, today));
        }
        return rows;
    }

    private ReminderViews.Row rowOf(final Trip trip, final TripReminder reminder, final List<ReminderSend> history,
            final LocalDate today) {
        final LocalDate date = reminder.sendDate(trip);
        final boolean approved = reminder.isApprovedFor(trip);
        final List<ReminderSend> ran = (date == null) ? List.of() : history.stream()
                .filter(s -> reminder.getId().equals(s.getReminderId()) && date.equals(s.getSendDate())).toList();
        final Status status = statusOf(trip, reminder, date, approved, ran, today);
        return ReminderViews.Row.builder().id(reminder.getId()).name(reminder.getName())
                .kindLabel(reminder.getKind().getLabel()).scheduleText(reminder.getScheduleText())
                .sendDateText((date == null) ? "No date" : DAY.format(date))
                .audienceLabel(reminder.getAudience().getLabel())
                .channel(reminder.isPush() ? "Email and app notification" : "Email")
                .statusLabel(status.label()).statusClass(status.styleClass()).statusNote(status.note())
                .approved(approved).approvable(date != null && !approved)
                // Offered again after a run: the ledger keeps everyone already sent to one message, so a second
                // click reaches only a traveler whose failed send was cleared, or who joined the audience since.
                .sendable(approved && !date.isAfter(today))
                .sendLabel(ran.isEmpty() ? "Send now" : "Send to anyone missed").build();
    }

    /** What a row says about where its message stands. */
    private record Status(String label, String styleClass, String note) {
    }

    private Status statusOf(final Trip trip, final TripReminder reminder, final LocalDate date,
            final boolean approved, final List<ReminderSend> ran, final LocalDate today) {
        if (date == null) {
            return new Status("No date", "nodate", missingDateNote(reminder));
        }
        if (reminder.isApprovalStale(trip)) {
            return new Status("Changed, approve again", "changed",
                    "The wording or the date is not what was approved, so it will not be sent.");
        }
        if (!approved) {
            return new Status("Draft", "draft", date.isBefore(today)
                    ? "Not approved, and its date has passed: approving it now only allows Send now."
                    : "Not approved, so it will not be sent.");
        }
        if (!ran.isEmpty()) {
            return ranStatus(ran);
        }
        final String by = "Approved by " + nameOf(reminder.getApprovedBy()) + ".";
        if (date.isBefore(today.minusDays(1))) {
            return new Status("Missed", "missed", by + " Its date passed before it could be sent; use Send now "
                    + "if it should still go out.");
        }
        return new Status("Approved", "approved",
                by + (isSendingOn() ? "" : " Scheduled sending is switched off for this site."));
    }

    /** The status of a message whose run happened: how many it reached, and how many sends did not go through. */
    private static Status ranStatus(final List<ReminderSend> ran) {
        final long sent = ran.stream().filter(s -> s.getOutcome() == ReminderSend.Outcome.SENT).count();
        final long failed = ran.stream().filter(s -> s.getOutcome() == ReminderSend.Outcome.FAILED
                || s.getOutcome() == ReminderSend.Outcome.CLAIMED).count();
        final String note = "Sent to " + sent + ((sent == 1) ? " person." : " people.")
                + ((failed == 0) ? "" : " " + failed + " failed: see the sent history.");
        return (sent == 0 && failed > 0) ? new Status("Failed", "failed", note) : new Status("Sent", "sent", note);
    }

    private static String missingDateNote(final TripReminder reminder) {
        return switch (reminder.getAnchor()) {
            case BALANCE_DUE_DATE -> "The trip has no balance due date (Registration Pricing), so this has no date.";
            case FIXED_DATE -> "Choose a date.";
            default -> "The trip has no " + ((reminder.getAnchor() == TripReminder.Anchor.TRIP_END) ? "end" : "start")
                    + " date, so this has no date.";
        };
    }

    private static String nameOf(final Person.Id personId) {
        final Person person = (personId == null) ? null
                : DAO.getInstance().getPerson(personId, Cached.YES).orElse(null);
        return (person == null) ? "someone" : CheckinCommands.displayName(person);
    }

    // ================================================================== the message dialog

    /** The token vocabulary, for the dialog's reference list. */
    public List<ReminderText.Token> getTokens() {
        return ReminderText.TOKENS;
    }

    /** The dates a message can count from, for the dialog's menu: "The trip start", "A date I choose". */
    public List<ReminderViews.Choice> anchorChoices() {
        final List<ReminderViews.Choice> out = new ArrayList<>();
        for (final TripReminder.Anchor anchor : TripReminder.Anchor.values()) {
            final String label = anchor.getLabel();
            out.add(new ReminderViews.Choice(anchor.name(), label.substring(0, 1).toUpperCase(Locale.ROOT)
                    + label.substring(1)));
        }
        return out;
    }

    /** Who a custom message can go to, for the dialog's menu. */
    public List<ReminderViews.Choice> audienceChoices() {
        final List<ReminderViews.Choice> out = new ArrayList<>();
        for (final TripReminder.Audience audience : TripReminder.Audience.values()) {
            out.add(new ReminderViews.Choice(audience.name(), audience.getLabel()));
        }
        return out;
    }

    /** The audience of the message in the dialog, in words: a built-in kind's is fixed and only shown. */
    public String audienceLabel(final ReminderViews.Form form) {
        final TripReminder.Kind kind = (form == null) ? null : parse(TripReminder.Kind.class, form.getKind());
        return (kind == null || kind.getAudience() == null) ? "" : kind.getAudience().getLabel();
    }

    /** A blank form for "Add custom message". */
    public ReminderViews.Form newForm() {
        return new ReminderViews.Form();
    }

    /** The form for editing one message; a blank form when it no longer exists. */
    public ReminderViews.Form formFor(final String tripId, final String reminderId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        final TripReminder reminder = (trip == null) ? null : planOf(trip).reminder(reminderId);
        final ReminderViews.Form form = new ReminderViews.Form();
        if (reminder == null) {
            return form;
        }
        form.setId(reminder.getId());
        form.setKind(reminder.getKind().name());
        form.setName(reminder.getName());
        form.setAnchor(reminder.getAnchor().name());
        form.setTiming((reminder.getOffsetDays() < 0) ? "BEFORE" : (reminder.getOffsetDays() > 0) ? "AFTER" : "ON");
        form.setDays(Math.abs(reminder.getOffsetDays()));
        form.setFixedDate(reminder.getFixedDate());
        form.setAudience(reminder.getAudience().name());
        form.setSubject(reminder.getSubject());
        // Cleaned on the way out like every other place the body is written: the editor loads it as HTML.
        form.setBody(ReminderHtml.clean(reminder.getBody()));
        form.setPushText(reminder.getPushText());
        return form;
    }

    /**
     * Adds or edits one message. Always stored as a DRAFT: whatever was approved before, the facilitator has
     * to look at the new wording or date and approve that. Refusals land on {@code form.problem}.
     */
    public boolean save(final String tripId, final ReminderViews.Form form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        if (form == null) {
            return refuse("Nothing to save.");
        }
        final String problem = formProblem(form);
        if (problem != null) {
            return refused(refusal, problem);
        }
        final TripReminderPlan plan = planOf(trip);
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final TripReminder existing = adding ? null : plan.reminder(form.getId());
        if (!adding && existing == null) {
            return refused(refusal, "This message no longer exists: reload the page.");
        }
        if (adding && plan.getReminders().size() >= MAX_REMINDERS) {
            return refused(refusal, "A trip holds at most " + MAX_REMINDERS + " scheduled messages.");
        }
        final TripReminder.Anchor anchor = parse(TripReminder.Anchor.class, form.getAnchor());
        final boolean dated = anchor == TripReminder.Anchor.FIXED_DATE;
        final int days = (dated || "ON".equals(form.getTiming())) ? 0 : form.getDays();
        final TripReminder reminder = TripReminder.builder()
                .id(adding ? UUID.randomUUID().toString() : existing.getId())
                // The kind is what the message IS (and fixes its audience), so an edit can never change it.
                .kind(adding ? TripReminder.Kind.CUSTOM : existing.getKind())
                .name(form.getName().trim()).anchor(anchor)
                .offsetDays("BEFORE".equals(form.getTiming()) ? -days : days)
                .fixedDate(dated ? form.getFixedDate() : null)
                .audience(parse(TripReminder.Audience.class, form.getAudience()))
                .subject(form.getSubject().trim()).body(cleanBody(form.getBody()))
                .pushText((form.getPushText() == null) ? "" : form.getPushText().trim())
                .status(TripReminder.Status.DRAFT).build();
        if (!reminder.isReachable(trip)) {
            return refused(refusal, TOO_LATE);
        }
        plan.put(reminder);
        if (!persist(trip, plan, refusal, (adding ? "added" : "edited") + " '" + reminder.getName() + "' (draft)")) {
            return false;
        }
        form.setId(reminder.getId());
        form.setProblem(null);
        info("Saved as a draft. It will not be sent until it is approved.");
        return true;
    }

    /**
     * The body as it is stored: Quill may percent-encode a placeholder typed as a link address, so the braces
     * are put back first, and then everything not on {@link ReminderHtml}'s allowlist is removed. A facilitator
     * writes this text and admins read it unescaped, so it is an allowlist, never a blocklist.
     */
    static String cleanBody(final String html) {
        final String braces = (html == null) ? "" : html.replace("%7B%7B", "{{").replace("%7D%7D", "}}")
                .replace("%7b%7b", "{{").replace("%7d%7d", "}}");
        return ReminderHtml.clean(braces);
    }

    private static String formProblem(final ReminderViews.Form form) {
        if (form.getName() == null || form.getName().isBlank()) {
            return "Give the message a name.";
        }
        if (form.getName().trim().length() > MAX_NAME) {
            return "The name is longer than " + MAX_NAME + " characters.";
        }
        final TripReminder.Anchor anchor = parse(TripReminder.Anchor.class, form.getAnchor());
        if (anchor == null) {
            return "Choose when it is sent.";
        }
        if (anchor == TripReminder.Anchor.FIXED_DATE && form.getFixedDate() == null) {
            return "Choose the date it is sent.";
        }
        if (anchor != TripReminder.Anchor.FIXED_DATE && !List.of("BEFORE", "ON", "AFTER").contains(form.getTiming())) {
            return "Choose before, on or after.";
        }
        if (form.getDays() < 0 || form.getDays() > MAX_DAYS) {
            return "The number of days must be between 0 and " + MAX_DAYS + ".";
        }
        if (form.isCustom() && parse(TripReminder.Audience.class, form.getAudience()) == null) {
            return "Choose who receives it.";
        }
        return wordingProblem(form);
    }

    private static String wordingProblem(final ReminderViews.Form form) {
        if (form.getSubject() == null || form.getSubject().isBlank()) {
            return "Write a subject line.";
        }
        if (form.getSubject().trim().length() > MAX_SUBJECT) {
            return "The subject is longer than " + MAX_SUBJECT + " characters.";
        }
        if (form.getBody() != null && form.getBody().length() > MAX_RAW_BODY) {
            return "The message is too long.";
        }
        final String body = cleanBody(form.getBody());
        if (WaiverHtml.isBlank(body)) {
            return "Write the message.";
        }
        if (body.length() > MAX_BODY) {
            return "The message is too long.";
        }
        if (form.getPushText() != null && form.getPushText().trim().length() > MAX_PUSH) {
            return "The app notification text is longer than " + MAX_PUSH + " characters.";
        }
        final List<String> unknown = new ArrayList<>(ReminderText.unknownTokens(form.getSubject()));
        unknown.addAll(ReminderText.unknownTokens(body));
        unknown.addAll(ReminderText.unknownTokens(form.getPushText()));
        return unknown.isEmpty() ? null : "Unknown placeholder {{" + unknown.get(0) + "}}: use one from the list.";
    }

    /** Deletes one message. Its sent history stays. */
    public boolean delete(final String tripId, final String reminderId) {
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        final TripReminderPlan plan = planOf(trip);
        final TripReminder reminder = plan.reminder(reminderId);
        if (reminder == null || !plan.remove(reminderId)) {
            return refuse("This message no longer exists: reload the page.");
        }
        if (!persist(trip, plan, PageFeedback::refuse, "deleted '" + reminder.getName() + "'")) {
            return false;
        }
        info("Message deleted.");
        return true;
    }

    /**
     * Adds the suggested messages as DRAFTS, worded from the organization's starter templates. One that is
     * already on the trip (same kind and schedule) is not added twice.
     */
    public boolean addSuggested(final String tripId) {
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        final TripReminderPlan plan = planOf(trip);
        int added = 0;
        for (final ReminderDefaults.Suggestion suggestion : ReminderDefaults.SUGGESTIONS) {
            if (!hasSuggestion(plan, suggestion) && plan.getReminders().size() < MAX_REMINDERS) {
                final ReminderDefaults.Text text = startingText(suggestion.templateId(), trip.getOrgId());
                plan.put(TripReminder.builder().id(UUID.randomUUID().toString()).kind(suggestion.kind())
                        .name(suggestion.name()).anchor(suggestion.anchor()).offsetDays(suggestion.offsetDays())
                        .subject(text.subject()).body(cleanBody(text.body())).status(TripReminder.Status.DRAFT)
                        .build());
                added++;
            }
        }
        if (added == 0) {
            info("The suggested messages are already on this trip.");
            return true;
        }
        if (!persist(trip, plan, PageFeedback::refuse, "added " + added + " suggested message(s) as drafts")) {
            return false;
        }
        info(added + " suggested " + ((added == 1) ? "message" : "messages") + " added as drafts. Review and approve "
                + "each one you want sent.");
        return true;
    }

    private static boolean hasSuggestion(final TripReminderPlan plan, final ReminderDefaults.Suggestion suggestion) {
        return plan.getReminders().stream().anyMatch(r -> r.getKind() == suggestion.kind()
                && r.getAnchor() == suggestion.anchor() && r.getOffsetDays() == suggestion.offsetDays());
    }

    /** The org's own copy of a starter text when it has one, else the installed one, else the built-in text. */
    static ReminderDefaults.Text startingText(final String templateId, final String orgId) {
        final ReminderDefaults.Text builtIn = ReminderDefaults.text(templateId);
        final ContentTemplate stored = new TemplateCommands().resolveForOrg(templateId, orgId);
        if (stored == null || stored.getKind() != TemplateKind.MAIL
                || !ReminderText.unknownTokens(stored.getName() + stored.getBody()).isEmpty()) {
            // A missing template, or one reworded with a placeholder this page cannot fill, falls back to the
            // text that is known to render rather than putting a broken draft in front of the facilitator.
            return builtIn;
        }
        return new ReminderDefaults.Text(templateId, stored.getName(), builtIn.description(), stored.getBody());
    }

    // ================================================================== approve, withdraw, send

    /**
     * Approves one message as the facilitator was SHOWN it. {@code shownHash} is the {@link ReminderViews.Review}
     * the review dialog rendered ({@code TripReminder.approvalHash}); when the stored message no longer hashes to
     * it (someone edited it, or the trip's date moved, while the dialog was open) the approval is refused. So an
     * approval can only ever cover wording, an audience and a date somebody actually looked at.
     */
    public boolean approve(final String tripId, final String reminderId, final String shownHash) {
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        final TripReminderPlan plan = planOf(trip);
        final TripReminder reminder = plan.reminder(reminderId);
        if (reminder == null) {
            return refuse("This message no longer exists: reload the page.");
        }
        final LocalDate date = reminder.sendDate(trip);
        if (date == null) {
            return refuse("This message has no send date yet, so it cannot be approved.");
        }
        if (!Digests.matches(reminder.approvalHash(date), shownHash)) {
            return refuse(CHANGED_SINCE_SHOWN);
        }
        if (!reminder.isReachable(trip)) {
            return refuse(TOO_LATE);
        }
        if (senderFrom(trip, plan) == null) {
            return refuse("Set the From address for this trip's messages first.");
        }
        plan.put(reminder.approved(trip, caller().personId(), clock.get()));
        if (!persist(trip, plan, PageFeedback::refuse,
                "APPROVED '" + reminder.getName() + "' to send on " + date)) {
            return false;
        }
        info("Approved. '" + reminder.getName() + "' " + whenItGoes(date) + ".");
        return true;
    }

    /**
     * What really happens next to a message approved now, in words for the confirmation. It never promises a
     * daily run that will not come: not while scheduled sending is off, not for a date already past (the next
     * run's one-day catch-up may or may not still reach it, so the facilitator is pointed at Send now), and not
     * for today once today's run hour has gone by (tomorrow's catch-up sends it, a day late).
     */
    String whenItGoes(final LocalDate date) {
        final LocalDate today = today();
        final boolean on = isSendingOn();
        if (date.isAfter(today)) {
            return "will be sent on " + DAY.format(date) + (on ? "" : " once scheduled sending is on");
        }
        if (date.isBefore(today)) {
            return "was dated " + DAY.format(date) + ", which has passed: use Send now to send it";
        }
        if (!on) {
            return "is due today, and scheduled sending is off: use Send now to send it";
        }
        final ConfigCommands config = new ConfigCommands();
        final int runHour = config.getInt(KnownSettings.REMINDERS_HOUR, 0, 23);
        return (clock.get().atZone(ReminderClock.zone(config)).getHour() < runHour)
                ? "is due today and goes out with today's run"
                : "is due today, after today's run: use Send now, or it goes out with tomorrow's run";
    }

    /** Withdraws an approval: the message stays, as a draft, and will not be sent. */
    public boolean withdraw(final String tripId, final String reminderId) {
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        final TripReminderPlan plan = planOf(trip);
        final TripReminder reminder = plan.reminder(reminderId);
        if (reminder == null) {
            return refuse("This message no longer exists: reload the page.");
        }
        plan.put(reminder.asDraft());
        if (!persist(trip, plan, PageFeedback::refuse, "withdrew the approval of '" + reminder.getName() + "'")) {
            return false;
        }
        info("Approval withdrawn. '" + reminder.getName() + "' will not be sent.");
        return true;
    }

    /**
     * Sends an approved message now, to whoever it is about today. Only for a message whose date has come: a
     * later one waits for its date. Each traveler still receives it at most once ({@link ReminderSender}).
     */
    public boolean sendNow(final String tripId, final String reminderId) {
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        final TripReminderPlan plan = planOf(trip);
        final TripReminder reminder = plan.reminder(reminderId);
        final LocalDate date = (reminder == null) ? null : reminder.sendDate(trip);
        if (date == null || !reminder.isApprovedFor(trip)) {
            return refuse("Only an approved message can be sent.");
        }
        if (date.isAfter(today())) {
            return refuse("This message is scheduled for " + DAY.format(date) + " and will be sent then.");
        }
        final ReminderSender.Summary summary =
                senderSource.get().sendAll(trip, plan, reminder, date, today(), true, caller().auditActor());
        if (summary.isRefused()) {
            return refuse(summary.text());
        }
        auditSource.get().reminder(trip.getId(), trip.getOrgId(), "Messages of '" + trip.getTitle()
                + "': sent '" + reminder.getName() + "' by hand. " + summary.text(), caller().auditActor());
        info(summary.text());
        return true;
    }

    // ================================================================== review, preview, test

    /** Who this message would go to if it were sent today, by name: the REAL audience, as the send uses it. */
    List<ReminderRecipients.Target> recipientsOf(final String tripId, final String reminderId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        final TripReminder reminder = (trip == null) ? null : planOf(trip).reminder(reminderId);
        return (reminder == null) ? List.of() : recipients.targets(trip, reminder, today());
    }

    /**
     * Everything the review dialog shows for one message, in ONE pass over the audience; null when the message no
     * longer exists. What is shown is cut to what this caller may see ({@link ReminderGate#sight}): the wording
     * and the names are real, an amount, a passport fact or an address they may not read is a placeholder.
     */
    public ReminderViews.Review review(final String tripId, final String reminderId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        final TripReminderPlan plan = (trip == null) ? null : planOf(trip);
        final TripReminder reminder = (plan == null) ? null : plan.reminder(reminderId);
        if (reminder == null) {
            return null;
        }
        final LocalDate today = today();
        final ReminderRecipients.Sight sight = gate.sight(tripId);
        final List<ReminderRecipients.Target> shown = recipients.targets(trip, reminder, today).stream()
                .map(target -> target.seenBy(sight)).toList();
        final Map<String, String> values = previewValues(shown);
        final List<ReminderViews.Recipient> rows = new ArrayList<>();
        for (final ReminderRecipients.Target target : shown) {
            rows.add(new ReminderViews.Recipient(target.getName(), target.getReason(), target.addressesSeenBy(sight)));
        }
        final String replyTo = ReminderSender.effectiveReplyTo(trip, plan);
        final String from = senderFrom(trip, plan);
        return ReminderViews.Review.builder()
                .row(rowOf(trip, reminder, DAO.getInstance().getReminderSends(tripId), today))
                .subject(ReminderText.renderPlain(reminder.getSubject(), values))
                .body(ReminderText.renderBody(reminder.getBody(), values))
                .push(ReminderText.renderPlain(reminder.getPushText(), values))
                .note(previewNote(shown, sight))
                .from((from == null) ? "" : from)
                .replyTo((replyTo == null || replyTo.isBlank()) ? "the From address" : replyTo)
                .recipients(rows).hash(reminder.approvalHash(reminder.sendDate(trip))).build();
    }

    private static String previewNote(final List<ReminderRecipients.Target> shown,
            final ReminderRecipients.Sight sight) {
        if (shown.isEmpty()) {
            return "Nobody would receive this today, so the preview shows sample values.";
        }
        final boolean all = sight.money() && sight.travelDocuments();
        return "Shown as " + shown.get(0).getName() + " would receive it today (1 of " + shown.size() + ")."
                + (all ? "" : " Details you do not have access to, such as an amount owed or a passport date, are "
                        + "not shown here; the traveler's own message has them.");
    }

    /** The values of the first shown target that has a mailbox; sample values when nobody is in the audience. */
    private static Map<String, String> previewValues(final List<ReminderRecipients.Target> shown) {
        for (final ReminderRecipients.Target target : shown) {
            if (!target.mailTo().isEmpty()) {
                return ReminderRecipients.valuesFor(target, target.mailTo().get(0));
            }
        }
        return shown.isEmpty() ? ReminderText.sampleValues()
                : ReminderRecipients.valuesFor(shown.get(0), shown.get(0).traveler());
    }

    /** The signed-in user's own address: what the test-send field starts with (editable; prompt, never assume). */
    public String myEmail() {
        final Person me = caller().isAuthenticated()
                ? DAO.getInstance().getPerson(caller().personId(), Cached.YES).orElse(null) : null;
        return (me == null || me.getEmail() == null) ? "" : me.getEmail();
    }

    /**
     * Mails the message, exactly as the review shows it to THIS caller, to ONE address they typed. A draft can be
     * tested: the test goes to the facilitator, never to travelers, and writes nothing to the sent history. It
     * carries only what the caller may see (a placeholder for the rest), and each person is held to
     * {@link #MAX_TESTS_PER_HOUR}, so the button is not a way to mail strangers from the organization's address.
     */
    public boolean sendTest(final String tripId, final String reminderId, final String to) {
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        final TripReminderPlan plan = planOf(trip);
        final ReminderViews.Review review = review(tripId, reminderId);
        if (review == null) {
            return refuse("This message no longer exists: reload the page.");
        }
        final String address = EmailAddresses.normalize(to);
        if (address == null) {
            return refuse("Enter the email address to send the test to.");
        }
        if (review.getFrom().isEmpty()) {
            return refuse("Set the From address for this trip's messages first.");
        }
        if (!underTestLimit()) {
            return refuse("That is " + MAX_TESTS_PER_HOUR + " test emails in an hour. Try again later.");
        }
        final boolean sent = senderSource.get().sendTest(review.getFrom(), address,
                ReminderSender.effectiveReplyTo(trip, plan), "[Test] " + review.getSubject(), review.getBody(),
                caller().auditActor());
        if (!sent) {
            return refuse("The test email could not be sent.");
        }
        info("Test email sent to " + address + ".");
        return true;
    }

    /**
     * Counts this send against the caller's hour. When the cache cannot count (it is down, or switched off) the
     * count is kept in this JVM instead, the way the chat rate limiter falls back: the cap must not disappear
     * exactly when the cache does.
     */
    private boolean underTestLimit() {
        final long hour = clock.get().getEpochSecond() / 3600;
        final String key = CacheKeys.reminderTestSendsKey(caller().personId().getValue(), hour);
        final long count = DAO.getInstance().getCacheClient().increment(key, 1, Duration.ofHours(1))
                .orElseGet(() -> countHere(key, hour));
        return count <= MAX_TESTS_PER_HOUR;
    }

    private static long countHere(final String key, final long hour) {
        // Only this hour's counters are kept; one facilitator's hour is one entry.
        LOCAL_TEST_COUNTS.keySet().removeIf(old -> !old.endsWith(":" + hour));
        return LOCAL_TEST_COUNTS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    // ================================================================== sender (From and Reply-To)

    /** The From this trip's messages go out from right now (the trip's own, else the inherited one). */
    public String fromSeed(final String tripId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        final String from = (trip == null) ? null : senderFrom(trip, planOf(trip));
        return (from == null) ? "" : from;
    }

    /** The Reply-To the trip's messages carry; "" when replies simply go to the From. */
    public String replyToSeed(final String tripId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        final String replyTo = (trip == null) ? null : ReminderSender.effectiveReplyTo(trip, planOf(trip));
        return (replyTo == null) ? "" : replyTo;
    }

    /**
     * The trip's OWN Reply-To, for the Sender dialog's field: blank while the trip inherits its facilitators'
     * addresses. Seeding the field with the inherited list made an untouched dialog save a list as if it were
     * one typed address.
     */
    public String ownReplyTo(final String tripId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        final String own = (trip == null) ? null : planOf(trip).getReplyTo();
        return (own == null) ? "" : own;
    }

    private static String senderFrom(final Trip trip, final TripReminderPlan plan) {
        final String from = ReminderSender.effectiveFrom(trip, plan);
        return (from == null || from.isBlank()) ? null : from;
    }

    /**
     * Stores the trip's From (the composer's three fields, checked against the organization's allowed sending
     * domains) and Reply-To. Changing who a message comes from does not change what was approved.
     */
    public boolean saveSender(final String tripId, final String name, final String local, final String domain,
            final String replyTo) {
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        final OrgCommands orgs = new OrgCommands(callerSource);
        final String from = new MailAddressCommands().composeAddress(name, local, domain,
                orgs.mailDomainsForTrip(trip), "The From address");
        if (from == null) {
            return false;
        }
        final String reply = (replyTo == null) ? "" : replyTo.trim();
        if (!reply.isEmpty() && EmailAddresses.normalize(reply) == null) {
            return refuse("The Reply-To is not an email address.");
        }
        final TripReminderPlan plan = planOf(trip);
        plan.setMailFrom(from);
        plan.setReplyTo(reply.isEmpty() ? null : reply);
        // Both halves are named: the Reply-To is outside the approval and is where travelers' answers go.
        if (!persist(trip, plan, PageFeedback::refuse, "set the sender: From '" + from + "', Reply-To '"
                + (reply.isEmpty() ? "(the trip's facilitators)" : reply) + "'")) {
            return false;
        }
        info("Sender saved.");
        return true;
    }

    // ================================================================== sent history

    /** What actually went out, newest first. */
    public List<ReminderViews.HistoryRow> history(final String tripId) {
        if (!canManage(tripId)) {
            return List.of();
        }
        final ZoneId zone = ReminderClock.zone(new ConfigCommands());
        final boolean addresses = gate.sight(tripId).contact();
        final List<ReminderViews.HistoryRow> rows = new ArrayList<>();
        for (final ReminderSend send : DAO.getInstance().getReminderSends(tripId)) {
            final ReminderSend.Outcome outcome = send.getOutcome();
            final String to = (send.getToEmail() == null) ? "" : send.getToEmail();
            rows.add(new ReminderViews.HistoryRow(send.getSortKey(),
                    (send.getAt() == null) ? "" : MOMENT.format(send.getAt().atZone(zone)),
                    send.getReminderName(), send.getPersonName(),
                    (addresses || to.isBlank()) ? to : ReminderRecipients.ADDRESS_HIDDEN, outcome.getLabel(),
                    outcome.name().toLowerCase(Locale.ROOT).replace("_", ""),
                    send.isManual() ? "Sent by hand" : "Scheduled", send.isPushed(),
                    outcome == ReminderSend.Outcome.FAILED || outcome == ReminderSend.Outcome.CLAIMED));
        }
        return rows;
    }

    /**
     * Clears one FAILED (or stuck) history row, so that traveler can be sent the message again by Send now. A
     * sent row can never be cleared: that would let the same person be mailed twice.
     */
    public boolean clearSend(final String tripId, final String key) {
        final Trip trip = manageable(tripId);
        if (trip == null) {
            return false;
        }
        for (final ReminderSend send : DAO.getInstance().getReminderSends(tripId)) {
            if (send.getSortKey().equals(key)) {
                if (send.getOutcome() != ReminderSend.Outcome.FAILED
                        && send.getOutcome() != ReminderSend.Outcome.CLAIMED) {
                    return refuse("Only a failed send can be cleared.");
                }
                DAO.getInstance().deleteReminderSend(send);
                auditSource.get().reminder(trip.getId(), trip.getOrgId(), "Messages of '" + trip.getTitle()
                        + "': cleared the failed send of '" + send.getReminderName() + "' to "
                        + send.getPersonName(), caller().auditActor());
                info("Cleared. \"Send to anyone missed\" on the message will include " + send.getPersonName()
                        + " again.");
                return true;
            }
        }
        return refuse("That send is no longer listed: reload the page.");
    }

    // ================================================================== the mail the trip sends by itself

    /**
     * The transactional email this trip already sends without a schedule, read-only: what triggers each one,
     * and the wording in force for the trip's organization (placeholders shown as written). They stay
     * automatic; each is a reply to something the traveler just did.
     */
    public List<ReminderViews.AutoMail> automaticMails(final String tripId) {
        final Trip trip = canManage(tripId) ? gate.knownTrip(tripId) : null;
        if (trip == null) {
            return List.of();
        }
        final List<ReminderViews.AutoMail> out = new ArrayList<>();
        addAuto(out, trip, StarterTemplates.REGISTRATION_RECEIVED_ID, "Registration received",
                "Right after someone submits a registration for this trip.");
        addAuto(out, trip, StarterTemplates.REGISTRATION_APPROVED_ID, "Registration approved",
                "When a registration is approved with \"send approval email\" ticked.");
        if (trip.getCheckout().isOn()) {
            addAuto(out, trip, StarterTemplates.CHECKOUT_CONFIRMATION_ID, "Checkout confirmation",
                    "Right after someone pays for a registration at checkout.");
        }
        final TripPaymentConfig payments = new OrgCommands(callerSource).effectivePaymentConfig(trip);
        if (payments.isPayable()) {
            addAuto(out, trip, (payments.getConfirmationTemplateId() == null)
                    ? StarterTemplates.PAYMENT_CONFIRMATION_ID : payments.getConfirmationTemplateId(),
                    "Payment confirmation", "Right after an online payment toward this trip is recorded.");
            addAuto(out, trip, StarterTemplates.PAYMENT_FAILED_ID, "Payment failed",
                    "When an online payment toward this trip does not go through.");
        }
        return out;
    }

    private static void addAuto(final List<ReminderViews.AutoMail> out, final Trip trip, final String templateId,
            final String name, final String trigger) {
        final ContentTemplate template = new TemplateCommands().resolveForOrg(templateId, trip.getOrgId());
        if (template == null || template.getKind() != TemplateKind.MAIL) {
            return;
        }
        out.add(new ReminderViews.AutoMail(templateId, name, trigger,
                (template.getOrgId() == null) ? "Standard wording" : "Your organization's wording",
                template.getName(), WaiverHtml.sanitize(template.getBody())));
    }

    // ================================================================== plumbing

    private Trip manageable(final String tripId) {
        final Trip trip = gate.manageableTrip(tripId);
        if (trip == null) {
            refuse(NOT_ALLOWED);
        }
        return trip;
    }

    private static boolean refused(final Consumer<String> refusal, final String problem) {
        refusal.accept(problem);
        return false;
    }

    private static <E extends Enum<E>> E parse(final Class<E> type, final String name) {
        try {
            return (name == null) ? null : Enum.valueOf(type, name);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    /** Saves the plan with one versioned put and audits what changed. */
    private boolean persist(final Trip trip, final TripReminderPlan plan, final Consumer<String> refusal,
            final String what) {
        if (plan.getVersion() == 0L) {
            plan.setTripId(trip.getId());
            plan.setOrgId(trip.getOrgId());
        }
        try {
            if (!DAO.getInstance().saveReminderPlan(plan)) {
                return refused(refusal, "The messages could not be saved.");
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refused(refusal, "Saved by someone else: reload the page and try again.");
        } catch (final IllegalArgumentException ex) {
            return refused(refusal, "The messages are too large to save: shorten or remove one.");
        } catch (final IOException ex) {
            log.error("Unable to save the reminders of trip {}", trip.getId(), ex);
            return refused(refusal, "The messages could not be saved.");
        }
        auditSource.get().reminder(trip.getId(), trip.getOrgId(), "Messages of '" + trip.getTitle() + "': " + what,
                caller().auditActor());
        return true;
    }
}
