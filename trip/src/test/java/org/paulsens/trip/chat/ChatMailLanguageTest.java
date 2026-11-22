package org.paulsens.trip.chat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * Chat mail is written in its RECIPIENT's language ({@code docs/i18n.md}): the immediate notifications
 * ({@link EmailChatNotifier}) and the daily digest ({@link ChatDigestSender}).
 *
 * <p>Two halves. The words moved out of the {@code .tpl} files into the {@code mail} bundle, so the English
 * half holds each mail to exactly what the old templates rendered, byte for byte: the templates' text is kept
 * here as it was, because a suite that only checks fragments would not notice a moved space. The other half
 * sends to a reader whose language resolves to Spanish or Croatian on Beta Corp's seeded site, the one that
 * offers all three.
 */
public class ChatMailLanguageTest {

    private static final String BASE = "https://example.org";
    private static final String CHAT_URL = BASE + "/trip/chat.jsf?trip=trip-1";
    /** What every one of the old templates ended with, after its opening sentence. */
    private static final String OLD_TAIL = "\n\n%s\n\n<p><a href=\"%s\">%s</a></p>\n\n"
            + "<hr style=\"border:none;border-top:1px solid #ddd;margin-top:1.5rem;\" />\n"
            + "<p style=\"font-size:0.85em;color:#666;\">%s</p>\n";
    private static final String ON_BEHALF_FOOTER = "You are receiving this as Lucy&#39;s family manager: Lucy has no "
            + "email address of their own. Mention emails can be turned off on the trip&#39;s chat page.";

    private MailCommands mail;
    private CacheClient cache;
    private ConfigCommands config;
    private EmailChatNotifier notifier;

    @BeforeClass
    public void seed() {
        // The DAO first: it is what seeds the fixtures the first time, and addFakeData reads them.
        DAO.getInstance();
        FakeData.addFakeData();
    }

    @BeforeMethod
    public void setUp() {
        mail = Mockito.mock(MailCommands.class);
        cache = Mockito.mock(CacheClient.class);
        config = Mockito.mock(ConfigCommands.class);
        notifier = new EmailChatNotifier(mail, cache, config);
        Mockito.when(cache.tryAcquireLock(ArgumentMatchers.anyString(), ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        Mockito.when(cache.getValue(ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        Mockito.when(cache.putValue(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(config.getString(KnownSettings.CHAT_MAIL_BASE_URL)).thenReturn(BASE);
        Mockito.when(mail.formatEmail(ArgumentMatchers.any()))
                .thenAnswer(inv -> ((Person) inv.getArgument(0)).getEmail());
        Mockito.when(mail.send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-lang").build());
    }

    // --- English: exactly what the templates said before their words moved to the bundle ---

    @Test
    public void anEnglishMentionAndReplyAreByteForByteWhatTheTemplatesRendered() throws IOException {
        final Person reader = savedPerson("Rita", "rita");
        final String quote = "<blockquote style=\"border-left:3px solid #6c8;padding-left:0.75rem;color:#444;"
                + "margin:1rem 0;\">" + ChatBodyHtml.html("bus at 7") + "</blockquote>";

        final String[] mention = send(notification(ChatChannel.Id.forTrip("trip-1"), "trip-1", "Rome & Assisi",
                "Ann O'Hara", reader, "bus at 7", ChatNotification.Reason.MENTION), reader);
        Assert.assertEquals(mention[0], "Ann O'Hara mentioned you in the Rome & Assisi chat");
        Assert.assertEquals(mention[1], "<p><b>Ann O&#39;Hara</b> mentioned you in the <a href=\"" + CHAT_URL
                + "\">Rome &amp; Assisi chat</a>.</p>" + String.format(OLD_TAIL, quote, CHAT_URL, "Open the chat",
                        "You are receiving this because you turned on mention notifications for this trip&#39;s "
                                + "chat. You can turn them off on the chat page."));

        final String[] reply = send(notification(ChatChannel.Id.forTrip("trip-1"), "trip-1", "Rome 2027",
                "Ann", reader, null, ChatNotification.Reason.REPLY), reader);
        Assert.assertEquals(reply[0], "Ann replied to you in the Rome 2027 chat");
        Assert.assertEquals(reply[1], "<p><b>Ann</b> replied to your message in the <a href=\"" + CHAT_URL
                + "\">Rome 2027 chat</a>.</p>" + String.format(OLD_TAIL, "", CHAT_URL, "Open the chat",
                        "You are receiving this because someone replied to a message you wrote. Replies follow "
                                + "your mention notification setting, which can be turned off on the chat page."));

        final String[] bare = send(notification(ChatChannel.Id.forTrip("trip-1"), "trip-1", null, null, reader,
                null, ChatNotification.Reason.ADMIN_ANNOUNCEMENT), reader);
        Assert.assertEquals(bare[0], "Announcement in the your trip chat");
        Assert.assertTrue(bare[1].startsWith("<p><b>Someone</b> mentioned you in the <a href=\"" + CHAT_URL
                + "\">trip chat</a>.</p>\n"), bare[1]);
    }

    @Test
    public void anEnglishPhotoMailIsByteForByteWhatTheTemplatesRendered() throws IOException {
        final Person reader = savedPerson("Rita", "rita");
        final ChatChannel.Id photo = ChatChannel.Id.forPhoto("chat/trip-1/x.jpg");
        final String url = BASE + "/trip/tripMedia.jsf?trip=trip-1&amp;photo=chat%2Ftrip-1%2Fx.jpg";

        final String[] comment = send(notification(photo, "trip-1", "Rome 2027", "Ann", reader, null,
                ChatNotification.Reason.PHOTO_COMMENT), reader);
        Assert.assertEquals(comment[0], "Ann commented on your photo from the Rome 2027 trip");
        Assert.assertEquals(comment[1], "<p><b>Ann</b> commented on <a href=\"" + url
                + "\">your photo from the Rome 2027 trip</a>.</p>" + String.format(OLD_TAIL, "", url,
                        "View the photo", "You are receiving this because someone commented on a photo you "
                                + "uploaded. These follow your mention notification setting, which can be turned "
                                + "off on the trip&#39;s chat page."));

        final String[] mention = send(notification(photo, "trip-1", "Rome 2027", "Ann", reader, null,
                ChatNotification.Reason.MENTION), reader);
        Assert.assertEquals(mention[0], "Ann mentioned you on a photo from the Rome 2027 trip");
        Assert.assertEquals(mention[1], "<p><b>Ann</b> mentioned you in a comment on a <a href=\"" + url
                + "\">photo from the Rome 2027 trip</a>.</p>" + String.format(OLD_TAIL, "", url, "View the photo",
                        "You are receiving this because someone mentioned you by name in a photo comment. "
                                + "Mention emails can be turned off on the trip&#39;s chat page."));
    }

    /** The manager's copy names the child; the possessive was an escaped value, so its apostrophe is an entity. */
    @Test
    public void anEnglishMailToAManagerIsByteForByteWhatTheTemplatesRendered() throws IOException {
        final Person mom = savedPerson("Mom", "mom");
        final Person lucy = childOf(mom);
        final ChatChannel.Id photo = ChatChannel.Id.forPhoto("chat/trip-1/x.jpg");
        final String photoUrl = BASE + "/trip/tripMedia.jsf?trip=trip-1&amp;photo=chat%2Ftrip-1%2Fx.jpg";

        final String[] mention = send(notification(ChatChannel.Id.forTrip("trip-1"), "trip-1", "Rome 2027", "Ann",
                lucy, null, ChatNotification.Reason.MENTION), mom);
        Assert.assertEquals(mention[0], "Ann mentioned Lucy in the Rome 2027 chat");
        Assert.assertEquals(mention[1], "<p><b>Ann</b> mentioned Lucy in the <a href=\"" + CHAT_URL
                + "\">Rome 2027 chat</a>.</p>" + String.format(OLD_TAIL, "", CHAT_URL, "Open the chat",
                        ON_BEHALF_FOOTER));

        final String[] reply = send(notification(ChatChannel.Id.forTrip("trip-1"), "trip-1", "Rome 2027", "Ann",
                lucy, null, ChatNotification.Reason.REPLY), mom);
        Assert.assertEquals(reply[0], "Ann replied to Lucy in the Rome 2027 chat");
        Assert.assertEquals(reply[1], "<p><b>Ann</b> replied to Lucy&#39;s message in the <a href=\"" + CHAT_URL
                + "\">Rome 2027 chat</a>.</p>" + String.format(OLD_TAIL, "", CHAT_URL, "Open the chat",
                        ON_BEHALF_FOOTER));

        final String[] comment = send(notification(photo, "trip-1", "Rome 2027", "Ann", lucy, null,
                ChatNotification.Reason.PHOTO_COMMENT), mom);
        Assert.assertEquals(comment[0], "Ann commented on Lucy's photo from the Rome 2027 trip");
        Assert.assertEquals(comment[1], "<p><b>Ann</b> commented on <a href=\"" + photoUrl
                + "\">Lucy&#39;s photo from the Rome 2027 trip</a>.</p>" + String.format(OLD_TAIL, "", photoUrl,
                        "View the photo", ON_BEHALF_FOOTER));

        final String[] photoMention = send(notification(photo, "trip-1", "Rome 2027", "Ann", lucy, null,
                ChatNotification.Reason.MENTION), mom);
        Assert.assertEquals(photoMention[0], "Ann mentioned Lucy on a photo from the Rome 2027 trip");
        Assert.assertEquals(photoMention[1], "<p><b>Ann</b> mentioned Lucy in a comment on a <a href=\"" + photoUrl
                + "\">photo from the Rome 2027 trip</a>.</p>" + String.format(OLD_TAIL, "", photoUrl,
                        "View the photo", ON_BEHALF_FOOTER));

        final String[] announcement = send(notification(ChatChannel.Id.forTrip("trip-1"), "trip-1", "Rome 2027",
                "Ann", lucy, null, ChatNotification.Reason.ADMIN_ANNOUNCEMENT), mom);
        Assert.assertEquals(announcement[0], "Announcement for Lucy in the Rome 2027 chat");
    }

    @Test
    public void anEnglishDigestIsByteForByteWhatTheTemplateRendered() throws IOException {
        final Person reader = savedPerson("Rita", "rita");
        final Trip trip = Trip.builder().id("t1").title("Rome & Assisi").people(new ArrayList<>()).build();
        final String url = BASE + "/trip/chat.jsf?trip=t1";

        final String[] own = digest(reader, trip, null, 2);
        Assert.assertEquals(own[0], "New messages in the Rome & Assisi chat");
        Assert.assertTrue(own[1].startsWith("<p>Here is what you missed in the <a href=\"" + url
                + "\">Rome &amp; Assisi chat</a>.</p>\n\n<p style=\"color:#666;\">2 new message(s).</p>\n\n"
                + "<div><p style=\"margin:0.5rem 0;\"><b>"), own[1]);
        Assert.assertTrue(own[1].endsWith("</p></div>\n\n<p><a href=\"" + url + "\">Open the chat</a></p>\n\n"
                + "<hr style=\"border:none;border-top:1px solid #ddd;margin-top:1.5rem;\" />\n"
                + "<p style=\"font-size:0.85em;color:#666;\">You are receiving this daily summary because you "
                + "asked for it on this trip&#39;s chat page. You can turn it off there at any time.</p>\n"),
                own[1]);

        final String[] one = digest(reader, trip, 0L, 1);
        Assert.assertTrue(one[1].contains("<p style=\"color:#666;\">1 new message(s).</p>\n\n"
                + "<p style=\"color:#666;\">Open the chat to read them; this chat does not keep messages long "
                + "enough to include them here.</p>\n\n<p><a href="), one[1]);

        final Person mom = savedPerson("Mom", "mom");
        final Person lucy = childOf(mom);
        final String[] forLucy = digest(lucy, trip, null, 1);
        Assert.assertEquals(forLucy[0], "New messages in the Rome & Assisi chat for Lucy");
        Assert.assertTrue(forLucy[1].startsWith("<p>Here is what Lucy missed in the <a href=\"" + url
                + "\">Rome &amp; Assisi chat</a>.</p>\n\n<p style=\"color:#666;\">1 new message(s).</p>\n\n"),
                forLucy[1]);
        Assert.assertTrue(forLucy[1].endsWith("<p style=\"font-size:0.85em;color:#666;\">You are receiving "
                + "Lucy&#39;s daily summary as their family manager: it was asked for when they were registered, "
                + "and they have no email address of their own. It can be turned off on the trip&#39;s chat "
                + "page.</p>\n"), forLucy[1]);
    }

    // --- the reader's language ---

    /** No saved choice of their own: the mail follows the language the trip is held in. */
    @Test
    public void aMentionOnASpanishTripIsWrittenInSpanish() throws IOException {
        final Trip trip = savedBetaTrip(Language.Spanish);
        final Person reader = savedPerson("Rita", "rita");
        final ChatChannel.Id chat = ChatChannel.Id.forTrip(trip.getId());
        final String url = MailTemplates.escape(notifier.chatUrl(trip.getId()));

        final String[] mention = send(notification(chat, trip.getId(), "Roma 2027", "Ann O'Hara", reader,
                "bus at 7", ChatNotification.Reason.MENTION), reader);
        Assert.assertEquals(mention[0], "Ann O'Hara te mencionó en el chat de Roma 2027");
        Assert.assertTrue(mention[1].startsWith("<p><b>Ann O&#39;Hara</b> te mencionó en el <a href=\"" + url
                + "\">chat de Roma 2027</a>.</p>\n"), mention[1]);
        Assert.assertTrue(mention[1].contains("bus at 7"), "what a person typed is never translated");
        Assert.assertTrue(mention[1].contains("<p><a href=\"" + url + "\">Abrir el chat</a></p>"), mention[1]);
        Assert.assertTrue(mention[1].contains("Recibes este mensaje porque activaste las notificaciones de "
                + "menciones para el chat de este viaje."), mention[1]);

        final String[] reply = send(notification(chat, trip.getId(), "Roma 2027", null, reader, null,
                ChatNotification.Reason.REPLY), reader);
        Assert.assertEquals(reply[0], "Alguien te respondió en el chat de Roma 2027");
        Assert.assertTrue(reply[1].contains("<b>Alguien</b> respondió a tu mensaje en el"), reply[1]);

        final String[] comment = send(notification(ChatChannel.Id.forPhoto("chat/" + trip.getId() + "/x.jpg"),
                trip.getId(), "Roma 2027", "Ann", reader, null, ChatNotification.Reason.PHOTO_COMMENT), reader);
        Assert.assertEquals(comment[0], "Ann comentó tu foto de Roma 2027");
        Assert.assertTrue(comment[1].contains(">tu foto de Roma 2027</a>."), comment[1]);
        Assert.assertTrue(comment[1].contains(">Ver la foto</a>"), comment[1]);
    }

    /** The reader's own saved choice leads, and the reader of an on-behalf mail is the MANAGER, not the child. */
    @Test
    public void aMailAboutAChildIsWrittenInTheManagersLanguage() throws IOException {
        final Trip trip = savedBetaTrip(Language.Spanish);
        final Person mom = savedPerson("Mom", "mom");
        mom.setLanguage("hr");
        Assert.assertTrue(DAO.getInstance().savePerson(mom));
        final Person lucy = childOf(mom);
        lucy.setLanguage("en");
        Assert.assertTrue(DAO.getInstance().savePerson(lucy));

        final String[] mention = send(notification(ChatChannel.Id.forTrip(trip.getId()), trip.getId(), "Roma 2027",
                "Ann", lucy, null, ChatNotification.Reason.MENTION), mom);

        Assert.assertEquals(mention[0], "Ann je spomenuo/la osobu Lucy u chatu (Roma 2027)");
        Assert.assertTrue(mention[1].contains("<b>Ann</b> je spomenuo/la osobu Lucy u <a href="), mention[1]);
        Assert.assertTrue(mention[1].contains("Ovu poruku primate kao voditelj obitelji za osobu Lucy: Lucy nema"),
                mention[1]);
        Assert.assertEquals(EmailChatNotifier.subjectFor(notification(ChatChannel.Id.forTrip("t"), "t", "Roma",
                "Ann", lucy, null, ChatNotification.Reason.MENTION), lucy, Language.Spanish.getLocale()),
                "Ann mencionó a Lucy en el chat de Roma");
    }

    @Test
    public void aDigestIsWrittenInItsReadersLanguageWithThatLanguagesPlural() throws IOException {
        final Trip spanish = betaTrip(Language.Spanish);
        final Person reader = savedPerson("Rita", "rita");

        final String[] two = digest(reader, spanish, null, 2);
        Assert.assertEquals(two[0], "Mensajes nuevos en el chat de Roma 2027");
        Assert.assertTrue(two[1].startsWith("<p>Esto es lo que te perdiste en el <a href=\""), two[1]);
        Assert.assertTrue(two[1].contains(">chat de Roma 2027</a>.</p>"), two[1]);
        Assert.assertTrue(two[1].contains("<p style=\"color:#666;\">2 mensajes nuevos.</p>"), two[1]);
        Assert.assertTrue(two[1].contains(">Abrir el chat</a>"), two[1]);
        Assert.assertTrue(two[1].contains("Recibes este resumen diario porque lo pediste"), two[1]);
        Assert.assertTrue(digest(reader, spanish, 0L, 1)[1].contains("<p style=\"color:#666;\">1 mensaje nuevo.</p>"
                + "\n\n<p style=\"color:#666;\">Abre el chat para leerlos;"));

        reader.setLanguage("hr");
        final String[] croatian = digest(reader, spanish, null, 3);
        Assert.assertEquals(croatian[0], "Nove poruke u chatu (Roma 2027)");
        Assert.assertTrue(croatian[1].contains("<p style=\"color:#666;\">3 nove poruke.</p>"), croatian[1]);
        Assert.assertTrue(digest(reader, spanish, null, 5)[1].contains("5 novih poruka."));

        // The child's digest goes to the manager, so it is the manager who reads it.
        final Person mom = savedPerson("Mom", "mom");
        final Person lucy = childOf(mom);
        lucy.setLanguage("hr");
        final String[] forLucy = digest(lucy, spanish, null, 1);
        Assert.assertEquals(forLucy[0], "Mensajes nuevos en el chat de Roma 2027 para Lucy");
        Assert.assertTrue(forLucy[1].contains("Esto es lo que Lucy se perdió en el"), forLucy[1]);
        Assert.assertTrue(forLucy[1].contains("Recibes el resumen diario de Lucy como responsable de su familia"),
                forLucy[1]);
    }

    /** A sentence with markup takes its markup from the bundle alone: every argument arrives escaped. */
    @Test
    public void aSentenceWithMarkupEscapesEveryArgument() {
        final MailTemplates.Raw lead = MailTemplates.html(Locale.ENGLISH, "mail.chat.body.mentionOther",
                "<script>alert(1)</script>", "https://x.example/?a=1&b=\"2\"", "Tom's <i>trip</i>", "L&L");

        Assert.assertEquals(lead.html(), "<b>&lt;script&gt;alert(1)&lt;/script&gt;</b> mentioned L&amp;L in the "
                + "<a href=\"https://x.example/?a=1&amp;b=&quot;2&quot;\">Tom&#39;s &lt;i&gt;trip&lt;/i&gt; chat</a>.");
        Assert.assertEquals(EmailChatNotifier.nameOf(new Person(), Language.Spanish.getLocale()), "tu familiar");
        Assert.assertEquals(EmailChatNotifier.nameOf(null, Locale.ENGLISH), OnBehalf.nameOf(null));
    }

    // --- fixtures ---

    private static ChatNotification notification(final ChatChannel.Id channel, final String tripId,
            final String tripTitle, final String author, final Person about, final String snippet,
            final ChatNotification.Reason reason) {
        return new ChatNotification(channel, ChatMessage.Id.from(String.valueOf(System.nanoTime())), tripId,
                tripTitle, Person.Id.from("author"), author, List.of(about.getId()), snippet, reason, null,
                Instant.now());
    }

    /** Sends one notification and answers the subject and body mailed to {@code to}. */
    private String[] send(final ChatNotification notification, final Person to) {
        Mockito.clearInvocations(mail);
        notifier.notify(notification);
        return mailedTo(to.getEmail());
    }

    private String[] mailedTo(final String address) {
        final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.eq(address), ArgumentMatchers.any(),
                ArgumentMatchers.any(), subject.capture(), body.capture(), ArgumentMatchers.any(AuditActor.class));
        return new String[] {subject.getValue(), body.getValue()};
    }

    /** Sends {@code member}'s digest of {@code count} messages; the mail goes to them or to their manager. */
    private String[] digest(final Person member, final Trip trip, final Long retentionSeconds, final int count) {
        final DAO dao = Mockito.mock(DAO.class);
        Mockito.when(dao.getPerson(ArgumentMatchers.eq(member.getId()), ArgumentMatchers.eq(Cached.NO)))
                .thenReturn(Optional.of(member));
        final ChatChannel.Id id = ChatChannel.Id.forTrip(trip.getId());
        final ChatChannel channel = new ChatChannel(id, trip.getId(), ChatChannel.Kind.TRIP, "Test", null, null,
                ChatSettings.defaults().toBuilder().retentionSeconds(retentionSeconds).build(), Instant.now(),
                "admin", null, null);
        final List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new ChatMessage(ChatMessage.Id.from(String.valueOf(100 + i)), id, Person.Id.from("author"),
                    Instant.now(), null, "hello", null, null, null, null, null, null, null, null, null));
        }
        final ChatMembership membership = new ChatMembership(id, member.getId(), ChatMembership.MemberState.JOINED,
                null, Instant.now(), null, null, null, null, null, null, null,
                ChatNotifyPref.defaults().withEmail(false, true), null, null, null, null);
        final ChatPage page = new ChatPage(messages, Map.of(), null, 0L, 0L, false, false, Map.of(), Instant.now());
        Mockito.clearInvocations(mail);

        Assert.assertTrue(new ChatDigestSender(dao, cache, mail, config)
                .send(new ChatDigestSender.Candidate(channel, trip, member.getId(), membership, page)));

        final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), subject.capture(), body.capture(), ArgumentMatchers.any(AuditActor.class));
        return new String[] {subject.getValue(), body.getValue()};
    }

    private static Person savedPerson(final String first, final String mailbox) throws IOException {
        final Person person = Person.builder().first(first).last("Language")
                .email(mailbox + "-" + System.nanoTime() + "@example.org").build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    /** Lucy: a family member with no mailbox, answered for by {@code manager}. */
    private static Person childOf(final Person manager) throws IOException {
        final Person child = Person.builder().first("Lucy").last("Language").build();
        Assert.assertTrue(DAO.getInstance().savePerson(child));
        final Family family = Family.builder().id(Family.Id.from("fam-" + System.nanoTime()))
                .memberIds(List.of(manager.getId(), child.getId())).managerIds(List.of(manager.getId()))
                .createdBy(manager.getId()).build();
        Assert.assertTrue(DAO.getInstance().saveFamily(family));
        child.setFamilyId(family.getId());
        Assert.assertTrue(DAO.getInstance().savePerson(child));
        return child;
    }

    /** An unsaved trip of Beta Corp's, the one site that offers en, es and hr. */
    private static Trip betaTrip(final Language language) {
        final Trip trip = Trip.builder().id("ml-" + System.nanoTime()).title("Roma 2027").language(language)
                .people(new ArrayList<>()).build();
        trip.setOrgId(FakeData.BETA_ORG_ID);
        return trip;
    }

    /** The notifier reads the trip by id, so there it has to be in the store. */
    private static Trip savedBetaTrip(final Language language) throws IOException {
        final Trip trip = betaTrip(language);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }
}
