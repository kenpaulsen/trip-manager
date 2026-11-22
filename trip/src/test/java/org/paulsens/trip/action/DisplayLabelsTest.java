package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import org.paulsens.trip.action.LedgerCommands.State;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.site.CustomDomains;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * What a page SHOWS against what the code STORES or COMPARES (docs/i18n.md): a status, a processor, an export
 * column, a palette group, the Balance page's line and every refusal that used to be an English constant is worded
 * in the reader's language, while the value beside it stays the stable English or id it always was. English output
 * is pinned word for word, so nothing a page or a test reads changed there.
 */
public class DisplayLabelsTest {

    private static <T> T in(final Language language, final Callable<T> body) throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.system().withLocale(language.getLocale()))
                .call(body::call);
    }

    @Test
    public void aRegistrationStatusIsShownTranslatedAndKeptInEnglish() throws Exception {
        Assert.assertEquals(RegistrationCommands.labelOf("Pending"), "Pending");
        Assert.assertEquals(RegistrationCommands.labelOf("Not Registered"), "Not Registered");
        Assert.assertEquals(in(Language.Spanish, () -> RegistrationCommands.labelOf("Pending")), "Pendiente");
        Assert.assertEquals(in(Language.Croatian, () -> RegistrationCommands.labelOf("Confirmed")), "Potvrđeno");
        Assert.assertEquals(RegistrationCommands.labelOf("Waitlisted"), "Waitlisted", "unknown text as it is");
        Assert.assertEquals(RegistrationCommands.labelOf(null), "");
        final RegistrationCommands.RegRow row =
                new RegistrationCommands.RegRow("u1", LocalDateTime.now(), "Confirmed", null);
        Assert.assertEquals(in(Language.Spanish, row::getStatusLabel), "Confirmada");
        Assert.assertEquals(row.getStatus(), "Confirmed", "the menu still submits, and the API reads, the English");
        Assert.assertEquals(in(Language.Spanish, () -> new RegistrationCommands().statusLabel("Not Registered")),
                "Sin inscripción");
        Assert.assertEquals(in(Language.Spanish, () -> new RegistrationCommands().allowedStatuses("Pending")),
                List.of("Pending", "Confirmed", "Not Registered"), "menu VALUES never translate");
    }

    @Test
    public void aProcessorIsNamedForThePageInTheReadersLanguage() throws Exception {
        final Payment offline = Payment.builder().processorType(ProcessorType.OFFLINE).build();
        Assert.assertEquals(PaymentCommands.paymentMethodOf(offline), "Offline payment");
        Assert.assertEquals(in(Language.Spanish, () -> PaymentCommands.paymentMethodOf(offline)),
                "Pago fuera de línea");
        final Payment unknown = Payment.builder().processorType(ProcessorType.FAKE).build();
        Assert.assertEquals(PaymentCommands.paymentMethodOf(unknown), "the payment processor");
        Assert.assertEquals(in(Language.Croatian, () -> PaymentCommands.paidWith(unknown)), "procesor plaćanja");
        Assert.assertEquals(ProcessorType.OFFLINE.getLabel(), "Offline payment", "the ledger's word stays English");
    }

    @Test
    public void anExportColumnKeepsItsIdAndShowsItsLabel() throws Exception {
        final CheckinCommands checkin = new CheckinCommands();
        for (final String id : List.of("Last", "Middle", "Sex", "Birthdate", "Passport-Country", "Passport-Number",
                "Passport-Expiration", "Passport-POB", "Email", "Room", "Options", "Preferred-Name",
                "Registration-Type", "Total", "Discount-Code", "Attestation", "Ticket", "Checked-In", "Registered",
                "Status", "Payment-Method", "Payment-Reference", "Payment-Received")) {
            Assert.assertEquals(checkin.exportFieldLabel(id), id, "the English label IS the id");
        }
        Assert.assertEquals(checkin.exportFieldLabel("Q: Diet"), "Q: Diet");
        Assert.assertEquals(in(Language.Spanish, () -> checkin.exportFieldLabel("Passport-Country")),
                "País del pasaporte");
        Assert.assertEquals(in(Language.Croatian, () -> checkin.exportFieldLabel("Q: Diet")), "P: Diet");
        Assert.assertEquals(checkin.exportFieldLabel("Something-New"), "Something-New", "an unknown id as it is");
        Assert.assertEquals(checkin.exportFieldLabel(null), "");
    }

    @Test
    public void aPaletteGroupIsAnIdWithAHeadingKey() {
        final CustomPalettes palettes = new CustomPalettes();
        Assert.assertEquals(palettes.getGroups().stream().map(palettes::groupLabelKey).toList(),
                List.of("feedback.themeColors.groupAccent", "feedback.themeColors.groupText",
                        "feedback.themeColors.groupSurfaces", "content.palette.menu"));
        Assert.assertEquals(palettes.groupLabelKey(null), "content.palette.menu");
    }

    @Test
    public void theBalanceLineIsOneSentencePerStateAndSubject() throws Exception {
        for (final State state : State.values()) {
            for (final String subject : State.SUBJECTS) {
                Assert.assertFalse(state.line(subject, "Ken", "Acme").startsWith("???"), state + " " + subject);
            }
        }
        Assert.assertEquals(in(Language.Spanish, () -> State.OWES.line("yourFamily", "Ken", "Acme")),
                "Tu familia debe a Acme");
        Assert.assertEquals(in(Language.Croatian, () -> State.OWES.line("you", "Ken", "Acme")), "Dugujete: Acme");
        Assert.assertEquals(in(Language.Croatian, () -> State.CREDIT.line("person", "Ken", "Acme")),
                "Ken ima pretplatu kod: Acme");
        Assert.assertEquals(in(Language.Spanish, () -> State.OWES.pillText(-1200)), "$12.00 adeudado",
                "money stays in US format");
        Assert.assertEquals(in(Language.Spanish, () -> State.PAID_UP.closingText(0)), "$0.00");
    }

    @Test
    public void aMoneyFormRefusalIsOneSentencePerField() throws Exception {
        Assert.assertEquals(MoneyForms.tooLong("feedback.moneyForms.reportNoteTooLong", "x".repeat(2001), 2000),
                "The note is at most 2000 characters.", "no digit grouping, as before");
        Assert.assertEquals(MoneyForms.tooLong("feedback.moneyForms.paymentInstructionsTooLong", "abcd", 3),
                "The payment instructions is at most 3 characters.");
        Assert.assertEquals(in(Language.Croatian, () -> MoneyForms.tooLong("feedback.moneyForms.noteTooLong",
                "abcd", 3)), "Napomena može imati najviše 3 znaka.", "Croatian's few form");
        Assert.assertEquals(in(Language.Croatian, () -> MoneyForms.tooLong("feedback.moneyForms.noteTooLong",
                "x".repeat(501), 500)), "Napomena može imati najviše 500 znakova.");
        Assert.assertEquals(in(Language.Spanish, () -> MoneyForms.dollarsProblem(
                "feedback.moneyForms.perPersonBudgetRange", -1.0)),
                "El presupuesto por persona debe estar entre $0 y $10,000,000.00.");
        Assert.assertEquals(MoneyForms.dollarsProblem("feedback.moneyForms.toBillRange", -1.0),
                "The amount to bill per person must be between $0 and $10,000,000.00.");
    }

    @Test
    public void theOldEnglishConstantsAreReadFromTheBundle() throws Exception {
        Assert.assertEquals(PersonCommands.signInChangeRefused(),
                "Changing how this person signs in takes a site administrator.");
        Assert.assertEquals(MailMerge.invalidMergeFrom(),
                "The From address must use one of your verified sending domains.");
        Assert.assertEquals(LedgerCommands.unassignedOrgName(), "No organization");
        Assert.assertEquals(LedgerCommands.unassignedTripTitle(), "Not yet assigned to a trip");
        Assert.assertEquals(CustomDomains.needsSlug(), "An organization needs a subdomain before it can have a "
                + "custom domain: the domain is a second address of that site.");
        Assert.assertEquals(in(Language.Spanish, LedgerCommands::unassignedOrgName), "Sin organización");
        Assert.assertEquals(in(Language.Croatian, PersonCommands::signInChangeRefused),
                "Način prijave ove osobe u sustav može promijeniti samo administrator stranice.");
        Assert.assertEquals(in(Language.Spanish, MailMerge::invalidMergeFrom),
                "La dirección del remitente debe usar uno de tus dominios de envío verificados.");
    }

    /**
     * A seeded trip page STORES its button text, so it is written in the page's own language (the trip's, when its
     * site offers it, else the site's default), never in the language of whoever's request seeded it.
     */
    @Test
    public void aSeededTripPageIsWrittenInThePagesLanguageNotTheRequesters() throws Exception {
        final Trip trip = Trip.builder().title("2027 May: Rome").startDate(LocalDateTime.of(2027, 5, 1, 8, 0))
                .endDate(LocalDateTime.of(2027, 5, 9, 17, 0)).build();
        trip.setLanguage(Language.Spanish);
        final List<ContentInstance> rows = in(Language.Spanish,
                () -> OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, LocalDateTime.of(2027, 1, 1, 0, 0)));
        Assert.assertEquals(rows.get(0).getValues().get("primaryText"), "Join Pilgrimage",
                "the site offers English alone, so a Spanish trip's page is still written in English");
        trip.setLanguage(Language.English);
        trip.setKind(TripKind.CONFERENCE);
        final List<ContentInstance> conference = in(Language.Croatian,
                () -> OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, LocalDateTime.of(2027, 1, 1, 0, 0)));
        Assert.assertEquals(conference.get(0).getValues().get("primaryText"), "Register");
        Assert.assertEquals(TripKind.CONFERENCE.registerLabelIn(Language.Spanish.getLocale()), "Inscribirse");
        Assert.assertEquals(TripKind.PILGRIMAGE.displayValueIn(Language.Croatian.getLocale()), "Hodočašće");
    }

    @Test
    public void aStoredRegistrationStatusIsUntouched() {
        Assert.assertEquals(Registration.Status.PENDING.getDescription(), "Pending");
        Assert.assertEquals(Registration.Status.fromDescription("Confirmed"), Registration.Status.CONFIRMED);
    }
}
