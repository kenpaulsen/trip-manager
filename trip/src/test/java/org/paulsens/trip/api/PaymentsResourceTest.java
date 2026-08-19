package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link PaymentsResource}: the payment flow for clients that are not browsers -- a thin edge over
 * {@code PaymentCommands} (the flow itself is pinned in {@code PaymentCommandsTest}). What carries risk HERE
 * is the HTTP mapping: CSRF on every mutation, the return-URL allowlist (without it the approval flow is an
 * open redirect wearing the processor's clothes), and the notfound translation that reveals nothing about
 * other people's payments.
 */
public class PaymentsResourceTest extends ResourceTestSupport {

    private static final String CSRF_OK = "1";
    private static final String RETURN_URL = "https://trip.example.org/pay/return";

    private PaymentsResource resource;

    @BeforeMethod
    public void bindBeans() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final ConfigCommands config = bindMock(ConfigCommands.class);
        Mockito.when(config.getString(KnownSettings.PAYMENT_RETURN_URL_PREFIXES))
                .thenReturn("https://trip.example.org/");
        // findTrip resolves through Beans; delegate to a real TripCommands over the local store.
        final org.paulsens.trip.action.TripCommands trips =
                bindMock(org.paulsens.trip.action.TripCommands.class);
        Mockito.when(trips.getTrip(Mockito.any())).thenAnswer(
                inv -> new org.paulsens.trip.action.TripCommands().getTrip(inv.getArgument(0)));
        // The confirmation mail rides Beans.get(MailCommands) inside the flow; a mock keeps SES out.
        final org.paulsens.trip.action.MailCommands mail =
                bindMock(org.paulsens.trip.action.MailCommands.class);
        Mockito.when(mail.sendManagedTemplate(Mockito.anyString(), Mockito.anyMap(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(true);
        resource = resource(new PaymentsResource());
    }

    @Test
    public void everyMutationRequiresTheCsrfHeader() {
        assertError(resource.start(null, body("faketrip", "10", RETURN_URL)), 403, ApiErrors.CSRF);
        assertError(resource.complete("x", null, null), 403, ApiErrors.CSRF);
        assertError(resource.cancel("x", null), 403, ApiErrors.CSRF);
    }

    @Test
    public void unknownTripsAnswer404() {
        signedInAs(savedPerson().getId());
        assertError(resource.quote(new PaymentsResource.PaymentStart("no-such-trip", Map.of(), null,
                null, null)), 404, ApiErrors.NOT_FOUND);
        assertError(resource.start(CSRF_OK, body("no-such-trip", "10", RETURN_URL)), 404,
                ApiErrors.NOT_FOUND);
    }

    @Test
    public void returnUrlsOutsideTheAllowlistAreRefused() {
        final Person payer = savedPerson();
        signedInAs(payer.getId());
        assertError(resource.start(CSRF_OK, body("faketrip", "10", "https://evil.example/return")), 400,
                ApiErrors.BAD_REQUEST);
        assertError(resource.start(CSRF_OK,
                new PaymentsResource.PaymentStart("faketrip", Map.of(), null, null, null)), 400,
                ApiErrors.BAD_REQUEST);
    }

    @Test
    public void quoteAnswersTheSameMathAsThePage() {
        signedInAs(savedPerson().getId());
        final Response response = resource.quote(new PaymentsResource.PaymentStart("faketrip",
                Map.of("anyone", "475"), "1000", null, null));
        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> quote = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(quote.get("creditCents"), 47500L);
        Assert.assertEquals(quote.get("donationCents"), 100000L);
        Assert.assertEquals(quote.get("payable"), true);
        Assert.assertEquals(quote.get("payerPaysFee"), true, "CFPW's seeded defaults are payer-pays");
    }

    @Test
    public void theFullFlowStartsCompletesAndCancels() {
        final Person payer = savedPerson();
        signedInAs(payer.getId());

        final Response started = resource.start(CSRF_OK,
                body("faketrip", "40", RETURN_URL));
        assertOk(started);
        @SuppressWarnings("unchecked")
        final String approvalUrl =
                (String) ((Map<String, Object>) started.getEntity()).get("approvalUrl");
        Assert.assertTrue(approvalUrl.startsWith("/trip/fakeCheckout.jsf?token=FAKE-"));
        final String paymentId = paymentIdFrom(approvalUrl);

        final Response completed = resource.complete(paymentId, CSRF_OK, null);
        assertOk(completed);
        @SuppressWarnings("unchecked")
        final Map<String, Object> outcome = (Map<String, Object>) completed.getEntity();
        Assert.assertEquals(outcome.get("status"), "recorded");

        // Cancelling a RECORDED payment is a no-op false, and someone else's payment is invisible.
        assertOk(resource.cancel(paymentId, CSRF_OK));
        signedInAs(savedPerson().getId());
        // The caller is memoized per request/resource; a new identity means a new resource instance.
        final PaymentsResource stranger = resource(new PaymentsResource());
        assertError(stranger.complete(paymentId, CSRF_OK, null), 404, ApiErrors.NOT_FOUND);
        assertError(stranger.complete("no-such-payment", CSRF_OK, null), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void aRefusedStartMapsTo400() {
        final Person payer = savedPerson();
        signedInAs(payer.getId());
        assertError(resource.start(CSRF_OK, body("faketrip", "not-money", RETURN_URL)), 400,
                ApiErrors.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ helpers

    private PaymentsResource.PaymentStart body(final String tripId, final String amount,
            final String returnUrl) {
        final Person.Id self = sessionPersonId();
        final Map<String, Object> amounts = (self == null)
                ? Map.of() : Map.of(self.getValue(), amount);
        return new PaymentsResource.PaymentStart(tripId, amounts, null, null, returnUrl);
    }

    /** The signed-in id this test set last (mirrors what signedInAs stored). */
    private Person.Id lastSignedIn;

    private Person.Id sessionPersonId() {
        return lastSignedIn;
    }

    @Override
    protected void signedInAs(final Person.Id id) {
        super.signedInAs(id);
        lastSignedIn = id;
    }

    private Person savedPerson() {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("rest." + RandomData.genAlpha(10) + "@example.com")
                .build();
        try {
            Assert.assertTrue(DAO.getInstance().savePerson(person));
        } catch (final java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    private static String paymentIdFrom(final String approvalUrl) {
        final String encoded = approvalUrl.substring(approvalUrl.indexOf("return=") + 7,
                approvalUrl.indexOf("&cancel="));
        final String returnUrl = java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
        return returnUrl.substring(returnUrl.indexOf("payment=") + 8);
    }
}
