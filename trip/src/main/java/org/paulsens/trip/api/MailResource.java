package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.MailMerge;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.ReminderCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.TripThreads;

/**
 * Sending email.
 *
 * <p>The only resource on this API whose effect leaves the building and cannot be undone. A wrong trip id
 * writes a row somebody can fix; a wrong recipient list is in people's inboxes. So: {@code emailAdmin} only,
 * recipients resolved and validated before anything is sent, a From the caller's organization may send as,
 * and a hard cap on how many go out per call.
 *
 * <p>Preview is a separate, side-effect-free endpoint precisely so a client can render a merge and show it to
 * a human before the send exists as a possibility.
 */
@Slf4j
@Path("mail")
@TripApi
public class MailResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.MAIL_V1;
    /**
     * A ceiling on one call's recipients.
     *
     * <p>Not a throughput limit -- SES has its own. This is a blast-radius limit: the difference between a
     * mistake that reaches a handful of people and one that reaches the entire database is a single bad
     * recipient list, and the API should make the second one impossible to do by accident.
     */
    private static final int MAX_RECIPIENTS = 200;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** Renders a merge against one person, without sending anything. */
    @POST
    @Path("preview")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response preview(
            @HeaderParam(CSRF_HEADER) final String csrf, final PreviewRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        // emailAdmin is ORG-scoped now (org migration, 2026-08): entry needs it somewhere, and the preview
        // subject must be someone the caller could actually mail (or themselves).
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (!orgs.canMail()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.mail.adminRequired"));
        }
        if (body == null || body.template() == null || body.template().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.mail.templateRequired"));
        }
        final Person person = (body.personId() == null || body.personId().isBlank())
                ? findPerson(personId()) : findPerson(Person.Id.from(body.personId()));
        if (person == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.mail.noPreviewPerson"));
        }
        final java.util.Set<String> allowed = orgs.allowedRecipientEmails();
        if (allowed != null && !person.getId().equals(personId())
                && !OrgCommands.isAllowedEmail(allowed, person.getEmail())) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.mail.personOutsideOrgs"));
        }
        // The merge's {{field}} substitution, the page's own rules: the text is never evaluated (it was EL
        // until medjugorje#79, which let an org-scoped emailAdmin run code here), and a field the caller may
        // not use is refused rather than blanked.
        final MailMerge merge = new MailMerge(this::caller);
        final MailMerge.Rendered rendered = merge.render(person, body.subject(), body.template(), body.tripId());
        if (rendered.problem() != null) {
            return error(422, ApiErrors.VALIDATION_FAILED, rendered.problem());
        }
        return ok(previewOf(body, rendered.preview()));
    }

    private static Map<String, String> previewOf(final PreviewRequest body, final MailMerge.Preview rendered) {
        return (body.subject() == null) ? Map.of("rendered", rendered.body())
                : Map.of("rendered", rendered.body(), "subject", rendered.subject());
    }

    /**
     * Sends one message to an explicit recipient list.
     *
     * <p>The actor is captured here, on the request thread, and passed in. The sends outlive this request on
     * their own virtual threads, where {@code AuditActor.current()} finds nothing -- every EMAIL record went
     * out unattributed for a period because attribution was left to the sending thread.
     */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response send(@HeaderParam(CSRF_HEADER) final String csrf, final SendRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (!orgs.canMail()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.mail.adminRequired"));
        }
        final Response invalid = validate(body);
        if (invalid != null) {
            return invalid;
        }
        // The page's forged-post guard (MailMerge.send), which this door lacked: every organization's
        // domain is verified in the one SES account, so an unchecked From let one tenant mail as another.
        if (!orgs.isMergeFromSendable(body.from())) {
            return error(422, ApiErrors.VALIDATION_FAILED, MailMerge.invalidMergeFrom());
        }
        // The page's allowlist too (review, 2026-11-24): this door mailed the body as typed, a frame or a script
        // included. What the cleaner dropped is said in the answer, never silently (the owner's rule).
        final ReminderCommands.Mailable mailable = MailMerge.mailable(body.body());
        if (mailable.problem() != null) {
            return error(422, ApiErrors.VALIDATION_FAILED, mailable.problem());
        }
        final String mailed = mailable.cleaned().body();
        final MailCommands mail = Beans.get(MailCommands.class);
        final AuditActor actor = actor();
        // emailAdmin is org-scoped: recipients outside the caller's orgs are dropped and counted in
        // "rejected" (null allowed-set == site admin, unrestricted).
        final java.util.Set<String> allowed = orgs.allowedRecipientEmails();
        // The bare addresses that were checked are exactly what is mailed (OrgCommands.boundRecipients).
        final List<String> accepted = OrgCommands.boundRecipients(allowed, body.to()).stream()
                .map(mail::validateEmail)
                .filter(Objects::nonNull)
                .toList();
        if (accepted.isEmpty()) {
            return error(422, ApiErrors.VALIDATION_FAILED, Text.get("api.mail.noUsableRecipients"));
        }
        final String bcc = orgs.boundedBcc(body.bcc());
        for (final String recipient : accepted) {
            // startAs, not a bare spawn: send() is blocking now, and a plain spawn would not inherit the
            // ScopedValue-bound identity -- the actor must ride along explicitly for the EMAIL audit record.
            TripThreads.startAs(actor, () -> sendOne(mail, body, mailed, recipient, bcc, actor));
        }
        // Reports what was ACCEPTED, not what was delivered. The sends run on their own virtual threads and
        // SES decides delivery later; claiming success here would be claiming something this call cannot know.
        return ok(Map.of("accepted", accepted, "rejected", body.to().size() - accepted.size(),
                "formattingRemoved", mailable.cleaned().dropped()));
    }

    private static void sendOne(final MailCommands mail, final SendRequest body, final String mailed,
            final String recipient, final String bcc, final AuditActor actor) {
        mail.send(body.from(), recipient, bcc, body.replyTo(), body.subject(), mailed, actor);
    }

    private Response validate(final SendRequest body) {
        if (body == null || body.to() == null || body.to().isEmpty()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.mail.recipientRequired"));
        }
        if (body.to().size() > MAX_RECIPIENTS) {
            return error(400, ApiErrors.BAD_REQUEST,
                    Text.get("api.mail.tooManyRecipients", String.valueOf(MAX_RECIPIENTS),
                            String.valueOf(body.to().size())));
        }
        if (body.subject() == null || body.subject().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.mail.subjectRequired"));
        }
        if (body.body() == null || body.body().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.shared.bodyRequired"));
        }
        return null;
    }

    /** One message to an explicit list of addresses. */
    public record SendRequest(
            String from, List<String> to, String bcc, String replyTo, String subject, String body) {
    }

    /**
     * A merge body ({@code template}) rendered against one person, for showing a human before anything is sent.
     * {@code subject} (optional) is rendered too; {@code tripId} (optional) is the trip its trip fields are about.
     */
    public record PreviewRequest(String personId, String template, String subject, String tripId) {
        /** The original two-field request: body only, no trip. */
        public PreviewRequest(final String personId, final String template) {
            this(personId, template, null, null);
        }
    }
}
