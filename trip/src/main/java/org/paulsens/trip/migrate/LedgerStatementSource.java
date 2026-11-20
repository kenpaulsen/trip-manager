package org.paulsens.trip.migrate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.paulsens.trip.action.LedgerCommands;
import org.paulsens.trip.action.LedgerStatements;
import org.paulsens.trip.migrate.LedgerBindingVerifier.OrgStatement;
import org.paulsens.trip.migrate.LedgerData.PersonName;
import org.paulsens.trip.migrate.LedgerData.TripInfo;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;

/**
 * The per-organization statement code ({@link LedgerStatements}, what {@code LedgerCommands.statement} and the
 * Balance page run) over a {@link LedgerData}: at system reach, for a viewer who sees every organization. This is
 * how verify's checks 1, 2 and 4 are a proof about the app's own code although the tool reads a dump: no store, no
 * cache and no request stands behind any answer here, only the parsed tables.
 *
 * <p>A partition is handed over WHOLE, soft-deleted rows included, so that leaving them off a statement is the
 * statement code's doing and check 4 would name one that slipped through.
 */
final class LedgerStatementSource implements LedgerStatements.Source {
    private final LedgerData data;

    LedgerStatementSource(final LedgerData data) {
        this.data = data;
    }

    /** The verifier's seam over this data; bindings are read at each call, so a rehearsal's writes are seen. */
    static LedgerBindingVerifier.Statements statements(final LedgerData data) {
        return new LedgerStatementSource(data)::of;
    }

    /** One partition's statements, by organization ("" for rows that carry none, as the snapshot keys them). */
    Map<String, OrgStatement> of(final String partition) {
        final LedgerStatements ledger = new LedgerStatements(this, List.of(Person.Id.from(partition)));
        final Map<String, OrgStatement> byOrg = new TreeMap<>();
        for (final String orgKey : ledger.balances().keySet()) {
            final LedgerCommands.Statement statement = ledger.statement(orgKey);
            final List<String> txIds = new ArrayList<>();
            statement.trips().forEach(group -> group.rows().forEach(row -> txIds.add(row.txId())));
            byOrg.put(LedgerCommands.UNASSIGNED_ORG_ID.equals(orgKey) ? "" : orgKey,
                    new OrgStatement(statement.balance(), txIds));
        }
        return byOrg;
    }

    @Override
    public List<Transaction> partition(final Person.Id subject) {
        return data.allRowsOf(subject.getValue()).stream().map(LedgerData.Row::tx).toList();
    }

    @Override
    public boolean sees(final Person.Id subject, final String orgId) {
        return true;
    }

    /** Only trip bindings are in the tables this tool reads; an event binding places no row on a statement. */
    @Override
    public List<String> bindings(final String rowKey, final BindingType destType) {
        return destType == BindingType.TRIP ? data.tripsOf(rowKey) : List.of();
    }

    @Override
    public Trip trip(final String tripId) {
        final TripInfo info = data.trips().get(tripId);
        if (info == null) {
            return null;
        }
        final Trip trip = Trip.builder().id(info.id()).build();
        trip.setTitle(info.title());
        trip.setOrgId(info.orgId());
        trip.setStartDate(info.start() == null ? null : info.start().atStartOfDay());
        trip.setEndDate(info.end() == null ? null : info.end().atStartOfDay());
        return trip;
    }

    @Override
    public String orgName(final String orgId) {
        return data.orgName(orgId);
    }

    @Override
    public String personName(final Person.Id personId) {
        final PersonName name = data.person(personId.getValue());
        return name == null ? personId.getValue() : (name.first() + " " + name.last()).trim();
    }

    @Override
    public String preferredName(final Person.Id personId) {
        final PersonName name = data.person(personId.getValue());
        return name == null || name.first() == null ? "" : name.first();
    }

    /** No payment configuration is in a dump, and the proof is about balances and rows, not about pay buttons. */
    @Override
    public boolean payableInApp(final Trip trip) {
        return false;
    }

    @Override
    public String eventTitle(final String eventId) {
        return "";
    }
}
