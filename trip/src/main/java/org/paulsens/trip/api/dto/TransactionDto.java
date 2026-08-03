package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A financial transaction against somebody's trip account.
 *
 * <p>{@code userAmount} is this person's share of a shared or batch transaction, which is NOT the same as
 * {@code amount}: a $600 hotel bill split three ways is one transaction of 600 in which each member owes 200.
 * A client that showed {@code amount} on a traveller's ledger would tell them they owe triple.
 *
 * <p>{@code groupPeople} names everyone sharing the transaction. It is withheld from a caller who is merely one
 * of them -- who else is on a bill is trip-staff information, not something every co-payer gets a roster of.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionDto(
        String txId,
        String userId,
        String groupId,
        String type,
        String txType,
        LocalDateTime txDate,
        Float amount,
        Float userAmount,
        String category,
        String note,
        boolean deleted,
        List<String> groupPeople) {

    /** This transaction without the list of who else shares it. */
    public TransactionDto withoutGroupPeople() {
        return new TransactionDto(txId, userId, groupId, type, txType, txDate, amount, userAmount, category,
                note, deleted, null);
    }
}
