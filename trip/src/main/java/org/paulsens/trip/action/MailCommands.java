package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.EmailAddresses;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesAsyncClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * This class contains methods that perform mail actions.
 */
@SuppressWarnings("unused")
@Slf4j
@Named("mail")
@ApplicationScoped
public class MailCommands {
    final DAO dao = DAO.getInstance();
    final ELUtil elUtil = ELUtil.getInstance();
    private static final String EMAIL_TPL_PREFIX = "mailTemplates/";
    private static final String EMAIL_TPL_SUFFIX = ".tpl";

    final SesAsyncClient client = SesAsyncClient.builder()
            .region(Region.US_WEST_2)
            // Default chain: finds the ECS task role / instance role in AWS, and ~/.aws [default] on a laptop.
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();

    /**
     * Sends one email, recording {@code who} asked for it.
     *
     * <p>There is deliberately no form without an actor. One resolving it through {@link AuditActor#current()}
     * would read {@code FacesContext} -- a ThreadLocal -- and find nobody whenever the caller is a JAX-RS
     * resource, a scheduler, or anything on a pool thread. The record still gets written; it just names no
     * sender, which is indistinguishable from a correct record until somebody tries to read the trail. That
     * exact bug already cost this application every EMAIL record between the Phase 10 rework and 2026-07-28.
     *
     * <p>The caller always knows who is asking. When nobody is -- the daily digest, a scheduled job -- the
     * answer is {@link AuditActor#system()}, which says so, rather than an empty actor, which does not.
     *
     * <p>NOTE: see {@code sendTemplate}; that method is generally more useful.
     */
    public CompletableFuture<SendEmailResponse> send(
            final String from,
            final String to,
            final String bcc,
            final String replyTo,
            final String subjectStr,
            final String bodyStr,
            final AuditActor who) {
        final Content subject = Content.builder()
                .data(subjectStr)
                .build();
        final Body body = Body.builder()
                .html(Content.builder().data(bodyStr).build())
                //.text(Content.builder().data(bodyStr).build())
                .build();
        // LOCAL MODE SENDS NOTHING. Every send funnels through here, so this one guard covers mail merges,
        // chat mentions, the digest, registration notifications -- everything.
        //
        // It is here because it was missing: a webtest that registers a fake pilgrim delivered a real
        // "just registered for the 'Spring Demo Trip' trip" email to a real inbox, from the laptop's own AWS
        // credentials. Nothing about SES is faked by local mode on its own -- the DAO and the cache have
        // stand-ins, SES did not, so it was the one dependency that stayed live while everything around it
        // went fake. Test data is fake; the addresses in it need not be.
        //
        // Keyed on LocalMode, which defaults to PRODUCTION and is never inferred, so the failure direction is
        // "a laptop sends mail it should not" rather than "the deployment silently stops sending". Logged at
        // INFO with the recipients so local runs can still show what would have gone out.
        if (FakeData.isLocal()) {
            log.info("LOCAL MODE: not sending '{}' to '{}' (bcc '{}')", subjectStr, to, bcc);
            return CompletableFuture.completedFuture(SendEmailResponse.builder().build());
        }
        final Collection<String> toAddresses = splitEmail(to);
        if (toAddresses.isEmpty()) {
            // Every recipient was unusable. Calling SES with an empty destination is an error, and there is
            // nothing to retry -- a missing address is a data problem, not a transient failure.
            log.warn("Not sending '{}': no usable recipient address in '{}'", subjectStr, to);
            return CompletableFuture.completedFuture(SendEmailResponse.builder().build());
        }
        final Destination dest = Destination.builder()
                .toAddresses(toAddresses)
                .bccAddresses(splitEmail(bcc))
                .build();
        final SendEmailRequest req = SendEmailRequest.builder()
                .source(from.trim())
                .destination(dest)
                .message(Message.builder().body(body).subject(subject).build())
                .replyToAddresses(replyTo)
                .returnPath(replyTo)
                .build();
        // Resolve the actor HERE, on the request thread, and carry it across the async boundary.
        // FacesContext is a ThreadLocal and the callbacks below run on the SDK's completion thread, where
        // AuditActor.current() finds nothing -- so every EMAIL record was written with no actor at all. It
        // looked fine in review: the call was AuditActor.current(), same as everywhere else. It just was not
        // on the thread that has a request.
        final AuditActor actor = (who == null) ? AuditActor.current() : who;

        return client.sendEmail(req)
                .thenApply(r -> logAndReturn(actor, r, to, "Email '" + subjectStr + "' sent. Response: "
                                                + r.sdkHttpResponse().statusCode()))
                .exceptionally(ex -> logException(actor, to, ex));
    }

    /**
     * Send an email using a template file.
     * See {@link #sendTemplate(String, Collection<Person>, String, String, String, String)}
     *
     * @param from          From address to show, example: {@code Business Name &lt;no-reply@nowhere.com&gt;}
     * @param to            The collection of people to send email to.
     * @param bcc           String of comma-separated people to bcc *on every email*.
     * @param replyTo       The email address to accept replies, example: {@code real-email@real-place.com}
     * @param subjectStr    The email subject.
     * @param template      Template to use. Note: {@link #EMAIL_TPL_PREFIX} and {@link #EMAIL_TPL_SUFFIX} are added.
     */
    public CompletableFuture<List<SendEmailResponse>> sendTemplateFile(
            final String from,
            final Collection<Person> to,
            final String bcc,
            final String replyTo,
            final String subjectStr,
            final String templateName) {
        final String template = elUtil.readFile(EMAIL_TPL_PREFIX + templateName + EMAIL_TPL_SUFFIX);
        if (template == null) {
            return CompletableFuture.failedFuture(new FileNotFoundException(templateName));
        }
        return sendTemplate(from, to, bcc, replyTo, subjectStr, template);
    }

    /**
     * Attempts to load a predefined email template and "mail-merge" the given people. Each {@link Person}, will be
     * set as #{requestScope.to} so you can use {@code EL} to customize the email. The people in the "to" field will
     * not be visible to anyone else in the "to" field because each person gets a personalized email.
     *
     * @param from          From address to show, example: {@code Business Name &lt;no-reply@nowhere.com&gt;}
     * @param to            The collection of people to send email to.
     * @param bcc           String of comma-separated people to bcc *on every email*.
     * @param replyTo       The email address to accept replies, example: {@code real-email@real-place.com}
     * @param subjectStr    The email subject.
     * @param template      Template to use.
     *
     * @return A CompletableFuture that completes w/ the status of all the email send attempts.
     */
    public CompletableFuture<List<SendEmailResponse>> sendTemplate(
            final String from,
            final Collection<Person> to,
            final String bcc,
            final String replyTo,
            final String subjectStr,
            final String template) {
        return sendTemplate(from, to, bcc, replyTo, subjectStr, template, AuditActor.current());
    }

    /**
     * The same merge, with the actor supplied.
     *
     * <p>The form above resolves it with {@link AuditActor#current()}. That is correct where it is called
     * from -- an XHTML page, on the request thread, before any of the sends below go asynchronous -- and it is
     * the reason this one exists: a merge started from anywhere else must say who asked for it, because by the
     * time each individual send completes there is no request left to ask.
     */
    public CompletableFuture<List<SendEmailResponse>> sendTemplate(
            final String from,
            final Collection<Person> to,
            final String bcc,
            final String replyTo,
            final String subjectStr,
            final String template,
            final AuditActor actor) {
        CompletableFuture<List<SendEmailResponse>> result = CompletableFuture.completedFuture(new ArrayList<>());
        for (final Person person : to) {
            final String toEmail = formatEmail(person);
            if (toEmail == null) {
                log.warn("Invalid email address: '" + person.getEmail() + "'");
                continue;
            }
            elUtil.setELValue("#{requestScope.to}", person);
            final Object subject = elUtil.eval(subjectStr);
            final Object body = elUtil.eval(template);
            if (!(body instanceof String) || !(subject instanceof String)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Body and subject must be a String!"));
            }
            result = result.thenCombine(
                    send(from, toEmail, bcc, replyTo, String.valueOf(subject), String.valueOf(body), actor),
                    this::combineNewResp);
        }
        return result;
    }

    public String previewTemplate(final Person to, final String template) {
        final ELUtil elUtil = ELUtil.getInstance();
        elUtil.setELValue("#{requestScope.to}", to);
        return String.valueOf(elUtil.eval(template));
    }

    /**
     * Renders a template for one person without needing a {@code FacesContext}.
     *
     * <p>{@link #previewTemplate} evaluates through {@code ELUtil.eval}, which resolves against the Faces
     * expression context. Off a Faces thread that yields null for the whole template and
     * {@code String.valueOf} turns it into the literal text "null" -- so the preview does not fail, it
     * cheerfully returns something that looks like a rendered result and is not one.
     *
     * <p>Uses {@code renderWithoutJsf}, which was written for exactly this and is what the mail templates
     * already go through. Note its contract: a typo in a template throws {@code PropertyNotFoundException}
     * rather than rendering blank, deliberately, so callers must contain that per recipient.
     *
     * <p>The variable is named {@code to} to match what the templates already reference.
     */
    public String renderTemplate(final Person to, final String template) {
        return ELUtil.getInstance().renderWithoutJsf(template, Map.of("to", to));
    }

    public Collection<String> addRecipients(final Collection<String> current, final Collection<Person.Id> newPeople) {
        newPeople.forEach(pid -> dao.getPerson(pid).join()
                .map(this::formatEmail)
                .map(current::add));
        return current;
    }

    public Collection<String> addRecipientsByPerson(
            final Collection<String> current, final Collection<Person> newPeople) {
        newPeople.forEach(per -> formatEmailMaybe(per).map(current::add));
        return current;
    }

    private Optional<String> formatEmailMaybe(final Person person) {
        return Optional.ofNullable(formatEmail(person));
    }

    public String formatEmail(final Person person) {
        final String email = validateEmail(person.getEmail());
        if (email == null) {
            return null;
        }
        final String prefName = Optional.ofNullable(person.getPreferredName()).orElse("");
        final String lastName = Optional.ofNullable(person.getLast()).orElse("");
        return prefName + ' ' + lastName + " <" + email + ">";
    }

    public List<Person> emailsToPeople(final List<String> email) {
        return email.stream().map(this::findPersonByEmail).toList();
    }

    public Person findPersonByEmail(final String email) {
        if (email == null) {
            return null;
        }
        // Accepts either a bare address or the "Pref Last <email>" form produced by formatEmail().
        return Optional.ofNullable(dao.getPersonByEmail(bareEmail(email)).join())
                .orElseGet(() -> Person.builder().email(email).build());
    }

    /** Extracts the address from a "Name &lt;email&gt;" recipient string (returns bare addresses unchanged). */
    static String bareEmail(final String recipient) {
        final int lt = recipient.indexOf('<');
        final int gt = recipient.lastIndexOf('>');
        return (lt >= 0 && gt > lt) ? recipient.substring(lt + 1, gt).trim() : recipient.trim();
    }

    /** @return the trimmed address, or null if it is not a usable email address. */
    public String validateEmail(final String emailToTest) {
        return EmailAddresses.normalize(emailToTest);
    }

    /**
     * Splits a comma-separated recipient list, **dropping anything that is not a usable address**.
     *
     * <p>Some people have no email address and the field holds a bare name ({@code joe.smith}). SES rejects the
     * whole request when any destination is malformed, so one such person in a mail merge would silently cost
     * every recipient after them their mail. Validation runs on the bare address so the
     * {@code "Pref Last <addr>"} form {@link #formatEmail} produces still passes.
     */
    Collection<String> splitEmail(final String emails) {
        if (emails == null) {
            return List.of();
        }
        return Arrays.stream(emails.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(this::isSendable)
                .toList();
    }

    private boolean isSendable(final String recipient) {
        if (EmailAddresses.isValid(bareEmail(recipient))) {
            return true;
        }
        log.warn("Dropping unusable email recipient: '{}'", recipient);
        return false;
    }

    /**
     * @param actor who asked for the mail to be sent, captured on the request thread by the caller. It CANNOT
     *              be resolved here: this runs on the SDK's completion thread, where there is no FacesContext.
     * @param to the RECIPIENT, which is the target of this action -- not the actor. The old record stored the
     *           recipient in the user field, so a mail merge appeared to have been sent by each of the hundreds
     *           of people who received it.
     */
    <T> T logAndReturn(final AuditActor actor, final T response, final String to, final String msg) {
        Audit.builder(AuditAction.EMAIL, AuditOutcome.SUCCESS)
                .actor(actor)
                .targetPerson(to, null)
                .message(msg)
                .log();
        return response;
    }

    private List<SendEmailResponse> combineNewResp(final List<SendEmailResponse> respList, SendEmailResponse newResp) {
        respList.add(newResp);
        return respList;
    }

    /** @param actor as for {@link #logAndReturn} -- captured by the caller, not resolvable on this thread. */
    <T> T logException(final AuditActor actor, final String to, final Throwable ex) {
        Audit.builder(AuditAction.EMAIL, AuditOutcome.FAILURE)
                .actor(actor)
                .targetPerson(to, null)
                .message("Unable to send email: " + ex.getMessage())
                .log();
        log.warn("Unable to send email to " + to, ex);
        return null;
    }
}
