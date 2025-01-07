package org.paulsens.trip.action;

import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.Address;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.Payer;
import com.paypal.sdk.models.PurchaseUnit;
import com.paypal.sdk.models.ShippingWithTrackingDetails;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.pay.PayPalClient;

/**
 * JSF-accessible CDI bean for PayPal credit card payment flows.
 *
 * <p><b>Redirect flow (for credit card payments):</b></p>
 * <ol>
 *   <li>Page calls {@link #initPayment} → server creates a PayPal order and redirects the
 *       user's browser to the PayPal checkout page.</li>
 *   <li>User completes (or cancels) payment on PayPal's site.</li>
 *   <li>PayPal redirects back to the {@code returnUrl} with {@code ?token=<orderId>} appended.</li>
 *   <li>Page detects {@code param.token} and calls {@link #captureAndSave} → captures the
 *       payment, records a Transaction (with fee info in the note), and optionally binds it
 *       to a trip.</li>
 * </ol>
 *
 * <p>PayPal credentials are read from environment variables
 * {@code PAYPAL_CLIENT_ID} and {@code PAYPAL_SECRET}. Set {@code PAYPAL_ENVIRONMENT=PRODUCTION}
 * to switch from sandbox to live mode.</p>
 */
@Slf4j
@Named("pay")
@ApplicationScoped
public class PayCommands {
    // Standard PayPal credit card rate (US): 3.49 % + $0.49 fixed fee.
    // See https://www.paypal.com/us/webapps/mpp/merchant-fees for current rates.
    static final BigDecimal FEE_RATE   = BigDecimal.valueOf(0.0349d);
    static final BigDecimal FEE_FIXED  = BigDecimal.valueOf(0.49d);

    // FIXME: This is temporary, we should move this out to the .xhtml file so it's parameterized per site
    private static final String FROM_ADDRESS = "Center Mir Medjugorje <info@centermirmedjugorje.com>";
    // FIXME: This is temporary, we should move this out to the .xhtml file so it's parameterized per site
    private static final String NOTIFY_EMAIL = "info@centermirmedjugorje.com";

    private final DateTimeFormatter timestampPattern = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    @Inject
    private TransactionsCommands txCmds;
    @Inject
    private BindingCommands bindCmds;
    @Inject
    private MailCommands mail;
    @Inject
    private AuditCommands audit;
    @Inject
    private PersonCommands people;
    @Inject
    private TripCommands trips;

    // -------------------------------------------------------------------------
    // Public API – called from XHTML pages
    // -------------------------------------------------------------------------

    /**
     * Starts a PayPal checkout session for the given amount and redirects the user's browser
     * to the PayPal approval page. Call this from a JSF command-button event.
     *
     * @param payer       The Person making the payment (used to pre-fill PayPal payer info).
     * @param userId      The Person.Id to credit with the resulting transaction.
     * @param amount      The gross amount to charge, in USD.
     * @param tripId      Optional trip ID; if provided, the transaction will be bound to it.
     * @param description Human-readable payment description shown on PayPal's checkout page.
     */
    public void initPayment(
            final Person payer,
            final Person.Id userId,
            final Float amount,
            final String tripId,
            final String description) {
        if (amount == null || amount <= 0) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Invalid Amount", "Please enter a payment amount greater than $0.");
            return;
        }
        try {
            final String returnUrl = buildCurrentUrl(tripId, false);
            final String cancelUrl = buildCurrentUrl(tripId, true);
            final String invoiceId = genInvoiceId();

            final ApiResponse<Order> response = PayPalClient.getInstance()
                    .createOrder(payer, userId, amount, invoiceId, "CFPW", description, returnUrl, cancelUrl)
                    .orTimeout(10_000, TimeUnit.MILLISECONDS)
                    .join();

            if (response == null || response.getResult() == null) {
                log.error("Null response from PayPal createOrder for userId={}", userId);
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                        "Payment Error", "Unable to create payment. Please try again.");
                return;
            }

            final Optional<String> approvalUrl = PayPalClient.getInstance().getApprovalUrl(response.getResult());
            if (approvalUrl.isEmpty()) {
                log.error("No approval URL in PayPal order response for userId={}", userId);
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                        "Payment Error", "Unable to get payment URL from PayPal. Please try again.");
                return;
            }

            FacesContext.getCurrentInstance().getExternalContext().redirect(approvalUrl.get());

        } catch (final IOException ex) {
            log.error("Error redirecting to PayPal for userId={}", userId, ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Payment Error", "A network error occurred. Please try again.");
        } catch (final Exception ex) {
            log.error("Unexpected error starting PayPal payment for userId={}", userId, ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Payment Error", ex.getMessage());
        }
    }

    /**
     * Captures a PayPal order returned from the approval redirect and persists the resulting
     * Transaction. Call this when {@code param.token} is present in the page URL (i.e. PayPal
     * has redirected the user back after approval).
     *
     * @param orderId The PayPal order ID ({@code param.token} from the redirect URL).
     * @param userId  The Person.Id to credit.
     * @param tripId  Optional trip ID for binding.
     * @return {@code true} if the payment was captured and saved successfully.
     */
    public boolean captureAndSave(final String orderId, final Person.Id userId, final String tripId) {
        if (orderId == null || orderId.isEmpty()) {
            log.error("captureAndSave called with null orderId");
            return false;
        }
        if (userId == null) {
            log.error("captureAndSave called with null userId");
        }
        if (txCmds.hasTransaction(userId, orderId)) {
            log.info("captureAndSave called again with orderId={}. Ignoring.", orderId);
            return true;
        }
        val person = (userId == null) ? null : people.getPerson(userId);
        val email = (person == null) ? orderId : person.getEmail();
        try {
            final ApiResponse<Order> response = PayPalClient.getInstance()
                    .captureOrder(orderId)
                    .orTimeout(10_000, TimeUnit.MILLISECONDS)
                    .join();

            if (response == null || response.getResult() == null) {
                log.error("Null response from PayPal captureOrder for orderId={}", orderId);
                return false;
            }

            final Order order = response.getResult();
            log.info("Capture order: {}", order.toString());
            final float gross = PayPalClient.getInstance().getCapturedAmount(order);
            final Optional<Float> feeOpt = PayPalClient.getInstance().getPayPalFee(order);

            final String note = buildTransactionNote(order, gross, feeOpt);
            if (userId != null) {
                final Transaction tx = new Transaction(orderId, userId, null, Transaction.Type.Tx,
                    Transaction.TransactionType.Payment, LocalDateTime.now(), gross, "Payment", note);
                if (!txCmds.saveTransaction(tx)) {
                    log.error("Failed to save transaction for PayPal order={}", orderId);
                    return false;
                }
                // Bind to trip (if trip != null)
                bindToTrip(tx, userId, tripId);
            }

            // Audit
            audit.log(email, "PAYMENT", note);

            // Notify
            sendPaymentNotification(order, person, gross, feeOpt, tripId);
            log.info("PayPal payment captured: orderId={}, user email={}, amount={}", orderId, email, gross);
            return true;
        } catch (final Exception ex) {
            log.error("Error capturing PayPal order={} for user={}", orderId, email, ex);
            return false;
        }
    }

    /**
     * Estimates the PayPal credit card processing fee for the given amount using
     * {@link #FEE_RATE} + {@link #FEE_FIXED} (e.g. US rate of 3.49% + $0.49). The
     * actual fee may differ slightly.
     *
     * @param amount Gross amount in USD.
     * @return Estimated fee.
     */
    public BigDecimal estimateFee(final BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(FEE_RATE).add(FEE_FIXED);
    }

    /**
     * Returns the estimated net amount received after PayPal fees.
     *
     * @param amount Gross amount in USD.
     * @return Estimated net in USD.
     */
    public BigDecimal estimateNet(final BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.subtract(estimateFee(amount));
    }

    // -------------------------------------------------------------------------
    // Legacy methods (kept for backwards compatibility)
    // -------------------------------------------------------------------------

    public ApiResponse<Order> startOrder(
            final Person payer,
            final Person.Id id,
            final Float amount,
            final String invoiceId,
            final String orgAbbr,
            final String description) {
        val invoice = (invoiceId == null || invoiceId.isEmpty()) ? genInvoiceId() : invoiceId;
        return PayPalClient.getInstance()
                .createOrder(payer, id, amount, invoiceId, orgAbbr, description, null, null)
                .orTimeout(5_000, TimeUnit.MILLISECONDS)
                .join();
    }

    // Not used
    public ApiResponse<Order> completeOrder(final String orderId) {
        return PayPalClient.getInstance()
                .captureOrder(orderId)
                .orTimeout(5_000, TimeUnit.MILLISECONDS)
                .join();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String genInvoiceId() {
        // Get the current date and time
        val now = LocalDateTime.now();
        // Define the timestampPattern for the timestamp part: YYYYMMDDhhmm
        val timestamp = now.format(timestampPattern);
        // Append a random 5-digit number (0-99999), formatted with leading zeros
        val num = String.format("%05d", ThreadLocalRandom.current().nextInt(100000));
        return timestamp + "-" + num;
    }

    /**
     * Builds the return or cancel URL from the current servlet request URL.
     * PayPal will append {@code ?token=<orderId>&PayerID=<id>} to the returnUrl.
     *
     * @param tripId      Optional trip ID to preserve in the URL.
     * @param isCancelUrl If {@code true}, appends {@code &cancelled=true}.
     */
    private String buildCurrentUrl(final String tripId, final boolean isCancelUrl) {
        final FacesContext fc = FacesContext.getCurrentInstance();
        final HttpServletRequest req = (HttpServletRequest) fc.getExternalContext().getRequest();
        final StringBuilder url = new StringBuilder(req.getRequestURL());
        boolean hasParam = false;
        if (tripId != null && !tripId.isEmpty()) {
            url.append("?trip=").append(tripId);
            hasParam = true;
        }
        if (isCancelUrl) {
            url.append(hasParam ? "&" : "?").append("cancelled=true");
        }
        return url.toString();
    }

    private String buildTransactionNote(final Order order, final float gross, final Optional<Float> feeOpt) {
        final StringBuilder note = new StringBuilder("PayPal for ")
                .append(String.format("$%.2f", gross));
        feeOpt.ifPresent(fee -> note.append(String.format(" with $%.2f PayPal fee", fee)));
        note.append(".\n")
            .append(getDescription(order, ""))
            .append(" \n(").append(getPaymentId(order)).append(")");
        return note.toString();
    }

    private void bindToTrip(final Transaction tx, final Person.Id userId, final String tripId) {
        if (tripId == null || tripId.isEmpty()) {
            return;
        }
        final String txBindKey = bindCmds.key(userId.getValue(), tx.getTxId());
        bindCmds.setBindings(txBindKey, BindingType.TRANSACTION, BindingType.TRIP, List.of(tripId), true);
    }

    private void sendPaymentNotification(
            final Order order, final Person user, final float gross,
            final Optional<Float> feeOpt, final String tripId) {
        try {
            final String subject = String.format("PayPal payment received – $%.2f", gross);
            final String feeInfo = feeOpt.map(f -> String.format(" (PayPal fee: $%.2f, net: $%.2f)", f, gross - f))
                    .orElse("");
            final String body = String.format("PayPal credit card payment received.<br/>\n"
                        + "Person Registered: <b>%s</b><br/>\n"
                        + "Paid by: <div style=\"display:inline-block;vertical-align:top;\">%s</div><br/>\n"
                        + "Amount: $%.2f%s<br/>\n"
                        + "Trip: %s<br/>\n"
                        + "PayPal Payment ID: %s<br/>\n",
                        //+ "Note: %s<br/>\n",
                    (user == null) ? "null" : user.getEmail(),
                    getPayer(order),
                    gross,
                    feeInfo,
                    (tripId != null) ? trips.getTrip(tripId).getTitle() : "N/A",
                    getPaymentId(order)
                    /*getDescription(order, "[empty]")*/);
            mail.send(FROM_ADDRESS, NOTIFY_EMAIL, "ken@centerforpeacewest.com", NOTIFY_EMAIL, subject, body);
        } catch (final Exception ex) {
            log.warn("Failed to send payment notification email for order {}", getPaymentId(order), ex);
        }
    }

    private String getPayer(final Order order) {
        final Optional<ShippingWithTrackingDetails> shipping = getPurchaseUnit(order).map(PurchaseUnit::getShipping);
        final StringBuilder builder = new StringBuilder();
        // Name
        builder.append(shipping.map(sh -> sh.getName().getFullName()).orElse(""));
        // Email
        builder.append('(').append(getEmail(order)).append(')');
        builder.append("<br/>\n");
        // Address
        builder.append(shipping
                .map(ShippingWithTrackingDetails::getAddress)
                .map(this::formatAddress)
                .orElse(""));
        return builder.toString();
    }

    private String formatAddress(final Address address) {
        return (address.getAddressLine1() == null ? "" : address.getAddressLine1() + "<br/>\n") +
            (address.getAddressLine2() == null ? "" : address.getAddressLine2() + "<br/>\n") +
            (address.getAdminArea2() == null ? "" : address.getAdminArea2() + ", ") +
            (address.getAdminArea1() == null ? "" : address.getAdminArea1() + " ") +
            (address.getPostalCode() == null ? "" : address.getPostalCode() + " ") +
            (address.getCountryCode() == null ? "" :
                address.getCountryCode().equalsIgnoreCase("US") ? "" : address.getCountryCode());
    }

    private String getEmail(final Order order) {
        return Optional.ofNullable(order).map(Order::getPayer).map(Payer::getEmailAddress).orElse("");
    }

    private String getPaymentId(final Order order) {
        return getPurchaseUnit(order).map(PurchaseUnit::getDescription).orElse(order.getId());
    }

    private String getDescription(final Order order, final String defaultDesc) {
        return getPurchaseUnit(order)
            .filter(pu -> pu.getDescription() != null)
            .map(PurchaseUnit::getDescription)
            .orElse(defaultDesc);
    }

    private Optional<PurchaseUnit> getPurchaseUnit(final Order order) {
        if (order.getPurchaseUnits() == null || order.getPurchaseUnits().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(order.getPurchaseUnits().getFirst());
    }
}
