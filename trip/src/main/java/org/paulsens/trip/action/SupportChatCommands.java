package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.ReaderLanguage;
import org.paulsens.trip.i18n.Text;
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
import org.paulsens.trip.util.Html;

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
    static final String TRIP_ROLES_MARKER = "[support:trip-roles]";

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
            return fail(Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.supportChatCommands.adminNotAdded"));
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
            return fail(Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.supportChatCommands.adminNotRemoved"));
        }
        auditChannel("Removed support-channel admin " + target.getValue());
        return true;
    }

    // ------------------------------------------------------------------ reading + replies (the admin page)

    /** One rendered support message. A plain record, Serializable so a page scope could hold it. */
    public record SupportMessage(String author, String sentAt, String body)
            implements java.io.Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.
    }

    /** Same zone the rest of the site displays in; formatted here because f:convertDateTime has no Instant. */
    private static final java.time.ZoneId SENT_ZONE = java.time.ZoneId.of("America/Los_Angeles");
    /** English keeps its "Sep 03, 2026 at 15:05"; another language reads its own date and 24-hour time. */
    private static final String SENT_ENGLISH = "MMM dd, yyyy 'at' HH:mm";

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
                        message.getSentAt() == null ? "" : Formats.formatter("yMMMddHm", SENT_ENGLISH,
                                RequestContext.currentLocale()).withZone(SENT_ZONE).format(message.getSentAt()),
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
            out.append(Html.escape(body.substring(copied, links.start())));
            final String href = Html.escape(url);
            out.append("<a href=\"").append(href).append('"');
            if (url.startsWith("http")) {
                out.append(" target=\"_blank\" rel=\"noopener\"");
            }
            out.append('>').append(href).append("</a>");
            copied = links.start() + url.length();
        }
        out.append(Html.escape(body.substring(copied)));
        return out.toString();
    }

    /** A reply by a support reader (channel admin or chatAdmin). Not a request: no marker, no email fan-out. */
    public boolean postReply(final String body) {
        final Caller caller = callerSource.get();
        if (!canReadSupport(caller)) {
            return fail(Text.get("feedback.shared.notAuthorized"),
                    Text.get("feedback.supportChatCommands.onlyAdminsPost"));
        }
        final String text = clean(body);
        if (text.isEmpty()) {
            return fail(Text.get("feedback.supportChatCommands.emptyMessage"),
                    Text.get("feedback.supportChatCommands.typeFirst"));
        }
        final ChatChannel channel = ensureSupportChannel(caller.auditActor());
        final ChatRateLimiter.Decision decision =
                rateLimiter.check(channel, caller.personId(), Instant.now());
        if (!decision.isAllowed() || decision.getAutoMuteUntil() != null) {
            return fail(Text.get("feedback.supportChatCommands.tooFast"),
                    Text.get("feedback.supportChatCommands.waitBeforePosting"));
        }
        final ChatMessage draft = new ChatMessage(
                null, channel.getId(), caller.personId(), null,
                ChatMessage.MessageKind.TEXT, text, null, List.of(), null,
                null, null, null, null, null, null);
        try {
            return dao().saveChatMessage(draft, channel, null).isPresent();
        } catch (final RuntimeException ex) {
            log.warn("Support reply was not stored", ex);
            return fail(Text.get("feedback.shared.notSent"),
                    Text.get("feedback.supportChatCommands.messageNotSaved"));
        }
    }

    private String authorName(final Person.Id authorId) {
        if (authorId == null) {
            return Text.get("feedback.supportChatCommands.systemAuthor");
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
            return fail(Text.get("feedback.shared.notSignedIn"),
                    Text.get("feedback.supportChatCommands.signInToRequest"));
        }
        final Person target = dao().getPerson(targetId, Cached.NO).orElse(null);
        final Family family = (me.getFamilyId() == null) ? null
                : dao().getFamily(me.getFamilyId(), Cached.NO).orElse(null);
        if (target == null || family == null || !family.isMember(targetId)) {
            return fail(Text.get("feedback.supportChatCommands.notInYourFamily"),
                    Text.get("feedback.supportChatCommands.onlyOwnFamilyMembers"));
        }
        if (!family.isManager(me.getId())) {
            return fail(Text.get("feedback.family.notAManager"),
                    Text.get("feedback.supportChatCommands.onlyManagerRequests"));
        }
        final String chatBody = REMOVAL_MARKER + "\n@all " + describe(me) + " asks that "
                + describe(target) + " be removed from their family.\n\nDetails: " + clean(details)
                + "\n\nAdmin: open /admin/adminManagePerson.jsf?id=" + targetId.getValue()
                + " and use \"Remove from family\".";
        final String page = baseUrl() + "/admin/adminManagePerson.jsf?id=" + targetId.getValue();
        return postRequest(me, caller, REMOVAL_MARKER, chatBody,
                locale -> Text.forLocale(locale, "mail.support.removal.subject", describe(target)),
                locale -> "<p>" + html(locale, "mail.support.removal.intro", describe(me), describe(target))
                        + "</p>" + detailsBlock(details) + "<p>"
                        + html(locale, "mail.support.removal.action", page) + "</p>");
    }

    /** Files a "please raise my family-size limit" request; only meaningful when the limit is reached. */
    public boolean fileLimitRequest(final String details) {
        final Caller caller = callerSource.get();
        final Person me = personOf(caller);
        if (me == null) {
            return fail(Text.get("feedback.shared.notSignedIn"),
                    Text.get("feedback.supportChatCommands.signInToRequest"));
        }
        final Family family = (me.getFamilyId() == null) ? null
                : dao().getFamily(me.getFamilyId(), Cached.NO).orElse(null);
        final int limit = config.getInt(KnownSettings.FAMILY_MAX_MEMBERS, 1, 100);
        if (family == null || family.getSize() < limit) {
            return fail(Text.get("feedback.supportChatCommands.limitNotReached"),
                    Text.get("feedback.supportChatCommands.familyNotAtLimit"));
        }
        final String chatBody = LIMIT_MARKER + "\n@all " + describe(me)
                + " has reached the family size limit (" + limit + ") and asks for an increase.\n\nDetails: "
                + clean(details)
                + "\n\nAdmin: raise family.maxMembers on /admin/settings.jsf.";
        return postRequest(me, caller, LIMIT_MARKER, chatBody,
                locale -> Text.forLocale(locale, "mail.support.limit.subject", describe(me)),
                locale -> "<p>" + html(locale, "mail.support.limit.intro", describe(me), String.valueOf(limit))
                        + "</p>" + detailsBlock(details) + "<p>"
                        + html(locale, "mail.support.limit.action", baseUrl() + "/admin/settings.jsf") + "</p>");
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
            return fail(Text.get("feedback.shared.notSignedIn"),
                    Text.get("feedback.supportChatCommands.signInToRequest"));
        }
        final Person subject = dao().getPerson(subjectId, Cached.NO).orElse(null);
        if (subject == null
                || !PersonCommands.getPersonCommands().canAccessUserId(me, subjectId)) {
            return fail(Text.get("feedback.supportChatCommands.notYourProfile"),
                    Text.get("feedback.supportChatCommands.onlyManagedProfile"));
        }
        if (!EmailAddresses.isValid(email)) {
            return fail(Text.get("feedback.supportChatCommands.notAValidEmail"),
                    Text.get("feedback.supportChatCommands.enterEmailFirst"));
        }
        final String address = email.trim();
        final String chatBody = EMAIL_CONFLICT_MARKER + "\n@all " + describe(me) + " reports that the email "
                + address + " is already in use and asks for help with "
                + describe(subject) + "'s account.\n\nDetails: " + clean(details)
                + "\n\nAdmin: the address belongs to an existing person -- decide whether the two accounts"
                + " should be merged, or the address moved. /admin/adminManagePerson.jsf?id="
                + subjectId.getValue();
        final String page = baseUrl() + "/admin/adminManagePerson.jsf?id=" + subjectId.getValue();
        return postRequest(me, caller, EMAIL_CONFLICT_MARKER, chatBody,
                locale -> Text.forLocale(locale, "mail.support.emailConflict.subject", address),
                locale -> "<p>" + html(locale, "mail.support.emailConflict.intro", describe(me), address,
                        describe(subject)) + "</p>" + detailsBlock(details) + "<p>"
                        + html(locale, "mail.support.emailConflict.action", page) + "</p>");
    }

    /**
     * Posts a NOTICE to the support channel: a trip creator could not receive some of the standard creator
     * roles because the owning org's allow-list withholds them ({@code OrgCommands.grantCreatorTripRoles}).
     * Unlike the user-filed requests above, there is no duplicate guard -- each created trip deserves its
     * own notice -- but the rate limiter still applies. The message is authored by the CREATOR (accurate
     * attribution, the postRequest contract).
     */
    public boolean fileMissingTripRolesNotice(final String tripId, final String tripTitle,
            final String orgName, final List<String> missingRoles) {
        final Caller caller = callerSource.get();
        final Person me = personOf(caller);
        if (me == null || tripId == null || missingRoles == null || missingRoles.isEmpty()) {
            return false;
        }
        final String roles = String.join(", ", missingRoles);
        final String title = (tripTitle == null || tripTitle.isBlank()) ? tripId : tripTitle;
        final String chatBody = TRIP_ROLES_MARKER + "\n@all " + describe(me) + " created trip '" + title
                + "' (" + orgName + ") but could not be granted: " + roles
                + " -- withheld by the organization's grantable-privileges allow-list."
                + "\n\nAdmin: grant the roles on /trip/edit.jsf?id=" + tripId
                + " or adjust the allow-list on /admin/organizations.jsf.";
        return postRequest(me, caller, TRIP_ROLES_MARKER, chatBody,
                locale -> Text.forLocale(locale, "mail.support.tripRoles.subject", title),
                locale -> "<p>" + html(locale, "mail.support.tripRoles.intro", describe(me), title, orgName, roles)
                        + "</p><p>" + html(locale, "mail.support.tripRoles.action",
                                baseUrl() + "/trip/edit.jsf?id=" + tripId, baseUrl() + "/admin/organizations.jsf")
                        + "</p>",
                false);
    }

    // ------------------------------------------------------------------ internals

    /** A bundle sentence carrying its own markup, every argument escaped first. */
    private static String html(final Locale locale, final String key, final String... args) {
        return MailTemplates.html(locale, key, args).html();
    }

    private boolean postRequest(final Person me, final Caller caller, final String marker,
            final String chatBody, final Function<Locale, String> mailSubject,
            final Function<Locale, String> mailHtml) {
        return postRequest(me, caller, marker, chatBody, mailSubject, mailHtml, true);
    }

    /**
     * Posts the request and tells the admins. The mail's subject and body are made per language, because each
     * admin reads it in their own ({@link ReaderLanguage#ofStaff}).
     */
    private boolean postRequest(final Person me, final Caller caller, final String marker,
            final String chatBody, final Function<Locale, String> mailSubject,
            final Function<Locale, String> mailHtml, final boolean dedup) {
        final Instant now = Instant.now();
        final ChatChannel channel = ensureSupportChannel(caller.auditActor());
        if (dedup && hasOpenRequest(channel, me.getId(), marker, now)) {
            return fail(Text.get("feedback.supportChatCommands.alreadyRequested"),
                    Text.get("feedback.supportChatCommands.alreadySentRecently"));
        }
        final ChatRateLimiter.Decision decision = rateLimiter.check(channel, me.getId(), now);
        if (!decision.isAllowed() || decision.getAutoMuteUntil() != null) {
            return fail(Text.get("feedback.supportChatCommands.tooManyRequests"),
                    Text.get("feedback.supportChatCommands.waitBeforeRequesting"));
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
            return fail(Text.get("feedback.shared.notSent"),
                    Text.get("feedback.supportChatCommands.requestNotSaved"));
        }
        if (stored == null) {
            return fail(Text.get("feedback.shared.notSent"),
                    Text.get("feedback.supportChatCommands.requestNotSaved"));
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
    private void notifyAdmins(final ChatMessage stored, final Person requester,
            final Function<Locale, String> subject, final Function<Locale, String> html) {
        final List<Person> admins = listAdmins();
        // Push rides push.enabled alone (the facade checks it), never the mail switch below: an admin who
        // turned support mail off because their phone buzzes anyway must keep the buzz. Its text is still the
        // English subject: push wording is its own piece of work.
        org.paulsens.trip.push.PushNotifications.getInstance().supportRequest(stored, requester, admins,
                subject.apply(Locale.ENGLISH));
        if (!config.getBoolean(KnownSettings.SUPPORT_MAIL_ENABLED)) {
            return;
        }
        TripThreads.startAs(AuditActor.system(), () -> deliver(stored, requester, subject, html, admins));
    }

    private void deliver(final ChatMessage stored, final Person requester, final Function<Locale, String> subject,
            final Function<Locale, String> html, final List<Person> admins) {
        final Map<Locale, Request> byLanguage = new HashMap<>();
        for (final Person admin : admins) {
            if (admin.getId().equals(requester.getId()) || !EmailAddresses.isValid(admin.getEmail())) {
                continue;
            }
            final String dedupe = stored.getId().getValue() + "|" + admin.getId().getValue() + "|EMAIL";
            if (!dao().getCacheClient().tryAcquireLock(
                    CacheKeys.chatNotifySentKey(dedupe), CacheKeys.CHAT_NOTIFY_SENT_TTL)) {
                continue;
            }
            final Request mailed = byLanguage.computeIfAbsent(ReaderLanguage.ofStaff(admin, null),
                    locale -> rendered(subject.apply(locale), html.apply(locale)));
            try {
                final MailAddressCommands addresses = new MailAddressCommands(config);
                mail.send(addresses.from(KnownSettings.SUPPORT_MAIL_FROM), mail.formatEmail(admin), null,
                        addresses.replyTo(KnownSettings.SUPPORT_MAIL_REPLY_TO, null), mailed.subject(),
                        mailed.body(),
                        AuditActor.system());
            } catch (final RuntimeException ex) {
                log.warn("Support-request mail to {} failed", admin.getId(), ex);
            }
        }
    }

    /** One language's copy of a request mail. */
    private record Request(String subject, String body) {
    }

    private Request rendered(final String subject, final String html) {
        // Prefer the runtime-editable MAIL template; the inline HTML built by the request methods is the
        // fallback when the starter was never installed. Rendered once per language -- token-substitution
        // only, no Faces coupling, so this off-request thread is fine.
        //
        // Deliberately the ORG-LESS render: a support request goes to the SITE's support-channel admins and
        // is sent on nobody's behalf, so it resolves the shared copy. (This thread has no host to infer an
        // organization from either -- the rule is that the org comes from the entity, and there is none.)
        final MailCommands.ManagedMail rendered = mail.renderManagedTemplate(
                org.paulsens.trip.content.StarterTemplates.SUPPORT_REQUEST_ID,
                java.util.Map.of("subject", subject, "requestBlock", new MailTemplates.Raw(html)));
        return (rendered == null) ? new Request(subject, html) : new Request(rendered.subject(), rendered.body());
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
        return fail(Text.get("feedback.shared.notAuthorized"),
                Text.get("feedback.supportChatCommands.needsConfigAdmin"));
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
        return PageFeedback.refuse(summary, detail);
    }

    private DAO dao() {
        return DAO.getInstance();
    }
}
