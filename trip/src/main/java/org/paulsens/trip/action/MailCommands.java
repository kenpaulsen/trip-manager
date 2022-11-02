package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
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
    final DynamoUtils dynamoUtils = DynamoUtils.getInstance();
    final ELUtil elUtil = ELUtil.getInstance();
    private static final String EMAIL_TPL_PREFIX = "mailTemplates/";
    private static final String EMAIL_TPL_SUFFIX = ".tpl";

    final SesAsyncClient client = SesAsyncClient.builder()
            .region(Region.US_WEST_2)
            .credentialsProvider(ProfileCredentialsProvider.builder().build())
            .build();

    // NOTE: See "sendTemplate" that method is generally more useful
    public CompletableFuture<SendEmailResponse> send(final String from, final String to, final String bcc,
            final String replyTo, final String subjectStr, final String bodyStr) {
        final Content subject = Content.builder()
                .data(subjectStr)
                .build();
        final Body body = Body.builder()
                .html(Content.builder().data(bodyStr).build())
                //.text(Content.builder().data(bodyStr).build())
                .build();
        final Destination dest = Destination.builder()
                .toAddresses(splitEmail(to))
                .bccAddresses(splitEmail(bcc))
                .build();
        final SendEmailRequest req = SendEmailRequest.builder()
                .source(from.trim())
                .destination(dest)
                .message(Message.builder().body(body).subject(subject).build())
                .replyToAddresses(replyTo)
                .returnPath(replyTo)
                .build();
// Can use this to disable email for testing
        //return CompletableFuture.completedFuture(SendEmailResponse.builder().build())
        return client.sendEmail(req)
                .thenApply(r -> logAndReturn(r, to,
                        "Email sent to: '" + to + "' with message id: '" + r.messageId() + "'."))
                .exceptionally(ex -> logException(to, ex));
    }

    // Note: Not sure if I'm going to support pre-defined templates... even if I do, I think it may be better to
    // Note: "load" the template into the online editor, and then call the #sendTemplate method instead.
    private CompletableFuture<List<SendEmailResponse>> sendTemplateFile(final String from, final Collection<Person> to,
            final String bcc, final String replyTo, final String subjectStr, final String templateName) {
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
     * @param template      Template to use. Note: {@link #EMAIL_TPL_PREFIX} and {@link #EMAIL_TPL_SUFFIX} are added.
     *
     * @return A CompletableFuture that completes w/ the status of all the email send attempts.
     */
    public CompletableFuture<List<SendEmailResponse>> sendTemplate(final String from, final Collection<Person> to,
            final String bcc, final String replyTo, final String subjectStr, final String template) {
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
                    send(from, toEmail, bcc, replyTo, String.valueOf(subject), String.valueOf(body)),
                    this::combineNewResp);
        }
        return result;
    }

    public String previewTemplate(final Person to, final String template) {
        final ELUtil elUtil = ELUtil.getInstance();
        elUtil.setELValue("#{requestScope.to}", to);
        return String.valueOf(elUtil.eval(template));
    }

    public Collection<String> addRecipients(final Collection<String> current, final Collection<Person.Id> newPeople) {
        newPeople.forEach(pid -> dynamoUtils.getPerson(pid).join()
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
        return findPerson(person -> email.equals(formatEmail(person)))
                .thenApply(maybePerson -> maybePerson.or(
                        () -> Optional.ofNullable(dynamoUtils.getPersonByEmail(email).join())))
                .thenApply(maybePerson -> maybePerson.orElse(Person.builder().email(email).build()))
                .join();
    }

    public String validateEmail(final String emailToTest) {
        if (emailToTest == null) {
            return null;
        }
        final String email = emailToTest.trim();
        final String result;
        final int atLoc = email.indexOf('@');
        if (atLoc < 1) {
            result = null;
        } else if (email.indexOf('.', atLoc + 1) < (atLoc + 2)) {
            result = null;
        } else if (email.indexOf(' ') != -1) {
            result = null;
        } else {
            result = email;
        }
        return result;
    }

    private CompletableFuture<Optional<Person>> findPerson(final Predicate<Person> checkFunc) {
        return dynamoUtils.getPeople()
                .thenApply(people -> people.stream().filter(checkFunc).findAny());
    }

    Collection<String> splitEmail(final String emails) {
        return (emails == null) ? List.of() :
                Arrays.stream(emails.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private <T> T logAndReturn(final T response, final String user, final String msg) {
        Audit.log(user, "EMAIL", msg);
        return response;
    }

    private List<SendEmailResponse> combineNewResp(final List<SendEmailResponse> respList, SendEmailResponse newResp) {
        respList.add(newResp);
        return respList;
    }

    private <T> T logException(final String user, final Throwable ex) {
        Audit.log(user, "EMAIL", "Unable to send email: " + ex.getMessage());
        log.warn("Unable to send email to " + user, ex);
        return null;
    }
}
