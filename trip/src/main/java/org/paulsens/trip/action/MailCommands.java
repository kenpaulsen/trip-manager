package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
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
@Named
@ApplicationScoped
public class MailCommands {
    final SesAsyncClient client = SesAsyncClient.builder()
            .region(Region.US_WEST_2)
            .credentialsProvider(ProfileCredentialsProvider.builder().build())
            .build();

    public CompletableFuture<SendEmailResponse> send(
            final String from, final String to, final String replyTo, final String subjectStr, final String bodyStr) {
        final Content subject = Content.builder()
                .data(subjectStr)
                .build();
        final Body body = Body.builder()
                //.html(Content.builder().data(bodyHtml).build())
                .text(Content.builder().data(bodyStr).build())
                .build();
        final SendEmailRequest req = SendEmailRequest.builder()
                .source(from)
                .destination(Destination.builder().toAddresses(to).build())
                .message(Message.builder().body(body).subject(subject).build())
                .replyToAddresses(replyTo)
                .returnPath(replyTo)
                .build();
        return client.sendEmail(req)
                .thenApply(r -> logAndReturn(r, to, "Email sent to: " + to))
                .exceptionally(ex -> logException(to, ex));
    }

    private <T> T logAndReturn(final T response, final String user, final String msg) {
        Audit.log(user, "EMAIL", msg);
        return response;
    }

    private <T> T logException(final String user, final Throwable ex) {
        Audit.log(user, "EMAIL", "Unable to send email: " + ex.getMessage());
        log.warn("Unable to send email to " + user, ex);
        return null;
    }
}
