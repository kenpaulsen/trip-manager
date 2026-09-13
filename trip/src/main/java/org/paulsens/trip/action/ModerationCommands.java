package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.UploadRateLimiter;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.util.Util;

/**
 * "Report this": a member flags a chat message, a chat photo or a photo comment as objectionable
 * (App Store guideline 1.2, "a mechanism to report offensive content and timely responses").
 *
 * <p>A report does two things and nothing else: it is written to the audit trail as a {@link AuditAction#REPORT}
 * record (append-only, so a report cannot be quietly discarded), and it is emailed to the people who can act
 * on it -- the {@code moderation.report.email} slot (facilitators by default, falling back to the
 * organization's contact and then the Site email) with a blind copy to the platform address. Acting on it
 * (delete, mute, remove) is what the existing moderation tools are for; nothing is auto-hidden on a report,
 * because one malicious report must not be able to silence a message.
 *
 * <p>Reports are throttled per person (10 an hour): enough for anyone honest, and it stops a script from
 * using the report mail as a spam cannon aimed at a trip's facilitators.
 */
@Slf4j
@Named("moderation")
@ApplicationScoped
public class ModerationCommands {

    /** What is being reported. A COMMENT is a message in a photo's thread; a PHOTO is the image itself. */
    public enum TargetKind { MESSAGE, PHOTO, COMMENT }

    /** One report target. {@code tripId} is required for MESSAGE; {@code photoKey} for PHOTO and COMMENT. */
    public record ReportTarget(TargetKind kind, String tripId, ChatMessage.Id messageId, String photoKey) {

        public static ReportTarget message(final String tripId, final ChatMessage.Id messageId) {
            return new ReportTarget(TargetKind.MESSAGE, tripId, messageId, null);
        }

        public static ReportTarget photo(final String photoKey) {
            return new ReportTarget(TargetKind.PHOTO, PhotoChatCommands.tripIdOfKey(photoKey), null, photoKey);
        }

        public static ReportTarget comment(final String photoKey, final ChatMessage.Id messageId) {
            return new ReportTarget(TargetKind.COMMENT, PhotoChatCommands.tripIdOfKey(photoKey), messageId,
                    photoKey);
        }
    }

    /** Outcome; on refusal {@code code} is one of the {@code REFUSED_*} constants. */
    public record ReportOutcome(boolean ok, String code, String message, int retryAfterSeconds) {
        static ReportOutcome accepted() {
            return new ReportOutcome(true, null, null, 0);
        }

        static ReportOutcome refused(final String code, final String message) {
            return new ReportOutcome(false, code, message, 0);
        }
    }

    public static final String REFUSED_NOT_FOUND = "not_found";
    public static final String REFUSED_FORBIDDEN = "forbidden";
    public static final String REFUSED_RATE_LIMITED = "rate_limited";
    public static final String REFUSED_BAD_REQUEST = "bad_request";

    public static final int REPORTS_PER_HOUR = 10;
    public static final int MAX_REASON_CHARS = 1000;
    /** How long the Terms promise a response within; also what the confirmation to the reporter says. */
    public static final String RESPONSE_PROMISE = "within 24 hours";

    private static final int SNIPPET_CHARS = 300;
    private static final UploadRateLimiter REPORTS = new UploadRateLimiter(REPORTS_PER_HOUR, 3600);

    private final ConfigCommands config;
    private final Supplier<MailCommands> mailSource;
    private final Supplier<MailAddressCommands> addressSource;
    private final Supplier<ChatCommands> chatSource;
    private final Supplier<PhotoChatCommands> photoChatSource;
    private final UploadRateLimiter limiter;

    public ModerationCommands() {
        this(new ConfigCommands(), () -> org.paulsens.trip.api.Beans.get(MailCommands.class),
                MailAddressCommands::new, ChatCommands::getChatCommands, PhotoChatCommands::getPhotoChatCommands,
                REPORTS);
    }

    /** Test seam: every collaborator handed in, no container needed. */
    public ModerationCommands(final ConfigCommands config, final Supplier<MailCommands> mailSource,
            final Supplier<MailAddressCommands> addressSource, final Supplier<ChatCommands> chatSource,
            final Supplier<PhotoChatCommands> photoChatSource, final UploadRateLimiter limiter) {
        this.config = config;
        this.mailSource = mailSource;
        this.addressSource = addressSource;
        this.chatSource = chatSource;
        this.photoChatSource = photoChatSource;
        this.limiter = limiter;
    }

    /**
     * Files a report from {@code caller} about {@code target}. The reporter must be able to SEE the target
     * (a chat member for a message; anyone the photo is readable by for a photo or comment) -- a report from
     * someone outside the trip is refused as not found, the same answer the content itself would give them.
     */
    public ReportOutcome report(final ReportTarget target, final String reason, final Caller caller) {
        if (caller == null || !caller.isAuthenticated() || caller.personId() == null) {
            return ReportOutcome.refused(REFUSED_FORBIDDEN, "Sign in required.");
        }
        if (target == null || target.kind() == null) {
            return ReportOutcome.refused(REFUSED_BAD_REQUEST, "Nothing to report.");
        }
        final Person.Id me = caller.personId();
        final Optional<ChatMessage> subject = locate(target, caller);
        if (subject == null) {
            return ReportOutcome.refused(REFUSED_NOT_FOUND, "That content is not available.");
        }
        if (!limiter.allow(me.getValue())) {
            final int retry = limiter.retryAfterSeconds(me.getValue());
            return new ReportOutcome(false, REFUSED_RATE_LIMITED,
                    "You have sent several reports recently. Please wait before sending another.", retry);
        }
        final String why = reason == null || reason.isBlank() ? "(no reason given)"
                : Util.orDefault(reason.strip(), "").length() > MAX_REASON_CHARS
                        ? reason.strip().substring(0, MAX_REASON_CHARS) : reason.strip();
        final Trip trip = target.tripId() == null ? null
                : DAO.getInstance().getTrip(target.tripId(), Cached.YES).orElse(null);
        final AuditActor who = caller.auditActor();
        record(target, subject, why, trip, who);
        notify(target, subject, why, trip, caller);
        return ReportOutcome.accepted();
    }

    /**
     * The reported message (empty for a PHOTO target, which has no message row), or {@code null} when the
     * target does not exist or the reporter may not see it. Null-vs-empty is the whole point of the
     * signature: "no message here" and "there is nothing you may report" are different answers.
     */
    private Optional<ChatMessage> locate(final ReportTarget target, final Caller caller) {
        final Person.Id me = caller.personId();
        switch (target.kind()) {
            case MESSAGE -> {
                if (target.tripId() == null || target.messageId() == null) {
                    return null;
                }
                final ChatCommands chat = chatSource.get();
                if (!chat.canParticipate(target.tripId(), me)) {
                    return null;
                }
                final ChatChannel channel = chat.getChannel(target.tripId());
                if (channel == null) {
                    return null;
                }
                final Optional<ChatMessage> message =
                        DAO.getInstance().getChatMessage(channel.getId(), target.messageId(), Cached.NO);
                return message.isEmpty() || message.get().isDeleted() ? null : message;
            }
            case PHOTO -> {
                if (target.photoKey() == null || target.photoKey().isBlank()
                        || photoChatSource.get().readDenialFor(target.photoKey(), caller) != null) {
                    return null;
                }
                return Optional.empty();
            }
            case COMMENT -> {
                if (target.photoKey() == null || target.photoKey().isBlank() || target.messageId() == null
                        || photoChatSource.get().readDenialFor(target.photoKey(), caller) != null) {
                    return null;
                }
                final Optional<ChatMessage> comment = DAO.getInstance().getChatMessage(
                        ChatChannel.Id.forPhoto(target.photoKey()), target.messageId(), Cached.NO);
                return comment.isEmpty() || comment.get().isDeleted() ? null : comment;
            }
            default -> {
                return null;
            }
        }
    }

    private static void record(final ReportTarget target, final Optional<ChatMessage> subject,
            final String reason, final Trip trip, final AuditActor who) {
        final AuditEventBuilder event = Audit.builder(AuditAction.REPORT, AuditOutcome.SUCCESS).actor(who);
        if (target.messageId() != null) {
            event.target(AuditEventBuilder.TARGET_CHAT_MESSAGE, target.messageId().getValue());
        } else {
            event.target(AuditEventBuilder.TARGET_MEDIA, target.photoKey());
        }
        if (trip != null && trip.getOrgId() != null) {
            event.org(trip.getOrgId());
        }
        event.message("moderation.report: " + describe(target, subject) + "; reason=" + reason).log();
    }

    /** Best effort: a report that is in the trail but whose mail failed is still a report. */
    private void notify(final ReportTarget target, final Optional<ChatMessage> subject, final String reason,
            final Trip trip, final Caller caller) {
        try {
            final MailAddressCommands addresses = addressSource.get();
            final String to = addresses.recipient(KnownSettings.MODERATION_REPORT_EMAIL, trip);
            final String bcc = config.getString(KnownSettings.MODERATION_PLATFORM_EMAIL);
            final String from = addresses.from(KnownSettings.MODERATION_REPORT_FROM);
            if (to == null || to.isBlank()) {
                log.warn("Content report not mailed: no recipient configured ({})", describe(target, subject));
                return;
            }
            final String tripTitle = trip == null ? "(unknown trip)" : Util.orDefault(trip.getTitle(), "");
            mailSource.get().send(from, to, bcc == null || bcc.isBlank() ? null : bcc, null,
                    "Content report: " + tripTitle, body(target, subject, reason, trip, caller),
                    caller.auditActor());
        } catch (final RuntimeException ex) {
            log.warn("Content report not mailed ({})", describe(target, subject), ex);
        }
    }

    private String body(final ReportTarget target, final Optional<ChatMessage> subject, final String reason,
            final Trip trip, final Caller caller) {
        final Person reporter = DAO.getInstance().getPerson(caller.personId(), Cached.YES).orElse(null);
        final StringBuilder html = new StringBuilder(1024);
        html.append("<p>Someone reported content in the trip chat and asked for it to be reviewed. ")
                .append("Please look at it and act (delete, mute or remove) ").append(RESPONSE_PROMISE)
                .append(".</p>");
        html.append("<table cellpadding=\"4\">");
        row(html, "Trip", trip == null ? escape(target.tripId()) : escape(trip.getTitle()));
        row(html, "Reported by", reporter == null ? escape(caller.auditActor().email())
                : escape(displayName(reporter) + " <" + Util.orDefault(reporter.getEmail(), "") + ">"));
        row(html, "What", kindLabel(target.kind()));
        if (subject.isPresent()) {
            final ChatMessage message = subject.get();
            final Person author = message.getAuthorId() == null ? null
                    : DAO.getInstance().getPerson(message.getAuthorId(), Cached.YES).orElse(null);
            row(html, "Written by", author == null ? "(unknown)" : escape(displayName(author)));
            row(html, "Sent", message.getSentAt() == null ? "" : message.getSentAt().toString());
            row(html, "Text", escape(snippet(message.getBody())));
            if (!message.getAttachments().isEmpty()) {
                row(html, "Photos", Integer.toString(message.getAttachments().size()));
            }
            row(html, "Message id", escape(message.getId() == null ? "" : message.getId().getValue()));
        }
        if (target.photoKey() != null) {
            row(html, "Photo", escape(target.photoKey()));
        }
        row(html, "Reason", escape(reason));
        row(html, "Reported at", Instant.now().toString());
        html.append("</table>");
        html.append("<p>Open the trip's chat page as a chat manager to delete the content, mute the author ")
                .append("or remove them from the chat. This report is also in the audit trail.</p>");
        return html.toString();
    }

    private static String describe(final ReportTarget target, final Optional<ChatMessage> subject) {
        final String author = subject == null || subject.isEmpty() || subject.get().getAuthorId() == null
                ? "?" : subject.get().getAuthorId().getValue();
        return target.kind() + " trip=" + target.tripId()
                + (target.messageId() == null ? "" : " msg=" + target.messageId().getValue())
                + (target.photoKey() == null ? "" : " photo=" + target.photoKey())
                + " author=" + author;
    }

    private static String kindLabel(final TargetKind kind) {
        return switch (kind) {
            case MESSAGE -> "A chat message";
            case PHOTO -> "A photo";
            case COMMENT -> "A comment on a photo";
        };
    }

    private static String displayName(final Person person) {
        return (Util.orDefault(person.getFirst(), "") + " " + Util.orDefault(person.getLast(), "")).strip();
    }

    private static String snippet(final String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= SNIPPET_CHARS ? body : body.substring(0, SNIPPET_CHARS) + "…";
    }

    private static void row(final StringBuilder html, final String label, final String value) {
        html.append("<tr><th align=\"left\" valign=\"top\">").append(label).append("</th><td>")
                .append(value == null ? "" : value).append("</td></tr>");
    }

    static String escape(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
