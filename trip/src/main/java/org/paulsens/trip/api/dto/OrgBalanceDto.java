package org.paulsens.trip.api.dto;

import org.paulsens.trip.action.LedgerCommands;

/**
 * A person's balance with ONE organization ({@code GET /api/transactions/people/{id}/balances}).
 *
 * <p>{@code balance} is the exact sum of the person's shares (negative: they owe); {@code balanceCents} is that
 * rounded once, which is the figure to show and to test against zero. {@code state} is {@code OWES},
 * {@code CREDIT}, {@code PAID_UP} or {@code NO_ACTIVITY} (an organization the person has a trip with and no
 * transactions yet). {@code orgId} is {@code "unassigned"} for rows that carry no organization, which only the
 * person, their family manager and a site administrator are ever shown.
 */
public record OrgBalanceDto(
        String orgId,
        String orgName,
        double balance,
        long balanceCents,
        String state,
        int transactions) {

    public static OrgBalanceDto of(final LedgerCommands.OrgBalance balance) {
        return new OrgBalanceDto(balance.orgId(), balance.orgName(), balance.balance(), balance.balanceCents(),
                balance.state().name(), balance.rowCount());
    }
}
