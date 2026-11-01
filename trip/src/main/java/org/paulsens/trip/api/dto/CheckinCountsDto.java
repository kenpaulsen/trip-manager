package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.paulsens.trip.action.CheckinCommands;

/**
 * A trip's check-in counts ({@code GET /api/trips/{tripId}/registrations/counts}, {@code docs/checkin.md}):
 * registered = confirmed + pending; the breakdowns count CONFIRMED people only. {@code money} is present only for a
 * caller who sees the trip's finances.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckinCountsDto(int registered, int confirmed, int pending, int checkedIn, List<Bucket> byType,
        List<Bucket> byDay, Money money) {

    /** One breakdown line: expected, and how many of those are checked in. */
    public record Bucket(String label, int total, int checkedIn) {
    }

    /** What the confirmed people have paid on this trip and still owe, in cents, and how many owe anything. */
    public record Money(long paidCents, long dueCents, int paidUp, int owing) {
    }

    /** The bean's counts (and money, when given) on the wire. */
    public static CheckinCountsDto of(final CheckinCommands.Counts counts, final CheckinCommands.Money money) {
        return new CheckinCountsDto(counts.registered(), counts.confirmed(), counts.pending(), counts.checkedIn(),
                buckets(counts.byType()), buckets(counts.byDay()),
                money == null ? null : new Money(money.paidCents(), money.dueCents(), money.paidUp(), money.owing()));
    }

    private static List<Bucket> buckets(final List<CheckinCommands.Bucket> buckets) {
        return buckets.stream().map(b -> new Bucket(b.label(), b.total(), b.checkedIn())).toList();
    }
}
