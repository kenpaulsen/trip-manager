package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

/**
 * The mail merge ({@code admin/mailMerge.xhtml}, {@code POST /api/mail/preview}): one message, personalized for
 * each recipient with {@code {{field}}} substitution ({@link MergeText}), checked and sent here.
 *
 * <p>Until 2026-11 the merge evaluated its subject and body as EL with the recipient bound as {@code #{to}}, so an
 * organization's {@code emailAdmin} could run code on the shared server (medjugorje#79). Nothing here evaluates
 * anything: {@link #problem} refuses text that still holds an EL marker, an unknown field, a trip field with no
 * mailable trip, or a field its sender may not use, and the values come from Java ({@link MergeValues}).
 *
 * <p>Who may be mailed is {@link OrgCommands}' rule (its {@code emailAdmin} bounds), asked here, never restated.
 */
@Named("mailMerge")
@ApplicationScoped
public class MailMerge {

    /** What a refused From is told, on the page and over the API alike, in the language of the request. */
    public static String invalidMergeFrom() {
        return Text.get("feedback.mailMerge.fromNotVerified");
    }

    /** What a merge is told when its text still holds the old EL personalization. */
    public static final String EXPRESSION = "This message contains #{...} or ${...}, which is no longer filled in. "
            + "Use the fields listed on the Send Mail page instead, such as {{firstName}}.";

    /** One recipient's personalized subject (plain text) and body (HTML), as the Preview shows them. */
    public record Preview(String subject, String body) {
    }

    private final Supplier<Caller> callerSource;
    private final Supplier<MailCommands> mailSource;

    public MailMerge() {
        this(Caller::current);
    }

    /** Test seam (the {@link OrgCommands} pattern): {@code Caller.current()} needs a FacesContext. */
    public MailMerge(final Supplier<Caller> callerSource) {
        this(callerSource, () -> org.paulsens.trip.api.Beans.get(MailCommands.class));
    }

    /** Test seam: mail too ({@code Beans.get} needs a CDI container unit tests do not have). */
    MailMerge(final Supplier<Caller> callerSource, final Supplier<MailCommands> mailSource) {
        this.callerSource = callerSource;
        this.mailSource = mailSource;
    }

    /**
     * The fields this sender may use about the chosen trip, grouped, for the list below the page's editor: a
     * field {@link #problem} would refuse is not offered at all. Trip fields are listed before a trip is chosen
     * (their group says they need one); address, phone and passport status appear once the sender is a site
     * admin or the chosen trip's manager, and the balance for whoever may see that trip's money.
     */
    public List<MergeText.Group> fieldGroupsFor(final String tripId) {
        final List<MergeText.Group> groups = new ArrayList<>();
        for (final MergeText.Group group : MergeText.groups()) {
            if (mayUse(group.kind(), tripId)) {
                groups.add(group);
            }
        }
        return groups;
    }

    /**
     * The Trip row after the author filters the To search by a trip: that trip only while the row is still empty.
     * A trip the author chose wins, so browsing another trip's roster to add one person never silently changes
     * which trip every {@code {{tripTitle}}} and {@code {{balanceDue}}} describes.
     */
    public String tripAfterRosterPick(final String chosen, final String picked) {
        return (chosen == null || chosen.isBlank()) ? picked : chosen;
    }

    /**
     * Whether this sender may use a field of this kind about the chosen trip: the ONE rule behind both what the
     * page lists and what {@link #problem} refuses. A trip field also needs a trip ({@link #problem} says so);
     * that is a step the author has not taken yet, not something they may not do.
     */
    private boolean mayUse(final MergeText.Kind kind, final String tripId) {
        // No default: a new Kind must be given its rule here before it compiles, never inherit "anyone".
        return switch (kind) {
            case PERSON, TRIP, ON_TRIP -> true;
            case CONTACT, TRAVEL_DOCUMENTS -> isTripAdmin(tripId);
            case MONEY -> callerSource.get().isSiteAdmin() || TripFinanceGate.forCaller(callerSource).canView(tripId);
        };
    }

    /**
     * The message that refuses a field of this kind by naming who may use it; the words for {@link #mayUse}'s
     * rule. A key, not the sentence: the refusals are grouped by it, and the sentence takes the fields.
     */
    private static String whoMay(final MergeText.Kind kind) {
        return switch (kind) {
            case PERSON, TRIP, ON_TRIP -> "feedback.mailMerge.onlySender";
            case CONTACT, TRAVEL_DOCUMENTS -> "feedback.mailMerge.onlyTripAdmin";
            case MONEY -> "feedback.mailMerge.onlyFinanceViewer";
        };
    }

    /**
     * Why this merge text cannot be sent or previewed, or null when it can: the rules {@link #checked} applies at
     * every door (the page's preview and send, the REST preview), answered alone for the tests. No EL marker (never
     * evaluated now, and every recipient would get it raw), no unknown field, a trip the sender may mail for every
     * field about a trip, and each gated field allowed ({@link MergeText.Kind}):
     * address, phone and passport status for a site admin or the chosen trip's {@code tripMgr} (the reminders'
     * trip-admin rule), the balance for whoever may see the trip's money ({@link TripFinanceGate}). Refused,
     * never blanked: a sender can BCC themselves, so a field they may not use would read every recipient's value.
     */
    String problem(final String subject, final String raw, final String tripId) {
        return checked(subject, raw, tripId).problem();
    }

    /**
     * A merge body through the one mail check ({@link ReminderCommands#mailable}), its paste refusal worded for this
     * page. The page's merge and the REST send ({@code MailResource}) both mail exactly this.
     */
    public static ReminderCommands.Mailable mailable(final String raw) {
        return ReminderCommands.mailable(raw, "feedback.mailMerge.pasteTooLarge");
    }

    /**
     * The text checked, and cleaned ONCE: every door fills exactly the body that was checked. A body too long, or
     * holding what the mail cleaner could only write out as text, is refused before it is cleaned, as a scheduled
     * message's save refuses it (the cleaner's cost grows with its input). Every field check then reads the body that
     * is FILLED, never the text as typed: cleaning can make a field (it restores encoded braces, and drops a comment or
     * an unlisted tag between two braces), and a field the check never saw would be filled for every recipient, gated
     * or not (review blocker, 2026-11-23).
     */
    private ReminderCommands.Mailable checked(final String subject, final String raw, final String tripId) {
        final ReminderCommands.Mailable mailable = mailable(raw);
        if (mailable.problem() != null) {
            return mailable;
        }
        final RichTextHtml.Cleaned mailed = mailable.cleaned();
        return new ReminderCommands.Mailable(fieldProblem(subject, mailed.body(), tripId), mailed);
    }

    /** Why the fields of this (cleaned) body cannot be filled for this sender, or null. */
    private String fieldProblem(final String subject, final String body, final String tripId) {
        if (MergeText.hasExpression(subject, body)) {
            return Text.get("feedback.mailMerge.expression");
        }
        final List<String> unknown = MergeText.unknownFields(subject, body);
        if (!unknown.isEmpty()) {
            return Text.get("feedback.mailMerge.unknownFields", unknown.size(),
                    String.join(", ", unknown.stream().map(MailMerge::braced).toList()));
        }
        final Set<MergeText.Kind> kinds = MergeText.kindsUsed(subject, body);
        final Set<MergeText.Kind> needTrip = EnumSet.noneOf(MergeText.Kind.class);
        for (final MergeText.Kind kind : kinds) {
            if (kind.isNeedsTrip()) {
                needTrip.add(kind);
            }
        }
        if (!needTrip.isEmpty() && !org().canMailTrip(tripId)) {
            return Text.get("feedback.mailMerge.chooseTripFor",
                    String.join(", ", MergeText.markupOf(needTrip, subject, body)));
        }
        // The kinds this sender may not use, grouped by who may, so each refusal names its rule once.
        final Map<String, Set<MergeText.Kind>> refused = new LinkedHashMap<>();
        for (final MergeText.Kind kind : kinds) {
            if (!mayUse(kind, tripId)) {
                refused.computeIfAbsent(whoMay(kind), who -> EnumSet.noneOf(MergeText.Kind.class)).add(kind);
            }
        }
        final List<String> refusals = new ArrayList<>();
        refused.forEach((who, gated) -> refusals.add(refusal(who, gated, subject, body)));
        return refusals.isEmpty() ? null : String.join(" ", refusals);
    }

    private static String refusal(final String who, final Set<MergeText.Kind> kinds, final String subject,
            final String body) {
        return Text.get(who, String.join(", ", MergeText.markupOf(kinds, subject, body)));
    }

    /** A personalized merge, or why the text is refused ({@link #problem}'s answer) with no preview. */
    public record Rendered(String problem, Preview preview) {
    }

    /**
     * One person's merge, personalized: what the REST preview answers once it has checked who the person is. The
     * text is checked and cleaned once; when it is refused there is no preview, so no caller can render a field its
     * sender may not use.
     */
    public Rendered render(final Person person, final String subject, final String raw, final String tripId) {
        final ReminderCommands.Mailable checked = checked(subject, raw, tripId);
        if (checked.problem() != null) {
            return new Rendered(checked.problem(), null);
        }
        final String body = checked.cleaned().body();
        return new Rendered(null, render(values(tripId, subject, body), person, subject, body));
    }

    /**
     * The body as it is mailed: through the one allowlist for everything the site mails (a scheduled message's,
     * {@link RichTextHtml}), so plain colors and images by web address go and a video, a frame, a script or a handler
     * never does. What it drops is said, on the preview before the send ({@code feedback.mailMerge.formattingRemoved})
     * and again on the send ({@code .formattingRemovedSent}), never silently (the owner's rule, 2026-11-23). Until then
     * the body was mailed as typed.
     */
    private static String mailed(final RichTextHtml.Cleaned cleaned, final String droppedKey) {
        if (cleaned.dropped()) {
            PageFeedback.warn(ReminderCommands.formattingRemoved(droppedKey));
        }
        return cleaned.body();
    }

    /**
     * The page's Preview: the message as the FIRST recipient will get it, or as the sender would when nobody has
     * been added yet. Null, after a growl, when the text is refused or that recipient has no usable address or is
     * outside the sender's organization(s). Warns how many recipients are not on the chosen trip, as the send does.
     */
    public Preview preview(final List<String> toEmails, final String subject, final String raw,
            final String tripId) {
        final OrgCommands org = org();
        // The permission first, as the send asks it: nothing is cleaned or checked for a caller who may not mail.
        if (!org.canMail()) {
            PageFeedback.refuse(Text.get("feedback.mailMerge.cannotPreview"),
                    Text.get("feedback.mailMerge.needsEmailAdmin"));
            return null;
        }
        final ReminderCommands.Mailable checked = checked(subject, raw, tripId);
        if (checked.problem() != null) {
            PageFeedback.refuse(Text.get("feedback.mailMerge.cannotPreview"), checked.problem());
            return null;
        }
        final List<String> toList = (toEmails == null) ? List.of() : toEmails;
        final Set<String> allowed = org.allowedRecipientEmails();
        // The send's two reasons, in the same words: a typo is never reported as an outsider.
        if (!toList.isEmpty() && OrgCommands.singleAddress(toList.get(0)) == null) {
            PageFeedback.refuse(Text.get("feedback.mailMerge.cannotPreview"),
                    Text.get("feedback.mailMerge.firstHasNoAddress"));
            return null;
        }
        if (!toList.isEmpty() && allowed != null && !OrgCommands.isAllowedEmail(allowed, toList.get(0))) {
            PageFeedback.refuse(Text.get("feedback.mailMerge.cannotPreview"),
                    Text.get("feedback.mailMerge.firstIsOutside"));
            return null;
        }
        final MailCommands mail = mailSource.get();
        final Person person = toList.isEmpty()
                ? DAO.getInstance().getPerson(callerSource.get().personId(), Cached.YES).orElse(null)
                : mail.findPersonByEmail(toList.get(0));
        if (person == null) {
            PageFeedback.refuse(Text.get("feedback.mailMerge.cannotPreview"),
                    Text.get("feedback.mailMerge.nobodyToPreview"));
            return null;
        }
        final String body = mailed(checked.cleaned(), "feedback.mailMerge.formattingRemoved");
        final MergeValues values = values(tripId, subject, body);
        if (!toList.isEmpty()) {
            warnOffTrip(values, mail.emailsToPeople(toList), subject, body);
        }
        return render(values, person, subject, body);
    }

    /**
     * The mail-merge send, org-bounded server-side: recipients (and bcc entries) outside the caller's allowed set
     * are DROPPED with a growl naming the count, and the send proceeds for the rest. This is the enforcement point
     * behind the Send button; the page never composes the filter itself.
     *
     * @param tripId the trip the text's trip fields are about; null or blank when it uses none
     */
    public boolean send(final String from, final List<String> toEmails, final String bcc, final String replyTo,
            final String subject, final String raw, final String tripId) {
        final OrgCommands org = org();
        if (!org.canMail()) {
            return PageFeedback.refuse(Text.get("feedback.shared.notAllowed"),
                    Text.get("feedback.mailMerge.needsEmailAdmin"));
        }
        final ReminderCommands.Mailable checked = checked(subject, raw, tripId);
        if (checked.problem() != null) {
            return PageFeedback.refuse(Text.get("feedback.shared.notSent"), checked.problem());
        }
        final Set<String> allowed = org.allowedRecipientEmails();
        final List<String> toList = (toEmails == null) ? List.of() : toEmails;
        final List<String> accepted = OrgCommands.boundRecipients(allowed, toList);
        // Two reasons, two counts: a site admin has no organization bound, so a typo must not read as an outsider.
        final int unusable = (int) toList.stream().filter(entry -> OrgCommands.singleAddress(entry) == null).count();
        final int outside = toList.size() - accepted.size() - unusable;
        if (unusable > 0) {
            PageFeedback.warn(Text.get("feedback.mailMerge.unusableSkipped", unusable, String.valueOf(unusable)));
        }
        if (outside > 0) {
            PageFeedback.warn(Text.get("feedback.mailMerge.outsideSkipped", outside, String.valueOf(outside)));
        }
        if (accepted.isEmpty()) {
            return PageFeedback.refuse(Text.get("feedback.mailMerge.nobodyToMail"), (outside > 0)
                    ? Text.get("feedback.mailMerge.noneWithinOrg") : Text.get("feedback.mailMerge.noneUsable"));
        }
        // The composer can only produce an allowed domain, so this is the forged-post guard: a From on a
        // domain SES has not verified for this tenant is refused HERE rather than silently at SES.
        if (!org.isMergeFromSendable(from)) {
            return PageFeedback.refuse(Text.get("feedback.mailMerge.invalidFrom"), invalidMergeFrom());
        }
        final MailCommands mail = mailSource.get();
        final List<Person> people = mail.emailsToPeople(accepted);
        // Past tense, with nothing left to check: the mail has gone by the time this is read.
        final String body = mailed(checked.cleaned(), "feedback.mailMerge.formattingRemovedSent");
        final MergeValues values = values(tripId, subject, body);
        warnOffTrip(values, people, subject, body);
        mail.sendMerged(from, OrgCommands.boundBcc(bcc, allowed), replyTo,
                people.stream().map(person -> merged(values, person, subject, body)).toList());
        return true;
    }

    private OrgCommands org() {
        return new OrgCommands(callerSource, mailSource);
    }

    private static String braced(final String name) {
        return "{{" + name + "}}";
    }

    /** A site admin, or a {@code tripMgr} of the chosen trip: the trip-admin rule the reminders apply. */
    private boolean isTripAdmin(final String tripId) {
        final Caller caller = callerSource.get();
        return caller.isSiteAdmin()
                || (tripId != null && !tripId.isBlank() && caller.has(PrivilegeCommands.TRIP_MGR, tripId));
    }

    private static Preview render(final MergeValues values, final Person person, final String subject,
            final String body) {
        final Map<String, String> fields = values.valuesFor(person);
        return new Preview(MergeText.renderPlain(subject, fields), MergeText.render(body, fields));
    }

    private static MailCommands.Merged merged(final MergeValues values, final Person person, final String subject,
            final String body) {
        final Preview rendered = render(values, person, subject, body);
        return new MailCommands.Merged(person, rendered.subject(), rendered.body());
    }

    /**
     * The values for one merge. The trip is resolved only when the sender may mail it: {@link #problem} already
     * refused every trip field without that, and an unmailable trip must not decide whose address fields fill.
     */
    private MergeValues values(final String tripId, final String subject, final String body) {
        final Trip trip = org().canMailTrip(tripId) ? DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null)
                : null;
        // One RegistrationCommands for the whole merge, not one per recipient.
        final RegistrationCommands registrations = new RegistrationCommands(callerSource);
        return new MergeValues(trip, callerSource.get().isSiteAdmin(), MergeText.fieldsUsed(subject, body),
                ReminderClock.today(new ConfigCommands(), Instant.now()), new ReminderRecipients(),
                registrations::roomLabel);
    }

    /** Warns, when the text uses fields about a person ON the trip, how many recipients are not on it. */
    private void warnOffTrip(final MergeValues values, final List<Person> people, final String subject,
            final String body) {
        final boolean blanks = values.blanksOffTrip(MergeText.kindsUsed(subject, body));
        final long off = blanks ? people.stream().filter(person -> !values.onTrip(person)).count() : 0;
        if (off > 0) {
            PageFeedback.warn(Text.get("feedback.mailMerge.offTrip", off, String.valueOf(off)));
        }
    }
}
