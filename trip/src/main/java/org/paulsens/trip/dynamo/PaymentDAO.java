package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Payment;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Payment attempts (PK paymentId). Deliberately UNCACHED, like the credentials and remember-me DAOs: every
 * read either authorizes a capture or steers money, and a stale answer here double-charges. Volume is tiny
 * (a handful of rows per trip), so the reconciliation listing is a filtered scan, not an index.
 *
 * <p>Writes encode the state machine: {@link #createPayment} may only CREATE
 * ({@code attribute_not_exists}); {@link #transitionPayment} may only replace the exact stored status it was
 * told to expect. A lost race throws {@link ConditionalCheckFailedException} -- the caller re-reads and
 * discovers the other writer already advanced the payment (the concurrent return-page retry case).
 */
@Slf4j
public class PaymentDAO {
    /** Package-visible so {@link InMemoryPersistence} can register the table for local mode. */
    static final String PAYMENTS_TABLE = "payments";
    static final String PAYMENT_ID = "paymentId";
    static final String STATUS_ATTR = "paymentStatus";
    private static final String CONTENT = "content";

    private final ObjectMapper mapper;
    private final Persistence persistence;

    protected PaymentDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
    }

    protected Boolean createPayment(final Payment payment) throws IOException {
        final Map<String, AttributeValue> map = itemFor(payment);
        return persistence.putItem(b -> b.tableName(PAYMENTS_TABLE).item(map)
                        .conditionExpression("attribute_not_exists(#i)")
                        .expressionAttributeNames(Map.of("#i", PAYMENT_ID)))
                .sdkHttpResponse().isSuccessful();
    }

    /**
     * Persists the payment's CURRENT state, conditioned on the stored status still being
     * {@code expectedStatus}. The object's status field should already hold the NEW status.
     */
    protected Boolean transitionPayment(final Payment payment, final Payment.Status expectedStatus)
            throws IOException {
        final Map<String, AttributeValue> map = itemFor(payment);
        return persistence.putItem(b -> b.tableName(PAYMENTS_TABLE).item(map)
                        .conditionExpression("#s = :expected")
                        .expressionAttributeNames(Map.of("#s", STATUS_ATTR))
                        .expressionAttributeValues(Map.of(":expected",
                                AttributeValue.builder().s(expectedStatus.name()).build())))
                .sdkHttpResponse().isSuccessful();
    }

    protected Optional<Payment> getPayment(final String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            return Optional.empty();
        }
        final Map<String, AttributeValue> key =
                Map.of(PAYMENT_ID, AttributeValue.builder().s(paymentId).build());
        final AttributeValue content =
                persistence.getItem(b -> b.key(key).tableName(PAYMENTS_TABLE).build()).item().get(CONTENT);
        return Optional.ofNullable(content == null ? null : parsePayment(content.s()));
    }

    /** Every stored payment (tiny table); the command layer filters for the reconciliation view. */
    protected List<Payment> getAllPayments() {
        return persistence.scanAll(b -> b.consistentRead(true).limit(1000).tableName(PAYMENTS_TABLE).build())
                .stream()
                .map(item -> item.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parsePayment(content.s()))
                .filter(payment -> payment != null)
                .toList();
    }

    private Map<String, AttributeValue> itemFor(final Payment payment) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(PAYMENT_ID, AttributeValue.builder().s(payment.getPaymentId()).build());
        map.put(STATUS_ATTR, AttributeValue.builder().s(payment.getStatus().name()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(payment)).build());
        return map;
    }

    private Payment parsePayment(final String json) {
        try {
            return mapper.readValue(json, Payment.class);
        } catch (final IOException ex) {
            log.error("Unable to parse payment record: " + json, ex);
            return null;
        }
    }
}
