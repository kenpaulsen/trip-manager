package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.UploadRateLimiter;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * "Report this": a member's report is mailed to the configured recipient and refused for content the
 * reporter cannot see. The trail record is exercised for real (fake audit sink), the mail is captured.
 */
public class ModerationCommandsTest {

    private final String tripId = "mod-" + RandomData.genAlpha(8);
    private Person author;
    private Person reporter;
    private ChatCommands chat;
    private MailCommands mail;
    private PhotoChatCommands photoChat;
    private ModerationCommands moderation;
    private UploadRateLimiter limiter;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        author = saved("Author");
        reporter = saved("Reporter");
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("Moderation test trip")
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(7))
                .people(new ArrayList<>(List.of(author.getId(), reporter.getId())))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        mail = Mockito.mock(MailCommands.class);
        photoChat = Mockito.mock(PhotoChatCommands.class);
        limiter = new UploadRateLimiter(2, 3600);
        moderation = new ModerationCommands(new ConfigCommands(), () -> mail,
                () -> new MailAddressCommands(new ConfigCommands()), () -> chat, () -> photoChat, limiter);
    }

    @Test
    public void aMemberReportsAMessageAndTheRecipientIsMailed() {
        final ChatMessage message = sent("something awful");
        final ModerationCommands.ReportOutcome outcome = moderation.report(
                ModerationCommands.ReportTarget.message(tripId, message.getId()), "Harassment", callerFor(reporter));

        Assert.assertTrue(outcome.ok(), outcome.message());
        final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> bcc = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.any(), to.capture(), bcc.capture(), ArgumentMatchers.isNull(),
                subject.capture(), body.capture(), ArgumentMatchers.any(AuditActor.class));
        Assert.assertEquals(subject.getValue(), "Content report: Moderation test trip");
        Assert.assertTrue(body.getValue().contains("something awful"), "the snippet is in the mail");
        Assert.assertTrue(body.getValue().contains("Harassment"), "the reason is in the mail");
        Assert.assertTrue(body.getValue().contains("Reporter"), "the reporter is named");
        Assert.assertTrue(body.getValue().contains("Author"), "the author is named");
        Assert.assertNotNull(to.getValue(), "the facilitators slot falls back to the Site email");
        Assert.assertEquals(bcc.getValue(), "info@unitetrip.com", "the platform copy rides on the default");
    }

    @Test
    public void aStrangerCannotReportWhatTheyCannotSee() throws IOException {
        final ChatMessage message = sent("private");
        final Person stranger = saved("Stranger");
        final ModerationCommands.ReportOutcome outcome = moderation.report(
                ModerationCommands.ReportTarget.message(tripId, message.getId()), null, callerFor(stranger));
        Assert.assertFalse(outcome.ok());
        Assert.assertEquals(outcome.code(), ModerationCommands.REFUSED_NOT_FOUND);
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void missingAndDeletedMessagesAreNotFound() {
        final ChatMessage message = sent("gone soon");
        Assert.assertTrue(chat.deleteMessage(tripId, message.getId(), callerFor(author)));
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.message(tripId, message.getId()),
                "x", callerFor(reporter)).code(), ModerationCommands.REFUSED_NOT_FOUND);
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.message(tripId,
                ChatMessage.Id.from("no-such-message")), "x", callerFor(reporter)).code(),
                ModerationCommands.REFUSED_NOT_FOUND);
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.message(tripId, null), "x",
                callerFor(reporter)).code(), ModerationCommands.REFUSED_NOT_FOUND);
        Assert.assertEquals(moderation.report(null, "x", callerFor(reporter)).code(),
                ModerationCommands.REFUSED_BAD_REQUEST);
    }

    @Test
    public void anonymousIsForbidden() {
        final ChatMessage message = sent("hello");
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.message(tripId, message.getId()),
                "x", null).code(), ModerationCommands.REFUSED_FORBIDDEN);
        final Caller nobody = new Caller(null, false, AuditActor.from(null), new PrivilegeCommands());
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.message(tripId, message.getId()),
                "x", nobody).code(), ModerationCommands.REFUSED_FORBIDDEN);
    }

    @Test
    public void reportsAreThrottledPerPerson() {
        final ChatMessage message = sent("spam target");
        final ModerationCommands.ReportTarget target = ModerationCommands.ReportTarget.message(tripId,
                message.getId());
        Assert.assertTrue(moderation.report(target, "1", callerFor(reporter)).ok());
        Assert.assertTrue(moderation.report(target, "2", callerFor(reporter)).ok());
        final ModerationCommands.ReportOutcome third = moderation.report(target, "3", callerFor(reporter));
        Assert.assertEquals(third.code(), ModerationCommands.REFUSED_RATE_LIMITED);
        Assert.assertTrue(third.retryAfterSeconds() >= 1);
        Assert.assertTrue(moderation.report(target, "other person", callerFor(author)).ok(),
                "the allowance is per person");
    }

    @Test
    public void aPhotoReportNeedsOnlyReadAccessToThePhoto() {
        final String key = "chat/" + tripId + "/photo.jpg";
        Mockito.when(photoChat.readDenialFor(ArgumentMatchers.eq(key), ArgumentMatchers.any())).thenReturn(null);
        final ModerationCommands.ReportOutcome outcome = moderation.report(
                ModerationCommands.ReportTarget.photo(key), "Inappropriate photo", callerFor(reporter));
        Assert.assertTrue(outcome.ok(), outcome.message());
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.isNull(), ArgumentMatchers.any(), body.capture(),
                ArgumentMatchers.any(AuditActor.class));
        Assert.assertTrue(body.getValue().contains(key));
        Assert.assertTrue(body.getValue().contains("A photo"));

        Mockito.when(photoChat.readDenialFor(ArgumentMatchers.eq(key), ArgumentMatchers.any()))
                .thenReturn("NOT_FOUND");
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.photo(key), "x",
                callerFor(reporter)).code(), ModerationCommands.REFUSED_NOT_FOUND);
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.comment(key,
                ChatMessage.Id.from("c1")), "x", callerFor(reporter)).code(),
                ModerationCommands.REFUSED_NOT_FOUND);
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.photo(" "), "x",
                callerFor(reporter)).code(), ModerationCommands.REFUSED_NOT_FOUND);
    }

    @Test
    public void aCommentThatDoesNotExistIsNotFound() {
        final String key = "chat/" + tripId + "/another.jpg";
        Mockito.when(photoChat.readDenialFor(ArgumentMatchers.eq(key), ArgumentMatchers.any())).thenReturn(null);
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.comment(key,
                ChatMessage.Id.from("missing")), "x", callerFor(reporter)).code(),
                ModerationCommands.REFUSED_NOT_FOUND);
        Assert.assertEquals(moderation.report(ModerationCommands.ReportTarget.comment(key, null), "x",
                callerFor(reporter)).code(), ModerationCommands.REFUSED_NOT_FOUND);
    }

    @Test
    public void aMailFailureDoesNotFailTheReport() {
        final ChatMessage message = sent("still reported");
        Mockito.when(mail.send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class))).thenThrow(new IllegalStateException("SES down"));
        Assert.assertTrue(moderation.report(ModerationCommands.ReportTarget.message(tripId, message.getId()),
                "x".repeat(2000), callerFor(reporter)).ok(), "a long reason is truncated, not refused");
    }

    @Test
    public void escapeIsHtmlSafe() {
        Assert.assertEquals(ModerationCommands.escape("<b>&\"x\""), "&lt;b&gt;&amp;&quot;x&quot;");
        Assert.assertEquals(ModerationCommands.escape(null), "");
    }

    private ChatMessage sent(final String body) {
        final ChatCommands.SendResult result = chat.send(tripId, author.getId(), body, null, null,
                new AuditActor(author.getEmail(), author.getId().getValue()));
        Assert.assertTrue(result.isOk(), result.getMessage());
        return result.getMessageObj();
    }

    private static Person saved(final String first) throws IOException {
        final Person person = Person.builder()
                .first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase() + "." + RandomData.genAlpha(10).toLowerCase(Locale.ROOT) + "@example.com")
                .build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static Caller callerFor(final Person person) {
        return new Caller(person.getId(), false, new AuditActor(person.getEmail(), person.getId().getValue()),
                new PrivilegeCommands());
    }
}
