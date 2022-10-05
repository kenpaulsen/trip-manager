package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
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
    final ELUtil elUtil = ELUtil.getInstance();
    private static final String EMAIL_TPL_PREFIX = "mailTemplates/";
    private static final String EMAIL_TPL_SUFFIX = ".tpl";

    final SesAsyncClient client = SesAsyncClient.builder()
            .region(Region.US_WEST_2)
            .credentialsProvider(ProfileCredentialsProvider.builder().build())
            .build();

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

    Collection<String> splitEmail(final String emails) {
        return (emails == null) ? List.of() :
                Arrays.stream(emails.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private <T> T logAndReturn(final T response, final String user, final String msg) {
        Audit.log(user, "EMAIL", msg);
        return response;
    }

    /**
     * Attempts to load a predefined email template and "mail-merge" the given people. Each {@link Person}, will be
     * set as #{requestScope.mailTo} so you can use {@code EL} to customize the email.
     *
     * @param from          From address to show, example: {@code Business Name &lt;no-reply@nowhere.com&gt;}
     * @param to            The collection of people to send email to.
     * @param bcc           String of comma-separated people to bcc on every email.
     * @param replyTo       The email address to accept replies, example: {@code real-email@real-place.com}
     * @param subjectStr    The email subject.
     * @param templateName  Template to use. Note: {@link #EMAIL_TPL_PREFIX} and {@link #EMAIL_TPL_SUFFIX} are added.
     *
     * @return A CompletableFuture that completes w/ the status of all the email send attempts.
     */
    public CompletableFuture<List<SendEmailResponse>> sendTemplateFile(final String from, final Collection<Person> to,
            final String bcc, final String replyTo, final String subjectStr, final String templateName) {
        final String template = elUtil.readFile(EMAIL_TPL_PREFIX + templateName + EMAIL_TPL_SUFFIX);
        if (template == null) {
            return CompletableFuture.failedFuture(new FileNotFoundException(templateName));
        }
        return sendTemplate(from, to, bcc, replyTo, subjectStr, template);
    }

    // Note: bcc will be bcc on every email, it can also contain comma separated list of bcc email addresses.
    public CompletableFuture<List<SendEmailResponse>> sendTemplate(final String from, final Collection<Person> to,
            final String bcc, final String replyTo, final String subjectStr, final String template) {
        CompletableFuture<List<SendEmailResponse>> result = CompletableFuture.completedFuture(new ArrayList<>());
        for (final Person person : to) {
            final String toEmail = validateEmail(person.getEmail());
            if (toEmail == null) {
                continue;
            }
            elUtil.setELValue("#{requestScope.mailTo}", person);
            final Object body = elUtil.eval(template);
            if (!(body instanceof String)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Body not a String! "));
            }
            result = result.thenCombine(
                    send(from, toEmail, bcc, replyTo, subjectStr, String.valueOf(body)),
                    this::combineNewResp);
        }
        return result;
    }

    public String previewTemplate(final Person to, final String template) {
        final ELUtil elUtil = ELUtil.getInstance();
        elUtil.setELValue("#{requestScope.mailTo}", to);
        return String.valueOf(elUtil.eval(template));
    }

    private List<SendEmailResponse> combineNewResp(final List<SendEmailResponse> respList, SendEmailResponse newResp) {
        respList.add(newResp);
        return respList;
    }

    private String validateEmail(final String emailToTest) {
        if (emailToTest == null) {
            return null;
        }
        final String email = emailToTest.trim();
        final String result;
        final int atLoc = email.indexOf('@');
        if (atLoc < 1) {
            log.warn("Invalid email address: '" + email + "'");
            result = null;
        } else if (email.indexOf('.', atLoc + 1) < (atLoc + 2)) {
            log.warn("Invalid email address: '" + email + "'");
            result = null;
        } else if (email.indexOf(' ') != -1) {
            log.warn("Invalid email address: '" + email + "'");
            result = null;
        } else {
            result = email;
        }
        return result;
    }

    private <T> T logException(final String user, final Throwable ex) {
        Audit.log(user, "EMAIL", "Unable to send email: " + ex.getMessage());
        log.warn("Unable to send email to " + user, ex);
        return null;
    }
}
