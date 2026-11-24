package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ReminderDefaults;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Language;
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

import static org.paulsens.trip.action.MoneyForms.parse;
import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;
import static org.paulsens.trip.action.PageFeedback.refuseInto;
import static org.paulsens.trip.action.PageFeedback.warn;

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
    /**
     * The refusal of a body holding a tag too long to read, in practice a pasted image (see hasUnreadableTag).
     * This constant and the next are the English the unit tests pin; the page reads the bundle's keys
     * ({@code feedback.reminderCommands.tagTooLong} and {@code .formattingRemoved}), whose English is the same.
     */
    static final String TAG_TOO_LONG = "Something pasted into this message (usually an image) is too large to "
            + "send. Remove it, then save.";
    static final String FORMATTING_REMOVED = "Some formatting this message cannot carry (a video, a frame, a style "
            + "other than a color, size, alignment or indent, a link that is not a full web, email or phone address, "
            + "an image that is not a full web address, or a field inside a link or image address) was removed. "
            + "Check the preview.";
    /** Test emails one person may send in one clock hour: plenty for wording work, useless as a mail relay. */
    static final int MAX_TESTS_PER_HOUR = 20;
    /** The raw body is refused above this before it is cleaned: the cleaner's cost grows with its input. */
    static final int MAX_RAW_BODY = 4 * MAX_BODY;
    /** A send date: "Thu, Sep 3, 2026" in English, the reader's own shape elsewhere. */
    private static final String DAY = "yMMMEd";
    /** A send's moment; English keeps its comma-free "Sep 3, 2026 3:05 PM". */
    private static final String MOMENT = "yMMMdjm";
    private static final String MOMENT_ENGLISH = "MMM d, yyyy h:mm a";

    /**
     * The test-send counts kept here when the cache cannot count ({@link #underTestLimit}): one clock hour's
     * counts per person. The hour is held beside them, never read back out of a key, so a change to the cache
     * key's format cannot make the sweep drop the current hour.
     */
    private static final AtomicReference<HourCounts> LOCAL_TEST_COUNTS = new AtomicReference<>(new HourCounts(-1));

    private final Supplier<Caller> callerSource;
    private final Supplier<AuditCommands> auditSource;
    private final Supplier<ReminderSender> senderSource;
    private final Supplier<Instant> clock;
    private final Supplier<ConfigCommands> configSource;
    private final Supplier<CacheClient> cacheSource;
    private final ReminderGate gate;
    private final ReminderRecipients recipients;

    /** One clock hour of in-JVM test-send counts, keyed by person id. */
    private record HourCounts(long hour, Map<String, AtomicLong> counts) {
        HourCounts(final long hour) {
            this(hour, new ConcurrentHashMap<>());
        }
    }

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
        this(callerSource, auditSource, senderSource, recipients, clock, ConfigCommands::new,
                () -> DAO.getInstance().getCacheClient());
    }

    /** Test seam for the settings ({@code reminders.enabled}, the run hour) and the cache that counts test sends. */
    ReminderCommands(final Supplier<Caller> callerSource, final Supplier<AuditCommands> auditSource,
            final Supplier<ReminderSender> senderSource, final ReminderRecipients recipients,
            final Supplier<Instant> clock, final Supplier<ConfigCommands> configSource,
            final Supplier<CacheClient> cacheSource) {
        this.callerSource = callerSource;
        this.auditSource = auditSource;
        this.senderSource = senderSource;
        this.recipients = recipients;
        this.clock = clock;
        this.configSource = configSource;
        this.cacheSource = cacheSource;
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
        return configSource.get().getBoolean(KnownSettings.REMINDERS_ENABLED);
    }

    /** Today where the messages are scheduled: {@link ReminderClock}, the scheduler's own answer. */
    LocalDate today() {
        return ReminderClock.today(configSource.get(), clock.get());
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
            return new ReminderViews.Card(Text.get("feedback.reminderCommands.cardNoMessages"),
                    Text.get("feedback.reminderCommands.cardNothingScheduled"));
        }
        final long approved = reminders.stream().filter(r -> r.isApprovedFor(trip)).count();
        final long waiting = reminders.size() - approved;
        final String subline = Text.get("feedback.reminderCommands.cardApprovedCount", approved, reminders.size());
        if (waiting > 0) {
            return new ReminderViews.Card(Text.get("feedback.reminderCommands.cardNeedApproval", waiting), subline);
        }
        final LocalDate next = nextSend(trip, reminders);
        return new ReminderViews.Card((next == null) ? Text.get("feedback.reminderCommands.cardAllSent")
                : Text.get("feedback.reminderCommands.cardNext", day(next)), subline);
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
                .sendDateText((date == null) ? Text.get("feedback.reminderCommands.noDate") : day(date))
                .audienceLabel(reminder.getAudience().getLabel())
                .channel(reminder.isPush() ? Text.get("feedback.reminderCommands.channelEmailAndPush")
                        : Text.get("common.email"))
                .statusLabel(status.label()).statusClass(status.styleClass()).statusNote(status.note())
                .approved(approved).approvable(date != null && !approved)
                // Offered again after a run: the ledger keeps everyone already sent to one message, so a second
                // click reaches only a traveler whose failed send was cleared, or who joined the audience since.
                .sendable(approved && !date.isAfter(today))
                .sendLabel(ran.isEmpty() ? Text.get("common.sendNow")
                        : Text.get("feedback.reminderCommands.sendToMissed")).build();
    }

    /** What a row says about where its message stands. */
    private record Status(String label, String styleClass, String note) {
    }

    private Status statusOf(final Trip trip, final TripReminder reminder, final LocalDate date,
            final boolean approved, final List<ReminderSend> ran, final LocalDate today) {
        if (date == null) {
            return new Status(Text.get("feedback.reminderCommands.noDate"), "nodate", missingDateNote(reminder));
        }
        if (reminder.isApprovalStale(trip)) {
            return new Status(Text.get("feedback.reminderCommands.statusChanged"), "changed",
                    Text.get("feedback.reminderCommands.noteChanged"));
        }
        if (!approved) {
            return new Status(Text.get("feedback.reminderCommands.statusDraft"), "draft", date.isBefore(today)
                    ? Text.get("feedback.reminderCommands.noteDraftPast")
                    : Text.get("feedback.reminderCommands.noteDraft"));
        }
        if (!ran.isEmpty()) {
            return ranStatus(ran);
        }
        final String by = nameOf(reminder.getApprovedBy());
        if (date.isBefore(today.minusDays(1))) {
            return new Status(Text.get("feedback.reminderCommands.statusMissed"), "missed",
                    Text.get("feedback.reminderCommands.noteMissed", by));
        }
        return new Status(Text.get("feedback.reminderCommands.statusApproved"), "approved", isSendingOn()
                ? Text.get("feedback.reminderCommands.noteApproved", by)
                : Text.get("feedback.reminderCommands.noteApprovedSendingOff", by));
    }

    /** The status of a message whose run happened: how many it reached, and how many sends did not go through. */
    private static Status ranStatus(final List<ReminderSend> ran) {
        final long sent = ran.stream().filter(s -> s.getOutcome() == ReminderSend.Outcome.SENT).count();
        final long failed = ran.stream().filter(s -> s.getOutcome() == ReminderSend.Outcome.FAILED
                || s.getOutcome() == ReminderSend.Outcome.CLAIMED).count();
        final String note = (failed == 0) ? Text.get("feedback.reminderCommands.noteSent", sent)
                : Text.get("feedback.reminderCommands.noteSentSomeFailed", sent, String.valueOf(failed));
        return (sent == 0 && failed > 0)
                ? new Status(Text.get("labels.enum.ReminderSend.Outcome.FAILED"), "failed", note)
                : new Status(Text.get("labels.enum.ReminderSend.Outcome.SENT"), "sent", note);
    }

    private static String missingDateNote(final TripReminder reminder) {
        return switch (reminder.getAnchor()) {
            case BALANCE_DUE_DATE -> Text.get("feedback.reminderCommands.noBalanceDueDate");
            case FIXED_DATE -> Text.get("feedback.reminderCommands.chooseADate");
            case TRIP_END -> Text.get("feedback.reminderCommands.noEndDate");
            default -> Text.get("feedback.reminderCommands.noStartDate");
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
        // Cleaned on the way out like every other place the body is written: the editor loads it as HTML. A stored
        // body the cleaner now changes (a field inside a link address, refused in mail since 2026-11-23) is said, as
        // the save says it, never dropped without a word.
        final RichTextHtml.Cleaned body = RichTextHtml.cleanNoting(reminder.getBody());
        if (body.dropped()) {
            warn(formattingRemoved("feedback.reminderCommands.formattingRemoved"));
        }
        form.setBody(body.body());
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
            return refuse(Text.get("feedback.shared.nothingToSave"));
        }
        final String problem = formProblem(form);
        if (problem != null) {
            return refuseInto(refusal, problem);
        }
        final TripReminderPlan plan = planOf(trip);
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final TripReminder existing = adding ? null : plan.reminder(form.getId());
        if (!adding && existing == null) {
            return refuseInto(refusal, Text.get("feedback.reminderCommands.noLongerExists"));
        }
        if (adding && plan.getReminders().size() >= MAX_REMINDERS) {
            return refuseInto(refusal, Text.get("feedback.reminderCommands.tooManyMessages", MAX_REMINDERS));
        }
        final TripReminder.Anchor anchor = parse(TripReminder.Anchor.class, form.getAnchor());
        final boolean dated = anchor == TripReminder.Anchor.FIXED_DATE;
        final int days = (dated || "ON".equals(form.getTiming())) ? 0 : form.getDays();
        // One clean gives both the stored body and the warning, so the two always judge the same input.
        final RichTextHtml.Cleaned cleaned = cleanNoting(form.getBody());
        final TripReminder reminder = TripReminder.builder()
                .id(adding ? UUID.randomUUID().toString() : existing.getId())
                // The kind is what the message IS (and fixes its audience), so an edit can never change it.
                .kind(adding ? TripReminder.Kind.CUSTOM : existing.getKind())
                .name(form.getName().trim()).anchor(anchor)
                .offsetDays("BEFORE".equals(form.getTiming()) ? -days : days)
                .fixedDate(dated ? form.getFixedDate() : null)
                .audience(parse(TripReminder.Audience.class, form.getAudience()))
                .subject(form.getSubject().trim()).body(cleaned.body())
                .pushText((form.getPushText() == null) ? "" : form.getPushText().trim())
                .status(TripReminder.Status.DRAFT).build();
        if (!reminder.isReachable(trip)) {
            return refuseInto(refusal, tooLate());
        }
        plan.put(reminder);
        if (!persist(trip, plan, refusal, (adding ? "added" : "edited") + " '" + reminder.getName() + "' (draft)")) {
            return false;
        }
        form.setId(reminder.getId());
        form.setProblem(null);
        info(Text.get("feedback.reminderCommands.savedAsDraft"));
        if (cleaned.dropped()) {
            // Still saved: the stored body is safe and is what the review shows. But a pasted video or frame, or a
            // link with no scheme, would vanish without a word, and a dead link is easy to approve unnoticed.
            warn(formattingRemoved("feedback.reminderCommands.formattingRemoved"));
        }
        return true;
    }

    /**
     * A warning that the mail cleaner dropped something, worded by {@code key} around ONE list of what mail cannot
     * carry ({@code feedback.reminderCommands.cannotCarry}), shared by the scheduled messages and the mail merge, so
     * that a new rule in the cleaner is named in every warning at once.
     */
    static String formattingRemoved(final String key) {
        return Text.get(key, Text.get("feedback.reminderCommands.cannotCarry"));
    }

    /**
     * The body as it is stored: Quill may percent-encode a placeholder typed as a link address, so the braces
     * are put back first, and then everything not on {@link RichTextHtml}'s allowlist is removed. A facilitator
     * writes this text and admins read it unescaped, so it is an allowlist, never a blocklist.
     */
    static String cleanBody(final String html) {
        return cleanNoting(html).body();
    }

    /**
     * {@link #cleanBody}, with whether the cleaner dropped something the writer could see. The mail merge cleans its
     * body here too ({@link MailMerge}): one allowlist for everything the site mails.
     */
    static RichTextHtml.Cleaned cleanNoting(final String html) {
        return RichTextHtml.cleanNoting(bracesRestored(html));
    }

    /**
     * Why a mail body as typed is refused before it is cleaned, or null: longer than any message may be (the
     * cleaner's cost grows with its input), a link too long to read, or a paste too large (named by
     * {@code pasteTooLarge}, worded for the page's own button). ONE copy for everything the site mails, a scheduled
     * message and the mail merge, so the two doors cannot drift apart.
     */
    static String rawMailProblem(final String raw, final String pasteTooLarge) {
        if (raw != null && raw.length() > MAX_RAW_BODY) {
            return Text.get("feedback.reminderCommands.messageTooLong");
        }
        if (RichTextHtml.hasUnreadableLink(raw)) {
            return Text.get("feedback.reminderCommands.linkTooLong");
        }
        return RichTextHtml.hasUnreadableTag(raw) ? Text.get(pasteTooLarge) : null;
    }

    /**
     * Why a cleaned mail body is refused, or null: it says and shows nothing (a body that was only a video cleans to
     * an empty paragraph, review 2026-11-23), or it is longer than a message may be once cleaned. Shared by a
     * scheduled message and the mail merge, like {@link #rawMailProblem}.
     */
    static String cleanedMailProblem(final String body) {
        if (WaiverHtml.isBlank(body) && !body.contains("<img")) {
            return Text.get("feedback.reminderCommands.bodyMissing");
        }
        return body.length() > MAX_BODY ? Text.get("feedback.reminderCommands.messageTooLong") : null;
    }

    private static String bracesRestored(final String html) {
        return (html == null) ? "" : html.replace("%7B%7B", "{{").replace("%7D%7D", "}}")
                .replace("%7b%7b", "{{").replace("%7d%7d", "}}");
    }

    private static String formProblem(final ReminderViews.Form form) {
        if (form.getName() == null || form.getName().isBlank()) {
            return Text.get("feedback.reminderCommands.nameMissing");
        }
        if (form.getName().trim().length() > MAX_NAME) {
            return Text.get("feedback.reminderCommands.nameTooLong", MAX_NAME);
        }
        final TripReminder.Anchor anchor = parse(TripReminder.Anchor.class, form.getAnchor());
        if (anchor == null) {
            return Text.get("feedback.reminderCommands.chooseWhen");
        }
        if (anchor == TripReminder.Anchor.FIXED_DATE && form.getFixedDate() == null) {
            return Text.get("feedback.reminderCommands.chooseSendDate");
        }
        if (anchor != TripReminder.Anchor.FIXED_DATE && !List.of("BEFORE", "ON", "AFTER").contains(form.getTiming())) {
            return Text.get("feedback.reminderCommands.chooseTiming");
        }
        if (form.getDays() < 0 || form.getDays() > MAX_DAYS) {
            return Text.get("feedback.reminderCommands.daysOutOfRange", MAX_DAYS);
        }
        if (form.isCustom() && parse(TripReminder.Audience.class, form.getAudience()) == null) {
            return Text.get("feedback.reminderCommands.chooseAudience");
        }
        return wordingProblem(form);
    }

    private static String wordingProblem(final ReminderViews.Form form) {
        if (form.getSubject() == null || form.getSubject().isBlank()) {
            return Text.get("feedback.reminderCommands.subjectMissing");
        }
        if (form.getSubject().trim().length() > MAX_SUBJECT) {
            return Text.get("feedback.reminderCommands.subjectTooLong", MAX_SUBJECT);
        }
        final String rawProblem = rawMailProblem(form.getBody(), "feedback.reminderCommands.tagTooLong");
        if (rawProblem != null) {
            return rawProblem;
        }
        final String body = cleanBody(form.getBody());
        final String bodyProblem = cleanedMailProblem(body);
        if (bodyProblem != null) {
            return bodyProblem;
        }
        if (form.getPushText() != null && form.getPushText().trim().length() > MAX_PUSH) {
            return Text.get("feedback.reminderCommands.pushTooLong", MAX_PUSH);
        }
        final List<String> unknown = new ArrayList<>(ReminderText.unknownTokens(form.getSubject()));
        unknown.addAll(ReminderText.unknownTokens(body));
        unknown.addAll(ReminderText.unknownTokens(form.getPushText()));
        return unknown.isEmpty() ? null : Text.get("feedback.reminderCommands.unknownField", unknown.get(0));
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
            return refuse(Text.get("feedback.reminderCommands.noLongerExists"));
        }
        if (!persist(trip, plan, PageFeedback::refuse, "deleted '" + reminder.getName() + "'")) {
            return false;
        }
        info(Text.get("feedback.reminderCommands.deleted"));
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
                final ReminderDefaults.Text text =
                        startingText(suggestion.templateId(), trip.getOrgId(), trip.getLanguage());
                plan.put(TripReminder.builder().id(UUID.randomUUID().toString()).kind(suggestion.kind())
                        .name(suggestion.name()).anchor(suggestion.anchor()).offsetDays(suggestion.offsetDays())
                        .subject(text.subject()).body(cleanBody(text.body())).status(TripReminder.Status.DRAFT)
                        .build());
                added++;
            }
        }
        if (added == 0) {
            info(Text.get("feedback.reminderCommands.suggestedAlreadyThere"));
            return true;
        }
        if (!persist(trip, plan, PageFeedback::refuse, "added " + added + " suggested message(s) as drafts")) {
            return false;
        }
        info(Text.get("feedback.reminderCommands.suggestedAdded", added));
        return true;
    }

    private static boolean hasSuggestion(final TripReminderPlan plan, final ReminderDefaults.Suggestion suggestion) {
        return plan.getReminders().stream().anyMatch(r -> r.getKind() == suggestion.kind()
                && r.getAnchor() == suggestion.anchor() && r.getOffsetDays() == suggestion.offsetDays());
    }

    /** {@link #startingText(String, String, Language)} for an English trip. */
    static ReminderDefaults.Text startingText(final String templateId, final String orgId) {
        return startingText(templateId, orgId, Language.English);
    }

    /**
     * The org's own copy of a starter text when it has one, else the installed one, else the built-in text, each in
     * the TRIP's language when a version in it is written: a trip's messages are authored in the language the trip
     * is hosted in ({@code docs/i18n.md}), so that is the language its starting drafts are in.
     */
    static ReminderDefaults.Text startingText(final String templateId, final String orgId, final Language language) {
        final ReminderDefaults.Text builtIn = ReminderDefaults.text(templateId, language);
        final ContentTemplate stored = new TemplateCommands().resolveForOrg(templateId, orgId, localeOf(language));
        if (stored == null || stored.getKind() != TemplateKind.MAIL
                || !ReminderText.unknownTokens(stored.getName() + stored.getBody()).isEmpty()
                || RichTextHtml.hasUnreadableTag(stored.getBody())) {
            // A missing template, one reworded with a placeholder this page cannot fill, or one holding a tag too
            // long to read (a pasted image the save would refuse) falls back to the text that is known to render
            // rather than putting a broken draft in front of the facilitator.
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
            return refuse(Text.get("feedback.reminderCommands.noLongerExists"));
        }
        final LocalDate date = reminder.sendDate(trip);
        if (date == null) {
            return refuse(Text.get("feedback.reminderCommands.noSendDateYet"));
        }
        if (!Digests.matches(reminder.approvalHash(date), shownHash)) {
            return refuse(Text.get("feedback.reminderCommands.changedSinceShown"));
        }
        if (!reminder.isReachable(trip)) {
            return refuse(tooLate());
        }
        if (senderFrom(trip, plan) == null) {
            return refuse(Text.get("feedback.reminderCommands.setFromFirst"));
        }
        plan.put(reminder.approved(trip, caller().personId(), clock.get()));
        if (!persist(trip, plan, PageFeedback::refuse,
                "APPROVED '" + reminder.getName() + "' to send on " + date)) {
            return false;
        }
        info(approvedNote(reminder.getName(), date));
        return true;
    }

    /**
     * What really happens next to a message approved now, in words for the confirmation. It never promises a
     * daily run that will not come: not while scheduled sending is off, not for a date already past (the next
     * run's one-day catch-up may or may not still reach it, so the facilitator is pointed at Send now), and not
     * for today once today's run hour has gone by (tomorrow's catch-up sends it, a day late).
     */
    private Going goingOf(final LocalDate date) {
        final LocalDate today = today();
        final boolean on = isSendingOn();
        if (date.isAfter(today)) {
            return on ? Going.LATER : Going.LATER_ONCE_ON;
        }
        if (date.isBefore(today)) {
            return Going.PAST;
        }
        if (!on) {
            return Going.TODAY_SENDING_OFF;
        }
        return ReminderClock.isBeforeTodaysRun(configSource.get(), clock.get())
                ? Going.TODAY_THIS_RUN : Going.TODAY_NEXT_RUN;
    }

    /** What {@link #goingOf} can answer; each is one whole confirmation sentence in the bundle. */
    private enum Going { LATER, LATER_ONCE_ON, PAST, TODAY_SENDING_OFF, TODAY_THIS_RUN, TODAY_NEXT_RUN }

    /** The confirmation of an approval: one complete sentence per case, so a translator can reorder it. */
    String approvedNote(final String name, final LocalDate date) {
        final String day = day(date);
        return switch (goingOf(date)) {
            case LATER -> Text.get("feedback.reminderCommands.approvedLater", name, day);
            case LATER_ONCE_ON -> Text.get("feedback.reminderCommands.approvedLaterOnceOn", name, day);
            case PAST -> Text.get("feedback.reminderCommands.approvedPast", name, day);
            case TODAY_SENDING_OFF -> Text.get("feedback.reminderCommands.approvedTodaySendingOff", name);
            case TODAY_THIS_RUN -> Text.get("feedback.reminderCommands.approvedTodayThisRun", name);
            case TODAY_NEXT_RUN -> Text.get("feedback.reminderCommands.approvedTodayNextRun", name);
        };
    }

    /** A send date for the person reading the page. */
    private static String day(final LocalDate date) {
        return Formats.format(date, DAY, RequestContext.currentLocale());
    }

    private static String tooLate() {
        return Text.get("feedback.reminderCommands.tooLate", TripReminder.MAX_DAYS_AFTER_END);
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
            return refuse(Text.get("feedback.reminderCommands.noLongerExists"));
        }
        plan.put(reminder.asDraft());
        if (!persist(trip, plan, PageFeedback::refuse, "withdrew the approval of '" + reminder.getName() + "'")) {
            return false;
        }
        info(Text.get("feedback.reminderCommands.approvalWithdrawn", reminder.getName()));
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
            return refuse(Text.get("feedback.reminderCommands.onlyApprovedSent"));
        }
        if (date.isAfter(today())) {
            return refuse(Text.get("feedback.reminderCommands.scheduledForLater", day(date)));
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
                .replyTo((replyTo == null || replyTo.isBlank()) ? Text.get("feedback.reminderCommands.replyToIsFrom")
                        : replyTo)
                .recipients(rows).hash(reminder.approvalHash(reminder.sendDate(trip))).build();
    }

    private static String previewNote(final List<ReminderRecipients.Target> shown,
            final ReminderRecipients.Sight sight) {
        if (shown.isEmpty()) {
            return Text.get("feedback.reminderCommands.previewNobody");
        }
        final boolean all = sight.money() && sight.travelDocuments();
        final String name = shown.get(0).getName();
        final String count = String.valueOf(shown.size());
        return all ? Text.get("feedback.reminderCommands.previewShownAs", name, count)
                : Text.get("feedback.reminderCommands.previewShownAsPartial", name, count);
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
            return refuse(Text.get("feedback.reminderCommands.noLongerExists"));
        }
        final String address = EmailAddresses.normalize(to);
        if (address == null) {
            return refuse(Text.get("feedback.orgCommands.enterTestAddress"));
        }
        if (review.getFrom().isEmpty()) {
            return refuse(Text.get("feedback.reminderCommands.setFromFirst"));
        }
        if (!underTestLimit()) {
            return refuse(Text.get("feedback.reminderCommands.testLimit", MAX_TESTS_PER_HOUR));
        }
        final boolean sent = senderSource.get().sendTest(review.getFrom(), address,
                // The test's reader is the facilitator who asked for it, so the marker is in their language.
                ReminderSender.effectiveReplyTo(trip, plan), Text.get("mail.reminder.testSubject", review.getSubject()),
                review.getBody(),
                caller().auditActor());
        if (!sent) {
            return refuse(Text.get("feedback.reminderCommands.testNotSent"));
        }
        info(Text.get("feedback.reminderCommands.testSent", address));
        return true;
    }

    /**
     * Counts this send against the caller's hour. When the cache cannot count (it is down, or switched off) the
     * count is kept in this JVM instead, the way the chat rate limiter falls back: the cap must not disappear
     * exactly when the cache does.
     */
    private boolean underTestLimit() {
        final long hour = clock.get().getEpochSecond() / 3600;
        final String personId = caller().personId().getValue();
        final String key = CacheKeys.reminderTestSendsKey(personId, hour);
        final long count = cacheSource.get().increment(key, 1, Duration.ofHours(1))
                .orElseGet(() -> countHere(personId, hour));
        return count <= MAX_TESTS_PER_HOUR;
    }

    /** Counts in this JVM; a new hour replaces the old hour's counts whole. */
    private static long countHere(final String personId, final long hour) {
        final HourCounts current = LOCAL_TEST_COUNTS.updateAndGet(held -> sameHourOrNew(held, hour));
        return current.counts().computeIfAbsent(personId, k -> new AtomicLong()).incrementAndGet();
    }

    private static HourCounts sameHourOrNew(final HourCounts held, final long hour) {
        return (held.hour() == hour) ? held : new HourCounts(hour);
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
        final String from = new MailAddressCommands().composeFromAddress(name, local, domain,
                orgs.mailDomainsForTrip(trip));
        if (from == null) {
            return false;
        }
        final String reply = (replyTo == null) ? "" : replyTo.trim();
        if (!reply.isEmpty() && EmailAddresses.normalize(reply) == null) {
            return refuse(Text.get("feedback.reminderCommands.replyToNotAnAddress"));
        }
        final TripReminderPlan plan = planOf(trip);
        plan.setMailFrom(from);
        plan.setReplyTo(reply.isEmpty() ? null : reply);
        // Both halves are named: the Reply-To is outside the approval and is where travelers' answers go.
        if (!persist(trip, plan, PageFeedback::refuse, "set the sender: From '" + from + "', Reply-To '"
                + (reply.isEmpty() ? "(the trip's facilitators)" : reply) + "'")) {
            return false;
        }
        info(Text.get("feedback.reminderCommands.senderSaved"));
        return true;
    }

    // ================================================================== sent history

    /** What actually went out, newest first. */
    public List<ReminderViews.HistoryRow> history(final String tripId) {
        if (!canManage(tripId)) {
            return List.of();
        }
        final ZoneId zone = ReminderClock.zone(configSource.get());
        final boolean addresses = gate.sight(tripId).contact();
        final List<ReminderViews.HistoryRow> rows = new ArrayList<>();
        for (final ReminderSend send : DAO.getInstance().getReminderSends(tripId)) {
            final ReminderSend.Outcome outcome = send.getOutcome();
            final String to = (send.getToEmail() == null) ? "" : send.getToEmail();
            rows.add(new ReminderViews.HistoryRow(send.getSortKey(),
                    Formats.format(send.getAt() == null ? null : send.getAt().atZone(zone), MOMENT, MOMENT_ENGLISH,
                            RequestContext.currentLocale()),
                    send.getReminderName(), send.getPersonName(),
                    (addresses || to.isBlank()) ? to : ReminderRecipients.ADDRESS_HIDDEN, outcome.getLabel(),
                    outcome.name().toLowerCase(Locale.ROOT).replace("_", ""),
                    send.isManual() ? Text.get("feedback.reminderCommands.historyByHand")
                            : Text.get("feedback.reminderCommands.historyScheduled"), send.isPushed(),
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
                    return refuse(Text.get("feedback.reminderCommands.onlyFailedCleared"));
                }
                DAO.getInstance().deleteReminderSend(send);
                auditSource.get().reminder(trip.getId(), trip.getOrgId(), "Messages of '" + trip.getTitle()
                        + "': cleared the failed send of '" + send.getReminderName() + "' to "
                        + send.getPersonName(), caller().auditActor());
                info(Text.get("feedback.reminderCommands.sendCleared", send.getPersonName()));
                return true;
            }
        }
        return refuse(Text.get("feedback.reminderCommands.sendNoLongerListed"));
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
        addAuto(out, trip, StarterTemplates.REGISTRATION_RECEIVED_ID,
                Text.get("feedback.reminderCommands.autoRegistrationReceived"),
                Text.get("feedback.reminderCommands.autoRegistrationReceivedWhen"));
        addAuto(out, trip, StarterTemplates.REGISTRATION_APPROVED_ID,
                Text.get("push.reg.title"),
                Text.get("feedback.reminderCommands.autoRegistrationApprovedWhen"));
        if (trip.getCheckout().isOn()) {
            addAuto(out, trip, StarterTemplates.CHECKOUT_CONFIRMATION_ID,
                    Text.get("feedback.reminderCommands.autoCheckout"),
                    Text.get("feedback.reminderCommands.autoCheckoutWhen"));
        }
        final TripPaymentConfig payments = new OrgCommands(callerSource).effectivePaymentConfig(trip);
        if (payments.isPayable()) {
            addAuto(out, trip, (payments.getConfirmationTemplateId() == null)
                    ? StarterTemplates.PAYMENT_CONFIRMATION_ID : payments.getConfirmationTemplateId(),
                    Text.get("feedback.reminderCommands.autoPaymentConfirmation"),
                    Text.get("feedback.reminderCommands.autoPaymentConfirmationWhen"));
            addAuto(out, trip, StarterTemplates.PAYMENT_FAILED_ID,
                    Text.get("feedback.reminderCommands.autoPaymentFailed"),
                    Text.get("feedback.reminderCommands.autoPaymentFailedWhen"));
        }
        return out;
    }

    private static void addAuto(final List<ReminderViews.AutoMail> out, final Trip trip, final String templateId,
            final String name, final String trigger) {
        // Shown in the trip's language: the one its own messages are written in.
        final ContentTemplate template =
                new TemplateCommands().resolveForOrg(templateId, trip.getOrgId(), localeOf(trip.getLanguage()));
        if (template == null || template.getKind() != TemplateKind.MAIL) {
            return;
        }
        out.add(new ReminderViews.AutoMail(templateId, name, trigger,
                (template.getOrgId() == null) ? Text.get("feedback.reminderCommands.wordingStandard")
                        : Text.get("feedback.reminderCommands.wordingOrganization"),
                template.getName(), RichTextHtml.cleanTemplatePreview(bracesRestored(template.getBody()))));
    }

    // ================================================================== plumbing

    private static Locale localeOf(final Language language) {
        return (language == null) ? null : language.getLocale();
    }

    private Trip manageable(final String tripId) {
        final Trip trip = gate.manageableTrip(tripId);
        if (trip == null) {
            refuse(Text.get("feedback.reminderCommands.notAllowed"));
        }
        return trip;
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
                return refuseInto(refusal, Text.get("feedback.reminderCommands.notSaved"));
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refuseInto(refusal, Text.get("feedback.shared.savedBySomeoneElse"));
        } catch (final IllegalArgumentException ex) {
            return refuseInto(refusal, Text.get("feedback.reminderCommands.tooLargeToSave"));
        } catch (final IOException ex) {
            log.error("Unable to save the reminders of trip {}", trip.getId(), ex);
            return refuseInto(refusal, Text.get("feedback.reminderCommands.notSaved"));
        }
        auditSource.get().reminder(trip.getId(), trip.getOrgId(), "Messages of '" + trip.getTitle() + "': " + what,
                caller().auditActor());
        return true;
    }
}
