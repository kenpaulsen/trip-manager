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
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;

/**
 * Sending email.
 *
 * <p>The only resource on this API whose effect leaves the building and cannot be undone. A wrong trip id
 * writes a row somebody can fix; a wrong recipient list is in people's inboxes. So: {@code emailAdmin} only,
 * recipients resolved and validated before anything is sent, and a hard cap on how many go out per call.
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

    /** Renders a template against one person, without sending anything. */
    @POST
    @Path("preview")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response preview(
            @HeaderParam(CSRF_HEADER) final String csrf, final PreviewRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.EMAIL_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Email administration required.");
        }
        if (body == null || body.template() == null || body.template().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "A template is required.");
        }
        final Person person = (body.personId() == null || body.personId().isBlank())
                ? findPerson(personId()) : findPerson(Person.Id.from(body.personId()));
        if (person == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such person to preview against.");
        }
        // renderTemplate, not previewTemplate: the latter evaluates through the Faces expression context and
        // off a Faces thread returns the literal text "null" for the whole template -- a preview that looks
        // like it worked. A bad expression throws here by design, so it is reported rather than swallowed.
        try {
            return ok(Map.of("rendered",
                    Beans.get(MailCommands.class).renderTemplate(person, body.template())));
        } catch (final RuntimeException ex) {
            log.debug("Template preview failed", ex);
            return error(422, ApiErrors.VALIDATION_FAILED, "Template could not be rendered: " + ex.getMessage());
        }
    }

    /**
     * Sends one message to an explicit recipient list.
     *
     * <p>The actor is captured here, on the request thread, and passed in. {@code MailCommands.send} writes its
     * audit record inside the SES client's completion callback, where {@code AuditActor.current()} finds
     * nothing -- every EMAIL record went out unattributed for a period because of exactly that.
     */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response send(@HeaderParam(CSRF_HEADER) final String csrf, final SendRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.EMAIL_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Email administration required.");
        }
        final Response invalid = validate(body);
        if (invalid != null) {
            return invalid;
        }
        final MailCommands mail = Beans.get(MailCommands.class);
        final AuditActor actor = actor();
        final List<String> accepted = body.to().stream()
                .map(mail::validateEmail)
                .filter(Objects::nonNull)
                .toList();
        if (accepted.isEmpty()) {
            return error(422, ApiErrors.VALIDATION_FAILED, "No usable recipient addresses.");
        }
        for (final String recipient : accepted) {
            mail.send(body.from(), recipient, body.bcc(), body.replyTo(), body.subject(), body.body(), actor);
        }
        // Reports what was ACCEPTED, not what was delivered. The sends are asynchronous and SES decides
        // delivery later; claiming success here would be claiming something this call cannot know.
        return ok(Map.of("accepted", accepted, "rejected", body.to().size() - accepted.size()));
    }

    private Response validate(final SendRequest body) {
        if (body == null || body.to() == null || body.to().isEmpty()) {
            return error(400, ApiErrors.BAD_REQUEST, "At least one recipient is required.");
        }
        if (body.to().size() > MAX_RECIPIENTS) {
            return error(400, ApiErrors.BAD_REQUEST,
                    "At most " + MAX_RECIPIENTS + " recipients per call; got " + body.to().size() + ".");
        }
        if (body.subject() == null || body.subject().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "A subject is required.");
        }
        if (body.body() == null || body.body().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "A body is required.");
        }
        return null;
    }

    /** One message to an explicit list of addresses. */
    public record SendRequest(
            String from, List<String> to, String bcc, String replyTo, String subject, String body) {
    }

    /** A template rendered against one person, for showing a human before anything is sent. */
    public record PreviewRequest(String personId, String template) {
    }
}
