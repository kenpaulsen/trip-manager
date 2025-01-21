package org.paulsens.trip.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.paypal.sdk.ApiHelper;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.Order;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.concurrent.TimeUnit;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.pay.PayPalClient;

@Named("pay")
@ApplicationScoped
public class PayCommands {
    /**
     * This is the first method called when making a PayPal payment. It sets up the amount PayPal should request.
     *
     * @param amount        Amount of payment.
     * @param id            Person to credit (not necessarily the payer).
     * @param payer         Optional. The Person that is paying.
     * @param invoiceId     Optional. Maybe use Trip Name in most cases? Visible to Payer.
     * @param orgAbbr       Optional. Shown on credit card statement. Perhaps: CFPW
     * @param description   Optional. Description of what charge is for.
     */
    public ApiResponse<Order> startOrder(
            final Person payer,
            final Person.Id id,
            final Float amount,         // Amount to charge
            final String invoiceId,
            final String orgAbbr,
            final String description) {
        return PayPalClient.getInstance().createOrder(payer, id, amount, invoiceId, orgAbbr, description)
                .orTimeout(5_000, TimeUnit.MILLISECONDS)
                .join();
    }

    public ApiResponse<Order> completeOrder(final String orderId) {
        return PayPalClient.getInstance().captureOrder(orderId)
                .orTimeout(5_000, TimeUnit.MILLISECONDS)
                .join();
    }

    public String serialize(final Object obj) throws JsonProcessingException {
        return ApiHelper.serialize(obj);
    }
}
