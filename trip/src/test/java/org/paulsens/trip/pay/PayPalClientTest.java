package org.paulsens.trip.pay;

import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.Order;
import java.util.concurrent.TimeUnit;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PayPalClientTest {

    @Test(enabled = false)
    void canCreateOrder() {
        final String id = RandomData.genAlpha(5);
        ApiResponse<Order> order = PayPalClient.getInstance()
                .createOrder(null, Person.Id.from(id), 123.45f, "some trip", "CFPW", "blah blah blah")
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .join();
        Assert.assertNotNull(order);
    }
}