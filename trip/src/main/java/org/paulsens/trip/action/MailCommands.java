package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.StructuredTaskScope;
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
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;
import org.paulsens.trip.cache.Cached;

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
    /** Client-level timeouts replace the per-call orTimeout the CF era needed; a hung send fails, not hangs. */
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    final SesClient client;

    public MailCommands() {
        this(SesClient.builder()
                .region(Region.US_WEST_2)
                // Default chain: finds the ECS task role / instance role in AWS, and ~/.aws [default] on a laptop.
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(o -> o.apiCallAttemptTimeout(ATTEMPT_TIMEOUT).apiCallTimeout(CALL_TIMEOUT))
                .build());
    }

    /** Test seam: the SES path (request shape, audit attribution, error mapping) is untestable without it. */
    MailCommands(final SesClient client) {
        this.client = client;
    }

    /**
     * Sends one email — blocking, on the caller's (virtual) thread — recording {@code who} asked for it.
     *
     * <p>There is deliberately no form without an actor, and the explicit parameter always wins. A null falls
     * back to {@link AuditActor#current()}, which since the virtual-threads migration reads the bound
     * {@code RequestContext} ScopedValue — correct on a request thread and inside {@code StructuredTaskScope}
     * forks — but still finds nobody on a plain spawn or an unbound scheduler thread. That failure mode is why
     * the parameter exists: an anonymous EMAIL record is indistinguishable from a correct one until somebody
     * reads the trail, and exactly that cost this application every EMAIL record between the Phase 10 rework
     * and 2026-07-28.
     *
     * <p>The caller always knows who is asking. When nobody is -- the daily digest, a scheduled job -- the
     * answer is {@link AuditActor#system()}, which says so, rather than an empty actor, which does not.
     *
     * <p>NOTE: see {@code sendTemplate}; that method is generally more useful.
     */
    public SendEmailResponse send(
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
            return SendEmailResponse.builder().build();
        }
        final Collection<String> toAddresses = splitEmail(to);
        if (toAddresses.isEmpty()) {
            // Every recipient was unusable. Calling SES with an empty destination is an error, and there is
            // nothing to retry -- a missing address is a data problem, not a transient failure.
            log.warn("Not sending '{}': no usable recipient address in '{}'", subjectStr, to);
            return SendEmailResponse.builder().build();
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
        // The explicit parameter wins; current() is only the fallback. It resolves correctly here because the
        // send now blocks on the calling thread -- the audit below is written on this same thread, so the
        // capture-before-the-async-boundary dance the CF era needed (and once got wrong, anonymizing every
        // EMAIL record) no longer has a boundary to get wrong.
        final AuditActor actor = (who == null) ? AuditActor.current() : who;
        try {
            final SendEmailResponse response = client.sendEmail(req);
            return logAndReturn(actor, response, to, "Email '" + subjectStr + "' sent. Response: "
                    + response.sdkHttpResponse().statusCode());
        } catch (final RuntimeException ex) {
            // Mapped to null, never rethrown: one refused recipient must not cost the rest of a merge theirs.
            // The digest sender relies on the null to know a send is retry-worthy.
            return logException(actor, to, ex);
        }
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
    public List<SendEmailResponse> sendTemplateFile(
            final String from,
            final Collection<Person> to,
            final String bcc,
            final String replyTo,
            final String subjectStr,
            final String templateName) {
        final String template = elUtil.readFile(EMAIL_TPL_PREFIX + templateName + EMAIL_TPL_SUFFIX);
        if (template == null) {
            // Unchecked wrapper, checked cause: the missing-file signal survives (callers and tests key on
            // FileNotFoundException) without forcing a throws clause onto every EL-driven call site.
            throw new UncheckedIOException(new FileNotFoundException(templateName));
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
     * @return The status of all the email send attempts, in recipient order (null = that send failed).
     */
    public List<SendEmailResponse> sendTemplate(
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
     * <p>The form above resolves it with {@link AuditActor#current()}, which is correct wherever the identity
     * is bound -- an XHTML page on its request thread, or any {@code RequestContext}-scoped virtual thread. A
     * merge started from an unbound spawn or a scheduler must say who asked for it through this parameter.
     *
     * <p>Rendering and sending are split on purpose. The EL evaluation mutates {@code requestScope.to} -- one
     * shared slot -- so each recipient's subject and body are rendered sequentially on the calling thread; only
     * the SES calls fan out, one {@code StructuredTaskScope} fork per recipient, which is the same concurrency
     * the async-client era had. Forks inherit the caller's ScopedValues, so the actor also flows into each send.
     */
    public List<SendEmailResponse> sendTemplate(
            final String from,
            final Collection<Person> to,
            final String bcc,
            final String replyTo,
            final String subjectStr,
            final String template,
            final AuditActor actor) {
        final List<Prepared> prepared = new ArrayList<>();
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
                throw new IllegalStateException("Body and subject must be a String!");
            }
            prepared.add(new Prepared(toEmail, String.valueOf(subject), String.valueOf(body)));
        }
        return fanOutSends(from, bcc, replyTo, actor, prepared);
    }

    /** One rendered, ready-to-send email. */
    private record Prepared(String to, String subject, String body) {
    }

    private List<SendEmailResponse> fanOutSends(
            final String from,
            final String bcc,
            final String replyTo,
            final AuditActor actor,
            final List<Prepared> prepared) {
        try (var scope = StructuredTaskScope.open()) {
            final List<StructuredTaskScope.Subtask<SendEmailResponse>> sends = new ArrayList<>();
            for (final Prepared mail : prepared) {
                sends.add(scope.fork(() -> sendPrepared(from, bcc, replyTo, actor, mail)));
            }
            scope.join();
            // send() maps its own failures to null, so subtasks never fail and get() is always answerable.
            // The nulls are KEPT: the list stays in recipient order and a null marks whose send failed.
            final List<SendEmailResponse> responses = new ArrayList<>();
            for (final StructuredTaskScope.Subtask<SendEmailResponse> sent : sends) {
                responses.add(sent.get());
            }
            return responses;
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending a mail merge", ex);
        }
    }

    private SendEmailResponse sendPrepared(
            final String from,
            final String bcc,
            final String replyTo,
            final AuditActor actor,
            final Prepared mail) {
        return send(from, mail.to(), bcc, replyTo, mail.subject(), mail.body(), actor);
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
        newPeople.forEach(pid -> dao.getPerson(pid, Cached.NO)
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

    /** A rendered managed mail: subject from the template's NAME, body from its body, same tokens. */
    public record ManagedMail(String subject, String body) {
    }

    /**
     * Renders a runtime-editable MAIL template ({@link org.paulsens.trip.model.TemplateKind#MAIL}) by
     * {@code {{token}}} substitution. Values follow the {@code MailTemplates} contract: {@code String}
     * (escaped), {@link org.paulsens.trip.chat.MailTemplates.Raw} (verbatim), {@code Number}/{@code Boolean}.
     * No EL and no Faces-thread coupling -- callable from any thread. Returns null when the template is
     * missing or the wrong kind; callers treat null as "do not send", never as an error that blocks the
     * flow that wanted to send it.
     */
    public ManagedMail renderManagedTemplate(final String templateId, final Map<String, Object> values) {
        final org.paulsens.trip.model.ContentTemplate template =
                dao.getTemplate(templateId, Cached.NO).orElse(null);
        if (template == null || template.getKind() != org.paulsens.trip.model.TemplateKind.MAIL) {
            log.error("No MAIL template '{}' (missing, or not MAIL kind); mail skipped. Run "
                    + "install-starter-templates.sh or create it on the Templates page.", templateId);
            return null;
        }
        return new ManagedMail(substituteTokens(template.getName(), values),
                substituteTokens(template.getBody(), values));
    }

    /**
     * The rendered subject of a managed MAIL template, for on-page previews. "" when the template is
     * missing -- a preview must render something rather than error. Split from {@link #renderManagedBody}
     * (rather than exposing {@link ManagedMail} to pages) because viewScope demands Serializable and JSFT
     * EL should not depend on record-accessor resolution.
     */
    public String renderManagedSubject(final String templateId, final Map<String, Object> values) {
        final ManagedMail rendered = renderManagedTemplate(templateId, values);
        return (rendered == null) ? "" : rendered.subject();
    }

    /** The rendered body of a managed MAIL template, for on-page previews; see {@link #renderManagedSubject}. */
    public String renderManagedBody(final String templateId, final Map<String, Object> values) {
        final ManagedMail rendered = renderManagedTemplate(templateId, values);
        return (rendered == null) ? "" : rendered.body();
    }

    /**
     * Renders and sends a managed MAIL template to one recipient. A missing template or unusable recipient
     * logs and answers false -- registration/approval/support flows must never fail because their mail did.
     */
    public boolean sendManagedTemplate(final String templateId, final Map<String, Object> values,
            final String to, final String from, final String replyTo, final AuditActor actor) {
        return sendManagedTemplate(templateId, values, to, from, replyTo, null, actor);
    }

    /**
     * {@link #sendManagedTemplate(String, Map, String, String, String, AuditActor)} with a bcc (the payment
     * confirmation's org copy). {@code send} always supported bcc; this overload just exposes it.
     */
    public boolean sendManagedTemplate(final String templateId, final Map<String, Object> values,
            final String to, final String from, final String replyTo, final String bcc,
            final AuditActor actor) {
        final ManagedMail rendered = renderManagedTemplate(templateId, values);
        if (rendered == null || to == null || to.isBlank()) {
            return false;
        }
        try {
            send(from, to, bcc, replyTo, rendered.subject(), rendered.body(), actor);
            return true;
        } catch (final RuntimeException ex) {
            log.error("Managed mail '{}' to '{}' failed", templateId, to, ex);
            return false;
        }
    }

    /** {@code {{token}}} substitution with the MailTemplates value semantics (escape unless Raw). */
    private static String substituteTokens(final String template, final Map<String, Object> values) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (values != null) {
            for (final Map.Entry<String, Object> entry : values.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", renderValue(entry.getValue()));
            }
        }
        return result;
    }

    private static String renderValue(final Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof org.paulsens.trip.chat.MailTemplates.Raw
                || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof String text) {
            return org.paulsens.trip.chat.MailTemplates.escape(text);
        }
        // The MailTemplates rule: binding a domain object is a programming error and must be loud.
        throw new IllegalArgumentException("Mail token values must be String/Raw/Number/Boolean, got: "
                + value.getClass().getName());
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
        return Optional.ofNullable(dao.getPersonByEmail(bareEmail(email), Cached.NO))
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
     * @param actor who asked for the mail to be sent -- always passed explicitly, never re-resolved here, so
     *              the attribution cannot depend on which thread happens to run the logging.
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

    /** @param actor as for {@link #logAndReturn} -- passed explicitly, never re-resolved here. */
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
