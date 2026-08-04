package org.paulsens.trip.action;

import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Money;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrdersCapture;
import com.paypal.sdk.models.PaymentCollection;
import com.paypal.sdk.models.PurchaseUnit;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.pay.PayPalClient;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link PayCommands}' order flows, with {@link PayPalClient} mocked at its static factory.
 *
 * <p>The dedupe rule carries the money-risk: a mobile client that loses its connection mid-capture MUST retry,
 * and the retry must neither charge nor record twice. And the audit actor is built on the request thread by
 * design -- the notification crosses into SES's completion thread where {@code AuditActor.current()} finds
 * nobody.
 */
public class PayCommandsCaptureTest {

    private static final Person.Id USER = Person.Id.from("pay-user");
    private static final String ORDER_ID = "ORDER-123";

    private PayCommands commands;
    private PayPalClient payPal;
    private MockedStatic<PayPalClient> payPalStatic;
    private TransactionsCommands transactions;
    private BindingCommands bindings;
    private MailCommands mail;
    private AuditCommands audit;
    private PersonCommands people;
    private TripCommands trips;

    @BeforeMethod
    public void wireBean() throws Exception {
        commands = new PayCommands();
        transactions = inject("txCmds", Mockito.mock(TransactionsCommands.class));
        bindings = inject("bindCmds", Mockito.mock(BindingCommands.class));
        mail = inject("mail", Mockito.mock(MailCommands.class));
        audit = inject("audit", Mockito.mock(AuditCommands.class));
        people = inject("people", Mockito.mock(PersonCommands.class));
        trips = inject("trips", Mockito.mock(TripCommands.class));

        payPal = Mockito.mock(PayPalClient.class);
        payPalStatic = Mockito.mockStatic(PayPalClient.class);
        payPalStatic.when(PayPalClient::getInstance).thenReturn(payPal);
    }

    @AfterMethod(alwaysRun = true)
    public void closePayPalStatic() {
        if (payPalStatic != null) {
            payPalStatic.close();
            payPalStatic = null;
        }
    }

    /** CDI would inject these; without a container the test does, by field name. */
    private <T> T inject(final String fieldName, final T mock) throws Exception {
        final Field field = PayCommands.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(commands, mock);
        return mock;
    }

    private static ApiResponse<Order> apiResponse(final Order order) {
        return new ApiResponse<>(200, new com.paypal.sdk.http.Headers(), order);
    }

    private static Order capturedOrder(final String amount) {
        final OrdersCapture capture = new OrdersCapture.Builder()
                .amount(new Money.Builder("USD", amount).build())
                .build();
        final PurchaseUnit unit = new PurchaseUnit.Builder()
                .description("Trip payment")
                .payments(new PaymentCollection.Builder().captures(List.of(capture)).build())
                .build();
        return new Order.Builder().id(ORDER_ID).purchaseUnits(List.of(unit)).build();
    }

    private void payPalAnswers(final Order order) {
        final ApiResponse<Order> response = apiResponse(order);
        Mockito.when(payPal.captureOrder(ORDER_ID)).thenReturn(CompletableFuture.completedFuture(response));
        Mockito.when(payPal.getCapturedAmount(order)).thenReturn(100f);
        Mockito.when(payPal.getPayPalFee(order)).thenReturn(Optional.of(3.98f));
    }

    @Test
    public void aMissingOrderIdRefusesWithoutTouchingPayPal() {
        Assert.assertFalse(commands.captureAndSave(null, USER, null));
        Assert.assertFalse(commands.captureAndSave("", USER, null));
        Mockito.verifyNoInteractions(payPal);
    }

    /** The retry rule: an order already recorded is a SUCCESS and must not reach PayPal again. */
    @Test
    public void aRepeatedCaptureIsSuccessWithoutASecondCharge() {
        Mockito.when(transactions.hasTransaction(USER, ORDER_ID)).thenReturn(true);

        Assert.assertTrue(commands.captureAndSave(ORDER_ID, USER, null));

        Mockito.verifyNoInteractions(payPal);
        Mockito.verify(transactions, Mockito.never()).saveTransaction(ArgumentMatchers.any());
    }

    @Test
    public void aNullPayPalResponseIsAFailureNotACrash() {
        Mockito.when(payPal.captureOrder(ORDER_ID))
                .thenReturn(CompletableFuture.completedFuture(null));

        Assert.assertFalse(commands.captureAndSave(ORDER_ID, USER, null));
        Mockito.verify(transactions, Mockito.never()).saveTransaction(ArgumentMatchers.any());
    }

    @Test
    public void aPayPalFailureIsCaughtAndReportedAsFalse() {
        Mockito.when(payPal.captureOrder(ORDER_ID))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("paypal down")));

        Assert.assertFalse(commands.captureAndSave(ORDER_ID, USER, null));
    }

    @Test
    public void aSuccessfulCaptureSavesBindsAuditsAndNotifies() {
        final Person person = new Person();
        person.setId(USER);
        person.setEmail("payer@example.org");
        Mockito.when(people.getPerson(USER)).thenReturn(person);
        payPalAnswers(capturedOrder("100.00"));
        Mockito.when(transactions.saveTransaction(ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(bindings.key(USER.getValue(), ORDER_ID)).thenReturn("bind-key");
        Mockito.when(trips.getTrip("trip-1"))
                .thenReturn(org.paulsens.trip.model.Trip.builder().id("trip-1").title("Rome").build());

        Assert.assertTrue(commands.captureAndSave(ORDER_ID, USER, "trip-1"));

        final org.mockito.ArgumentCaptor<Transaction> saved =
                org.mockito.ArgumentCaptor.forClass(Transaction.class);
        Mockito.verify(transactions).saveTransaction(saved.capture());
        Assert.assertEquals(saved.getValue().getTxId(), ORDER_ID, "The PayPal order id IS the tx id (dedupe)");
        Assert.assertEquals(saved.getValue().getAmount(), 100f);
        Assert.assertTrue(saved.getValue().getNote().contains("$3.98"), "The fee belongs in the note");

        Mockito.verify(bindings).setBindings("bind-key", BindingType.TRANSACTION, BindingType.TRIP,
                List.of("trip-1"), true);
        Mockito.verify(audit).log(ArgumentMatchers.eq("payer@example.org"), ArgumentMatchers.eq("PAYMENT"),
                ArgumentMatchers.contains("$100.00"));
        // The actor travels INTO the mail call, built on this thread; SES's callback thread has no FacesContext.
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.contains("$100.00"), ArgumentMatchers.any(),
                ArgumentMatchers.argThat((AuditActor actor) -> "payer@example.org".equals(actor.email())));
    }

    @Test
    public void aFailedTransactionSaveRefusesAndDoesNotBind() {
        Mockito.when(people.getPerson(USER)).thenReturn(new Person());
        payPalAnswers(capturedOrder("100.00"));
        Mockito.when(transactions.saveTransaction(ArgumentMatchers.any())).thenReturn(false);

        Assert.assertFalse(commands.captureAndSave(ORDER_ID, USER, "trip-1"));
        Mockito.verifyNoInteractions(bindings);
    }

    @Test
    public void noTripMeansNoBinding() {
        Mockito.when(people.getPerson(USER)).thenReturn(new Person());
        payPalAnswers(capturedOrder("100.00"));
        Mockito.when(transactions.saveTransaction(ArgumentMatchers.any())).thenReturn(true);

        Assert.assertTrue(commands.captureAndSave(ORDER_ID, USER, null));
        Mockito.verifyNoInteractions(bindings);
    }

    @Test
    public void createApprovalUrlAnswersEmptyWhenPayPalDeclines() {
        Mockito.when(payPal.createOrder(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(CompletableFuture.completedFuture(null));

        Assert.assertTrue(commands.createApprovalUrl(null, USER, 10f, "d", "https://r", "https://c").isEmpty());
    }

    @Test
    public void createApprovalUrlExtractsTheApprovalLink() {
        final Order order = new Order.Builder()
                .links(List.of(new LinkDescription.Builder("https://paypal/approve", "approve").build()))
                .build();
        Mockito.when(payPal.createOrder(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(apiResponse(order)));
        Mockito.when(payPal.getApprovalUrl(order)).thenReturn(Optional.of("https://paypal/approve"));

        Assert.assertEquals(commands.createApprovalUrl(null, USER, 10f, "d", "https://r", "https://c")
                .orElse(null), "https://paypal/approve");
    }

    @Test
    public void initPaymentRefusesANonPositiveAmountBeforePayPal() {
        commands.initPayment(null, USER, null, null, "d");
        commands.initPayment(null, USER, 0f, null, "d");
        commands.initPayment(null, USER, -5f, null, "d");

        Mockito.verifyNoInteractions(payPal);
    }

    @Test
    public void initPaymentRedirectsTheBrowserToPayPal() throws Exception {
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            final ExternalContext external = Mockito.mock(ExternalContext.class);
            final jakarta.servlet.http.HttpServletRequest request =
                    Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
            Mockito.when(ctx.getExternalContext()).thenReturn(external);
            Mockito.when(external.getRequest()).thenReturn(request);
            Mockito.when(request.getRequestURL())
                    .thenReturn(new StringBuffer("https://site.example/trip/pay.jsf"));

            final Order order = new Order.Builder().build();
            Mockito.when(payPal.createOrder(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(CompletableFuture.completedFuture(
                            apiResponse(order)));
            Mockito.when(payPal.getApprovalUrl(order)).thenReturn(Optional.of("https://paypal/approve"));

            commands.initPayment(null, USER, 50f, "trip-1", "Payment");

            Mockito.verify(external).redirect("https://paypal/approve");
            // The return and cancel URLs derive from the page the payer was on, with the trip preserved.
            Mockito.verify(payPal).createOrder(ArgumentMatchers.any(), ArgumentMatchers.eq(USER),
                    ArgumentMatchers.eq(50f), ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.eq("https://site.example/trip/pay.jsf?trip=trip-1"),
                    ArgumentMatchers.eq("https://site.example/trip/pay.jsf?trip=trip-1&cancelled=true"));
        }
    }

    @Test
    public void initPaymentReportsAPayPalDeclineAsAMessageNotACrash() {
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            final ExternalContext external = Mockito.mock(ExternalContext.class);
            final jakarta.servlet.http.HttpServletRequest request =
                    Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
            Mockito.when(ctx.getExternalContext()).thenReturn(external);
            Mockito.when(external.getRequest()).thenReturn(request);
            Mockito.when(request.getRequestURL())
                    .thenReturn(new StringBuffer("https://site.example/trip/pay.jsf"));
            Mockito.when(payPal.createOrder(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            commands.initPayment(null, USER, 50f, null, "Payment");

            Mockito.verifyNoInteractions(mail);
        }
    }

    @Test
    public void theLegacyOrderCallsPassThrough() {
        final Order order = new Order.Builder().build();
        final ApiResponse<Order> response = apiResponse(order);
        Mockito.when(payPal.createOrder(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull())).thenReturn(CompletableFuture.completedFuture(response));
        Mockito.when(payPal.captureOrder("o1")).thenReturn(CompletableFuture.completedFuture(response));

        Assert.assertSame(commands.startOrder(null, USER, 10f, null, "CFPW", "d"), response);
        Assert.assertSame(commands.completeOrder("o1"), response);
    }
}
