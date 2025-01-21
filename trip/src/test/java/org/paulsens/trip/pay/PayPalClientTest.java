package org.paulsens.trip.pay;

import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.Order;
import io.apimatic.core.exceptions.AuthValidationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PayPalClientTest {

    @Test(enabled = false)
    void canCreateOrder() {
        final String id = RandomData.genAlpha(5);
        final CompletionException ex = Assert.expectThrows(CompletionException.class, () -> PayPalClient.getInstance()
                .createOrder(null, Person.Id.from(id), 123.45f, "some trip", "CFPW", "blah blah blah")
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .join());
        Assert.assertEquals(ex.getCause().getClass(), AuthValidationException.class);
    }
}