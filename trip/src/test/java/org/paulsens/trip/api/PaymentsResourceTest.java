package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.PayCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link PaymentsResource}: PayPal for clients that are not browsers.
 *
 * <p>Two rules carry the risk. A payment starts only for yourself or someone whose booking you manage --
 * otherwise a stranger's PayPal account gets attached to somebody else's balance. And the client-supplied
 * return/cancel URLs must sit under a configured prefix, or the approval flow becomes an open redirect wearing
 * PayPal's clothes.
 */
public class PaymentsResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("pay-me");
    private static final Person.Id WARD = Person.Id.from("pay-ward");
    private static final Person.Id STRANGER = Person.Id.from("pay-stranger");
    private static final String RETURN_URL = "https://trip.example.org/pay/return";
    private static final String CANCEL_URL = "https://trip.example.org/pay/cancel";

    private PayCommands pay;
    private PersonCommands people;
    private ConfigCommands config;
    private PaymentsResource resource;

    @BeforeMethod
    public void bindBeans() {
        pay = bindMock(PayCommands.class);
        people = bindMock(PersonCommands.class);
        // The manager rule itself should run for real -- it is what these tests are about. It is a stateless
        // check over Person.managedUsers, so the real method is safe on a mock.
        Mockito.when(people.canAccessUserId(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenCallRealMethod();
        config = bindMock(ConfigCommands.class);
        Mockito.when(config.getString(KnownSettings.PAYMENT_RETURN_URL_PREFIXES))
                .thenReturn("https://trip.example.org/");
        resource = resource(new PaymentsResource());
    }

    private Person me() {
        final Person person = new Person();
        person.setId(ME);
        person.setManagedUsers(List.of(WARD));
        Mockito.when(people.getPerson(ME)).thenReturn(person);
        return person;
    }

    private static PaymentsResource.OrderRequest order(final String userId, final Float amount) {
        return new PaymentsResource.OrderRequest(userId, amount, "trip-1", "Payment", RETURN_URL, CANCEL_URL);
    }

    @Test
    public void createOrderRequiresCsrfAndAPositiveAmount() {
        signedInAs(ME);

        assertError(resource.createOrder(null, order(null, 10f)), 403, ApiErrors.CSRF);
        assertError(resource.createOrder(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.createOrder(CSRF_OK, order(null, null)), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.createOrder(CSRF_OK, order(null, 0f)), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.createOrder(CSRF_OK, order(null, -5f)), 400, ApiErrors.BAD_REQUEST);
        Mockito.verifyNoInteractions(pay);
    }

    /** The rule that keeps one person's PayPal off another person's balance. */
    @Test
    public void payingForAStrangerIsRefused() {
        signedInAs(ME);
        me();

        assertError(resource.createOrder(CSRF_OK, order(STRANGER.getValue(), 10f)), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(pay);
    }

    @Test
    public void payingForAManagedPersonIsAllowed() {
        signedInAs(ME);
        me();
        Mockito.when(pay.createApprovalUrl(ArgumentMatchers.any(), ArgumentMatchers.eq(WARD),
                ArgumentMatchers.eq(10f), ArgumentMatchers.any(), ArgumentMatchers.eq(RETURN_URL),
                ArgumentMatchers.eq(CANCEL_URL))).thenReturn(Optional.of("https://paypal/approve"));

        final Response response = resource.createOrder(CSRF_OK, order(WARD.getValue(), 10f));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("approvalUrl"), "https://paypal/approve");
        Assert.assertEquals(body.get("userId"), WARD.getValue());
    }

    /** The open-redirect gate: an address outside the configured prefixes never reaches PayPal. */
    @Test
    public void aReturnUrlOffTheAllowlistIsRefused() {
        signedInAs(ME);
        me();

        final PaymentsResource.OrderRequest evil = new PaymentsResource.OrderRequest(
                null, 10f, "trip-1", "Payment", "https://evil.example.com/phish", CANCEL_URL);

        assertError(resource.createOrder(CSRF_OK, evil), 400, ApiErrors.BAD_REQUEST);
        Mockito.verifyNoInteractions(pay);
    }

    @Test
    public void paypalNotAnsweringIs502() {
        signedInAs(ME);
        me();
        Mockito.when(pay.createApprovalUrl(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Optional.empty());

        assertError(resource.createOrder(CSRF_OK, order(null, 10f)), 502, ApiErrors.INTERNAL);
    }

    @Test
    public void captureRequiresCsrfAndPermission() {
        signedInAs(ME);
        me();

        assertError(resource.capture("order-1", null, null), 403, ApiErrors.CSRF);
        assertError(resource.capture("order-1", CSRF_OK,
                new PaymentsResource.CaptureRequest(STRANGER.getValue(), null)), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(pay);
    }

    @Test
    public void captureDefaultsToTheCallerAndReportsTheOrder() {
        signedInAs(ME);
        Mockito.when(pay.captureAndSave("order-1", ME, "trip-1")).thenReturn(true);

        final Response response = resource.capture("order-1", CSRF_OK,
                new PaymentsResource.CaptureRequest(null, "trip-1"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("captured"), true);
        Assert.assertEquals(body.get("orderId"), "order-1");
    }

    @Test
    public void aFailedCaptureIsReported() {
        signedInAs(ME);
        Mockito.when(pay.captureAndSave(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(false);

        assertError(resource.capture("order-1", CSRF_OK, null), 502, ApiErrors.STORE_FAILED);
    }

    @Test
    public void feeEstimateNeedsANumber() {
        signedInAs(ME);

        assertError(resource.feeEstimate(null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.feeEstimate("ten dollars"), 400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void feeEstimateReportsFeeAndNet() {
        signedInAs(ME);
        Mockito.when(pay.estimateFee(new BigDecimal("100"))).thenReturn(new BigDecimal("3.98"));
        Mockito.when(pay.estimateNet(new BigDecimal("100"))).thenReturn(new BigDecimal("96.02"));

        final Response response = resource.feeEstimate(" 100 ");

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("fee"), new BigDecimal("3.98"));
        Assert.assertEquals(body.get("net"), new BigDecimal("96.02"));
    }

    @Test
    public void theProducedTypeIsThePaymentsMediaType() {
        Assert.assertEquals(new PaymentsResource().versionedType(), ApiMediaTypes.PAYMENTS_V1);
    }
}
