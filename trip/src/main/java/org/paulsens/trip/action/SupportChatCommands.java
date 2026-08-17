package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.paulsens.trip.util.EmailAddresses;
import org.paulsens.trip.util.TripThreads;
import org.paulsens.trip.cache.Cached;

/**
 * The site-wide technical-support channel ({@code support:main}) -- the {@code PhotoChatCommands} precedent:
 * a non-trip channel kind with its OWN authorization, reusing the chat tables and models untouched.
 *
 * <p>Membership rows ARE the admin list: explicit JOINED rows (managed from the admin Settings page) may read
 * and post; global {@code chatAdmin} always may. There is no trip, so there is no implicit roster and nothing
 * ever archives.
 *
 * <p>Requests are the twist: any authenticated user may FILE one ({@link #fileRemovalRequest},
 * {@link #fileLimitRequest}) without ever gaining read access. The stored message is authored by the
 * REQUESTER -- accurate attribution -- which is exactly the "bring your own authorization design" case the
 * {@code ChatCommands.send} contract names; this class deliberately does not go through {@code send()}.
 * The body carries a literal {@code @all} for UI highlight, but the email fan-out is support-specific: every
 * JOINED member is mailed (there is no trip roster to resolve {@code @all} against), gated by its own
 * {@code support.mail.enabled} switch rather than the chat-mail master switch (which ships off).
 */
@Slf4j
@Named("support")
@ApplicationScoped
public class SupportChatCommands {
    /** How far back the one-open-request-per-kind guard looks. */
    private static final Duration REQUEST_COOLDOWN = Duration.ofHours(24);
    private static final int GUARD_SCAN_LIMIT = 100;
    private static final int MAX_DETAILS_CHARS = 2000;
    /** Machine-readable first line of a request message; also what the duplicate guard matches on. */
    static final String REMOVAL_MARKER = "[support:family-removal]";
    static final String LIMIT_MARKER = "[support:family-limit]";
    static final String EMAIL_CONFLICT_MARKER = "[support:email-conflict]";

    private final ChatRateLimiter rateLimiter;
    private final ConfigCommands config;
    private final MailCommands mail;
    private final Supplier<Caller> callerSource;

    /** Privilege name, mirrored from ChatCommands' private constant: global chat administration. */
    private static final String CHAT_ADMIN_PRIV = "chatAdmin";

    public SupportChatCommands() {
        this(new ChatRateLimiter(DAO.getInstance().getCacheClient()), new ConfigCommands(),
                new MailCommands(), Caller::current);
    }

    /** Test seam: every collaborator handed in (the FamilyCommands/ChatCommands pattern). */
    public SupportChatCommands(final ChatRateLimiter rateLimiter, final ConfigCommands config,
            final MailCommands mail, final Supplier<Caller> callerSource) {
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.mail = mail;
        this.callerSource = callerSource;
    }

    // ------------------------------------------------------------------ authorization

    /** Whether this caller may read/post the support channel: an explicit JOINED row, or global chatAdmin. */
    public boolean canReadSupport(final Caller caller) {
        if (caller == null || !caller.isAuthenticated()) {
            return false;
        }
        if (caller.has(CHAT_ADMIN_PRIV)) {
            return true;
        }
        return isChannelAdmin(caller.personId());
    }

    /** EL face of {@link #canReadSupport} for the signed-in user (My Chats, chat page render gates). */
    public boolean isSupportReader() {
        return canReadSupport(callerSource.get());
    }

    private boolean isChannelAdmin(final Person.Id personId) {
        if (personId == null) {
            return false;
        }
        return dao().getChatMembership(ChatChannel.Id.forSupport(), personId, Cached.NO)
                .map(ChatMembership::getState)
                .map(state -> state == ChatMembership.MemberState.JOINED)
                .orElse(false);
    }

    // ------------------------------------------------------------------ admin-list management (settings page)

    /** The resolved people currently holding a JOINED membership row -- the support-admin list. */
    public List<Person> listAdmins() {
        final List<Person> admins = new ArrayList<>();
        for (final ChatMembership row : dao().listChatMembers(ChatChannel.Id.forSupport(), Cached.NO)) {
            if (row.getState() == ChatMembership.MemberState.JOINED) {
                dao().getPerson(row.getPersonId(), Cached.NO).ifPresent(admins::add);
            }
        }
        return admins;
    }

    public boolean addAdmin(final Person.Id target) {
        if (!requireConfigAdmin() || target == null) {
            return false;
        }
        final ChatChannel channel = ensureSupportChannel(callerSource.get().auditActor());
        final ChatMembership existing = dao().getChatMembership(channel.getId(), target, Cached.NO).orElse(null);
        final ChatMembership joined = (existing == null)
                ? ChatMembership.joining(channel.getId(), target, Instant.now())
                : existing.withState(ChatMembership.MemberState.JOINED);
        if (!dao().saveChatMembership(joined)) {
            return fail("Unable to save", "Could not add the support admin.");
        }
        auditChannel("Added support-channel admin " + target.getValue());
        return true;
    }

    public boolean removeAdmin(final Person.Id target) {
        if (!requireConfigAdmin() || target == null) {
            return false;
        }
        final ChatMembership existing =
                dao().getChatMembership(ChatChannel.Id.forSupport(), target, Cached.NO).orElse(null);
        if (existing == null || existing.getState() != ChatMembership.MemberState.JOINED) {
            return true;
        }
        if (!dao().saveChatMembership(existing.withRemoved(Instant.now(), "settings page",
                actorId()))) {
            return fail("Unable to save", "Could not remove the support admin.");
        }
        auditChannel("Removed support-channel admin " + target.getValue());
        return true;
    }

    // ------------------------------------------------------------------ reading + replies (the admin page)

    /** One rendered support message. A plain record, Serializable so a page scope could hold it. */
    public record SupportMessage(String author, String sentAt, String body)
            implements java.io.Serializable {
    }

    /** Same zone the rest of the site displays in; formatted here because f:convertDateTime has no Instant. */
    private static final java.time.format.DateTimeFormatter SENT_FORMAT = java.time.format.DateTimeFormatter
            .ofPattern("MMM dd, yyyy 'at' HH:mm").withZone(java.time.ZoneId.of("America/Los_Angeles"));

    /**
     * Newest-first recent messages for the support page. Empty for anyone who may not read -- the page gates
     * too, but data must not depend on the page remembering to.
     */
    public List<SupportMessage> recentMessages(final int limit) {
        if (!canReadSupport(callerSource.get())) {
            return List.of();
        }
        final List<SupportMessage> result = new ArrayList<>();
        for (final ChatMessage message : dao().getRawChatMessagesBefore(
                ChatChannel.Id.forSupport(), null, Math.min(Math.max(limit, 1), 200), Cached.NO)) {
            if (message.getDeletedAt() == null) {
                result.add(new SupportMessage(authorName(message.getAuthorId()),
                        message.getSentAt() == null ? "" : SENT_FORMAT.format(message.getSentAt()),
                        message.getBody()));
            }
        }
        return result;
    }

    /**
     * Links a message body may embed and have rendered clickable: site-relative {@code /....jsf?...} paths
     * (what the system-generated requests contain) and absolute http(s) URLs.
     */
    private static final java.util.regex.Pattern LINK_TOKEN = java.util.regex.Pattern
            .compile("https?://\\S+|/[A-Za-z0-9_\\-./]+\\.jsf(?:\\?[A-Za-z0-9_\\-.=&%:]*)?");

    /**
     * The message body as escape-safe HTML with recognized links wrapped in anchors, so the support page
     * can render it with {@code escape="false"}. Everything outside a link is HTML-escaped -- bodies are
     * member-authored text (removal-request details), so raw rendering would be an XSS hole. Trailing
     * sentence punctuation stays outside the anchor ("open /admin/x.jsf." must not link the dot).
     */
    public String renderBody(final String body) {
        if (body == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(body.length() + 64);
        final java.util.regex.Matcher links = LINK_TOKEN.matcher(body);
        int copied = 0;
        while (links.find()) {
            String url = links.group();
            while (!url.isEmpty() && ".,;:!?)'\"".indexOf(url.charAt(url.length() - 1)) >= 0) {
                url = url.substring(0, url.length() - 1);
            }
            if (url.isEmpty()) {
                continue;
            }
            out.append(escapeHtml(body.substring(copied, links.start())));
            final String href = escapeHtml(url);
            out.append("<a href=\"").append(href).append('"');
            if (url.startsWith("http")) {
                out.append(" target=\"_blank\" rel=\"noopener\"");
            }
            out.append('>').append(href).append("</a>");
            copied = links.start() + url.length();
        }
        out.append(escapeHtml(body.substring(copied)));
        return out.toString();
    }

    private static String escapeHtml(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** A reply by a support reader (channel admin or chatAdmin). Not a request: no marker, no email fan-out. */
    public boolean postReply(final String body) {
        final Caller caller = callerSource.get();
        if (!canReadSupport(caller)) {
            return fail("Not authorized", "Only support-channel admins can post here.");
        }
        final String text = clean(body);
        if (text.isEmpty()) {
            return fail("Empty message", "Type a message first.");
        }
        final ChatChannel channel = ensureSupportChannel(caller.auditActor());
        final ChatRateLimiter.Decision decision =
                rateLimiter.check(channel, caller.personId(), Instant.now());
        if (!decision.isAllowed() || decision.getAutoMuteUntil() != null) {
            return fail("Too fast", "Please wait a moment before posting again.");
        }
        final ChatMessage draft = new ChatMessage(
                null, channel.getId(), caller.personId(), null,
                ChatMessage.MessageKind.TEXT, text, null, List.of(), null,
                null, null, null, null, null, null);
        try {
            return dao().saveChatMessage(draft, channel, null).isPresent();
        } catch (final RuntimeException ex) {
            log.warn("Support reply was not stored", ex);
            return fail("Not sent", "The message could not be saved. Please try again.");
        }
    }

    private String authorName(final Person.Id authorId) {
        if (authorId == null) {
            return "(system)";
        }
        return dao().getPerson(authorId, Cached.NO).map(Person::getPreferredName).orElse(authorId.getValue());
    }

    // ------------------------------------------------------------------ requests

    /**
     * Files a "please remove this person from my family" request. Caller must be a family manager of the
     * target's family (validated HERE -- the requester never gains channel access, so this method carries its
     * own authorization). Returns true when the request was posted.
     */
    public boolean fileRemovalRequest(final Person.Id targetId, final String details) {
        final Caller caller = callerSource.get();
        final Person me = personOf(caller);
        if (me == null) {
            return fail("Not signed in", "Sign in to send a support request.");
        }
        final Person target = dao().getPerson(targetId, Cached.NO).orElse(null);
        final Family family = (me.getFamilyId() == null) ? null
                : dao().getFamily(me.getFamilyId(), Cached.NO).orElse(null);
        if (target == null || family == null || !family.isMember(targetId)) {
            return fail("Not in your family", "You can only request removal of your own family members.");
        }
        if (!family.isManager(me.getId())) {
            return fail("Not a family manager", "Only a family manager can request a removal.");
        }
        final String chatBody = REMOVAL_MARKER + "\n@all " + describe(me) + " asks that "
                + describe(target) + " be removed from their family.\n\nDetails: " + clean(details)
                + "\n\nAdmin: open /admin/adminManagePerson.jsf?id=" + targetId.getValue()
                + " and use \"Remove from family\".";
        return postRequest(me, caller, REMOVAL_MARKER, chatBody,
                "Support request: remove " + describe(target) + " from a family",
                "<p><b>" + MailTemplates.escape(describe(me)) + "</b> asks that <b>"
                        + MailTemplates.escape(describe(target)) + "</b> be removed from their family.</p>"
                        + detailsBlock(details)
                        + "<p><a href=\"" + baseUrl() + "/admin/adminManagePerson.jsf?id="
                        + targetId.getValue() + "\">Open their Manage People page</a> and use "
                        + "“Remove from family”.</p>");
    }

    /** Files a "please raise my family-size limit" request; only meaningful when the limit is reached. */
    public boolean fileLimitRequest(final String details) {
        final Caller caller = callerSource.get();
        final Person me = personOf(caller);
        if (me == null) {
            return fail("Not signed in", "Sign in to send a support request.");
        }
        final Family family = (me.getFamilyId() == null) ? null
                : dao().getFamily(me.getFamilyId(), Cached.NO).orElse(null);
        final int limit = config.getInt(KnownSettings.FAMILY_MAX_MEMBERS, 1, 100);
        if (family == null || family.getSize() < limit) {
            return fail("Limit not reached", "Your family is not at the size limit.");
        }
        final String chatBody = LIMIT_MARKER + "\n@all " + describe(me)
                + " has reached the family size limit (" + limit + ") and asks for an increase.\n\nDetails: "
                + clean(details)
                + "\n\nAdmin: raise family.maxMembers on /admin/settings.jsf.";
        return postRequest(me, caller, LIMIT_MARKER, chatBody,
                "Support request: raise the family size limit for " + describe(me),
                "<p><b>" + MailTemplates.escape(describe(me)) + "</b> has reached the family size limit ("
                        + limit + ") and asks for an increase.</p>" + detailsBlock(details)
                        + "<p>Raise <code>family.maxMembers</code> on <a href=\"" + baseUrl()
                        + "/admin/settings.jsf\">the Settings page</a>.</p>");
    }

    /**
     * Files a "two of our accounts want the same email address" request -- the merge case the profile page
     * offers when an address is already taken.
     *
     * <p>Deliberately does NOT name the conflicting account's owner in the message beyond what the asker was
     * already shown: the admin can look the address up, and the request must not become a way to enumerate
     * who holds which address. Authorization is the same rule the profile page itself uses -- the asker must
     * be able to edit {@code subjectId}.
     */
    public boolean fileEmailConflictRequest(final Person.Id subjectId, final String email,
            final String details) {
        final Caller caller = callerSource.get();
        final Person me = personOf(caller);
        if (me == null) {
            return fail("Not signed in", "Sign in to send a support request.");
        }
        final Person subject = dao().getPerson(subjectId, Cached.NO).orElse(null);
        if (subject == null
                || !PersonCommands.getPersonCommands().canAccessUserId(me, subjectId)) {
            return fail("Not your profile", "You can only ask about a profile you manage.");
        }
        if (!EmailAddresses.isValid(email)) {
            return fail("Not a valid email", "Enter the email address in question first.");
        }
        final String address = email.trim();
        final String chatBody = EMAIL_CONFLICT_MARKER + "\n@all " + describe(me) + " reports that the email "
                + address + " is already in use and asks for help with "
                + describe(subject) + "'s account.\n\nDetails: " + clean(details)
                + "\n\nAdmin: the address belongs to an existing person -- decide whether the two accounts"
                + " should be merged, or the address moved. /admin/adminManagePerson.jsf?id="
                + subjectId.getValue();
        return postRequest(me, caller, EMAIL_CONFLICT_MARKER, chatBody,
                "Support request: email address already in use (" + address + ")",
                "<p><b>" + MailTemplates.escape(describe(me)) + "</b> reports that <b>"
                        + MailTemplates.escape(address) + "</b> is already in use, and asks for help with "
                        + MailTemplates.escape(describe(subject)) + "'s account.</p>"
                        + detailsBlock(details)
                        + "<p>Decide whether the two accounts should be merged or the address moved: "
                        + "<a href=\"" + baseUrl() + "/admin/adminManagePerson.jsf?id="
                        + subjectId.getValue() + "\">open their Manage People page</a>.</p>");
    }

    // ------------------------------------------------------------------ internals

    private boolean postRequest(final Person me, final Caller caller, final String marker,
            final String chatBody, final String mailSubject, final String mailHtml) {
        final Instant now = Instant.now();
        final ChatChannel channel = ensureSupportChannel(caller.auditActor());
        if (hasOpenRequest(channel, me.getId(), marker, now)) {
            return fail("Already requested", "You already sent this request recently. An administrator "
                    + "will follow up -- there is no need to send it again.");
        }
        final ChatRateLimiter.Decision decision = rateLimiter.check(channel, me.getId(), now);
        if (!decision.isAllowed() || decision.getAutoMuteUntil() != null) {
            return fail("Too many requests", "Please wait a while before sending another request.");
        }
        final ChatMessage draft = new ChatMessage(
                null, channel.getId(), me.getId(), null,
                ChatMessage.MessageKind.TEXT, chatBody, null, List.of(), null,
                null, null, null, null, null, null);
        final ChatMessage stored;
        try {
            stored = dao().saveChatMessage(draft, channel, null).orElse(null);
        } catch (final RuntimeException ex) {
            log.warn("Support request was not stored", ex);
            return fail("Not sent", "The request could not be saved. Please try again.");
        }
        if (stored == null) {
            return fail("Not sent", "The request could not be saved. Please try again.");
        }
        Audit.builder(AuditAction.ALARM, AuditOutcome.SUCCESS)
                .actor(caller.auditActor())
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, channel.getId().getValue())
                .message("support.request: " + marker + " filed by " + describe(me))
                .log();
        notifyAdmins(stored, me, mailSubject, mailHtml);
        return true;
    }

    /** One open request per kind per person per cooldown window, matched on the marker line. */
    private boolean hasOpenRequest(final ChatChannel channel, final Person.Id me, final String marker,
            final Instant now) {
        final Instant floor = now.minus(REQUEST_COOLDOWN);
        for (final ChatMessage message : dao().getRawChatMessagesBefore(channel.getId(), null,
                GUARD_SCAN_LIMIT, Cached.NO)) {
            if (message.getSentAt() != null && message.getSentAt().isBefore(floor)) {
                return false;   // newest-first: everything after this is older still
            }
            if (me.equals(message.getAuthorId()) && message.getDeletedAt() == null
                    && message.getBody() != null && message.getBody().startsWith(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emails every JOINED member (minus the requester). Fan-out is membership-row based -- there is no trip
     * roster here -- and rides its own switch, not {@code CHAT_MAIL_ENABLED}. Per-recipient dedupe reuses the
     * chat notification claim key, so a retry cannot double-send. Dispatched off-thread like the chat
     * notifiers; mail must never hold up the request.
     */
    private void notifyAdmins(final ChatMessage stored, final Person requester, final String subject,
            final String html) {
        if (!config.getBoolean(KnownSettings.SUPPORT_MAIL_ENABLED)) {
            return;
        }
        final List<Person> admins = listAdmins();
        TripThreads.startAs(AuditActor.system(), () -> deliver(stored, requester, subject, html, admins));
    }

    private void deliver(final ChatMessage stored, final Person requester, final String subject,
            final String html, final List<Person> admins) {
        // Prefer the runtime-editable MAIL template; the inline HTML built by the request methods is the
        // fallback when the starter was never installed. Rendered once -- token-substitution only, no
        // Faces coupling, so this off-request thread is fine.
        final MailCommands.ManagedMail rendered = mail.renderManagedTemplate(
                org.paulsens.trip.content.StarterTemplates.SUPPORT_REQUEST_ID,
                java.util.Map.of("subject", subject, "requestBlock", new MailTemplates.Raw(html)));
        final String finalSubject = (rendered == null) ? subject : rendered.subject();
        final String finalBody = (rendered == null) ? html : rendered.body();
        for (final Person admin : admins) {
            if (admin.getId().equals(requester.getId()) || !EmailAddresses.isValid(admin.getEmail())) {
                continue;
            }
            final String dedupe = stored.getId().getValue() + "|" + admin.getId().getValue() + "|EMAIL";
            if (!dao().getCacheClient().tryAcquireLock(
                    CacheKeys.chatNotifySentKey(dedupe), CacheKeys.CHAT_NOTIFY_SENT_TTL)) {
                continue;
            }
            try {
                mail.send(config.getString(KnownSettings.CHAT_MAIL_FROM), mail.formatEmail(admin), null,
                        config.getString(KnownSettings.CHAT_MAIL_REPLY_TO), finalSubject, finalBody,
                        AuditActor.system());
            } catch (final RuntimeException ex) {
                log.warn("Support-request mail to {} failed", admin.getId(), ex);
            }
        }
    }

    ChatChannel ensureSupportChannel(final AuditActor actor) {
        final ChatChannel.Id id = ChatChannel.Id.forSupport();
        final ChatChannel existing = dao().getChatChannel(id, Cached.NO).orElse(null);
        if (existing != null) {
            return existing;
        }
        // No trip, no retention (requests live forever), no media; never archived (isArchived needs a trip).
        final ChatChannel channel = new ChatChannel(
                id, null, ChatChannel.Kind.SUPPORT, "Technical Support",
                "Support requests from members; admins are managed on the Settings page.",
                List.of(), ChatSettings.builder().allowMedia(false).build(), Instant.now(),
                actor == null ? null : actor.id(), null, null, null, null);
        dao().saveChatChannel(channel);
        return channel;
    }

    private String detailsBlock(final String details) {
        final String cleaned = clean(details);
        return cleaned.isEmpty() ? ""
                : "<blockquote style=\"margin:8px 0;padding:6px 10px;border-left:3px solid #ccc;\">"
                        + MailTemplates.escape(cleaned) + "</blockquote>";
    }

    private String clean(final String details) {
        if (details == null || details.isBlank()) {
            return "";
        }
        final String stripped = details.strip();
        return stripped.length() > MAX_DETAILS_CHARS ? stripped.substring(0, MAX_DETAILS_CHARS) : stripped;
    }

    private String baseUrl() {
        return config.getString(KnownSettings.CHAT_MAIL_BASE_URL);
    }

    private Person personOf(final Caller caller) {
        if (caller == null || !caller.isAuthenticated()) {
            return null;
        }
        return dao().getPerson(caller.personId(), Cached.NO).orElse(null);
    }

    private boolean requireConfigAdmin() {
        if (callerSource.get().has(PrivilegeCommands.CONFIG_ADMIN)) {
            return true;
        }
        return fail("Not authorized", "Managing support-channel admins requires configAdmin.");
    }

    private void auditChannel(final String message) {
        Audit.builder(AuditAction.CHAT_ADMIN, AuditOutcome.SUCCESS)
                .actor(callerSource.get().auditActor())
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, ChatChannel.Id.forSupport().getValue())
                .message(message)
                .log();
    }

    private String actorId() {
        final AuditActor actor = callerSource.get().auditActor();
        return actor == null ? null : actor.id();
    }

    private String describe(final Person person) {
        return AuditCommands.describe(person);
    }

    private boolean fail(final String summary, final String detail) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail);
        return false;
    }

    private DAO dao() {
        return DAO.getInstance();
    }
}
