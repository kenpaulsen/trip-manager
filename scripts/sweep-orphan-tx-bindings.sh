#!/bin/bash
# Deletes TRANSACTION bindings that point at a transaction which no longer exists.
#
# A binding is one row per DIRECTION (BindingDAO: bidirectional means two independent rows), keyed by
# (id1, id2), each side stored as "{typeId}_{id}" -- TRANSACTION is 4 and composite, so a transaction side
# reads `4_{userId},{txId}`. Deleting a transaction used to leave both of its rows behind: nothing removed
# a TRANSACTION->TRIP or TRANSACTION->TRIP_EVENT edge, and the trip-side ledger just null-checks the miss,
# so the orphans were invisible and accumulated for the life of the trip. As of 2026-09-17 every delete
# path drops its own bindings (TransactionsCommands.deleteRow); this sweeps up what the old ones left.
#
# Both directions are handled by one pass: the scan sees every row, and a row is judged by whichever of
# its sides is a TRANSACTION, then deleted by its own (id1, id2). Deleted transactions are never revived
# (TransactionDAO filters `deleted` out of every load, and a re-added group member gets a NEW txId), so an
# orphan can never become live again.
#
# DRY RUN BY DEFAULT -- unlike the additive migrations, this one removes rows. Review the listing, then
# re-run with --apply. Deletions run CONCURRENCY at a time; a failed row is reported and counted but never
# aborts the run, the script exits non-zero if any failed, and re-running retries exactly those.
#
# UNDO: every run writes a JOURNAL (--journal, default ./orphan-tx-bindings-journal-<stamp>.json) holding
# the COMPLETE rows it is about to delete, one DynamoDB item per line, written before anything is deleted.
# A binding row is exactly four string attributes (id1, id1_type, id2, id2_type) and nothing else, so that
# journal is a lossless copy and `--restore-from <journal>` puts every row back byte for byte. Restoring is
# idempotent: a row that is already there is overwritten with the same content. Keep the journal until you
# are satisfied -- it is the cheap, precise undo. PITR (35 days, table-wide, restores to a NEW table) is
# the fallback for the case where the journal itself is lost; see docs/migrations/orphan-tx-bindings.md.
#
# Two classes of orphan, reported separately because the confidence differs:
#   DEAD    -- the transaction row exists and carries `deleted`. Provably gone. Swept by default.
#   MISSING -- no transaction row at all (hard-deleted, or a binding written against another environment's
#              table). Needs --include-missing, since "I could not find it" is a weaker claim than "I can
#              see that it is deleted".
#
# Usage: sweep-orphan-tx-bindings.sh [--profile <p>] [--region <r>] [--bindings-table <t>]
#                                    [--transactions-table <t>] [--concurrency <n>]
#                                    [--include-missing] [--journal <file>] [--apply]
#        sweep-orphan-tx-bindings.sh --restore-from <journal> [--profile <p>] [--region <r>]
#                                    [--bindings-table <t>] [--concurrency <n>]
set -euo pipefail

BINDINGS_TABLE="bindings"
TX_TABLE="transactions"
CONCURRENCY=25
INCLUDE_MISSING=0
APPLY=0
RESTORE_FROM=""
JOURNAL="./orphan-tx-bindings-journal-$(date -u +%Y%m%dT%H%M%SZ).json"
while [[ $# -gt 0 ]]; do
    case "$1" in
        # Exported (not passed as flags) so the parallel workers inherit them.
        --profile)            export AWS_PROFILE="$2"; shift 2 ;;
        --region)             export AWS_DEFAULT_REGION="$2"; shift 2 ;;
        --bindings-table)     BINDINGS_TABLE="$2"; shift 2 ;;
        --transactions-table) TX_TABLE="$2"; shift 2 ;;
        --concurrency)        CONCURRENCY="$2"; shift 2 ;;
        --include-missing)    INCLUDE_MISSING=1; shift ;;
        --journal)            JOURNAL="$2"; shift 2 ;;
        --restore-from)       RESTORE_FROM="$2"; shift 2 ;;
        --apply)              APPLY=1; shift ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

. "$(dirname "$0")/lib/cache-invalidate.sh"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export WORK BINDINGS_TABLE

# Restore is a standalone mode: it needs neither scan, because the journal already holds whole rows.
if [[ -n "$RESTORE_FROM" ]]; then
    [[ -s "$RESTORE_FROM" ]] || { echo "Journal '$RESTORE_FROM' is missing or empty." >&2; exit 1; }
    # Refuse anything that is not a table row, rather than putting a malformed item into the bindings table.
    # Counted, not `jq -e`: that exits 4 when a filter yields NO output, which is the healthy case here.
    BAD=$(jq -s '[.[] | select((type != "object")
            or (((.id1.S? // null) | type) != "string") or (((.id2.S? // null) | type) != "string")
            or (((.id1_type.S? // null) | type) != "string")
            or (((.id2_type.S? // null) | type) != "string"))] | length' "$RESTORE_FROM" 2>/dev/null \
        || echo "unparsable")
    if [[ "$BAD" == "unparsable" ]]; then
        echo "Refusing '$RESTORE_FROM': it is not one JSON item per line." >&2
        exit 1
    fi
    if [[ "$BAD" != "0" ]]; then
        echo "Refusing '$RESTORE_FROM': $BAD line(s) are not a binding item with the four string keys." >&2
        exit 1
    fi
    RCOUNT=$(wc -l < "$RESTORE_FROM" | tr -d ' ')
    echo "Restoring $RCOUNT binding rows into '$BINDINGS_TABLE' from $RESTORE_FROM, $CONCURRENCY at a time..."
    put_one() {
        local rec="$1" err
        err=$(mktemp "$WORK/err.XXXXXX")
        # PutItem is an overwrite, so a row that never went is simply rewritten with the same content.
        if aws dynamodb put-item --table-name "$BINDINGS_TABLE" --item "$rec" >/dev/null 2>"$err"; then
            echo "RESTORED $(jq -r '"id1=\(.id1.S) id2=\(.id2.S)"' <<< "$rec")"
        else
            echo "FAILED   $(jq -r '"id1=\(.id1.S) id2=\(.id2.S)"' <<< "$rec") -- $(tr '\n' ' ' < "$err")" >&2
            mktemp "$WORK/failed.XXXXXX" >/dev/null
        fi
        rm -f "$err"
    }
    export -f put_one
    jq -c . "$RESTORE_FROM" | tr '\n' '\0' | xargs -0 -P "$CONCURRENCY" -n 1 bash -c 'put_one "$0"'
    RFAILED=$(find "$WORK" -name 'failed.*' -type f | wc -l | tr -d ' ')
    echo "Done. Restored $((RCOUNT - RFAILED)) of $RCOUNT rows."
    trip_invalidate_cache binding
    if [[ "$RFAILED" -gt 0 ]]; then
        echo "$RFAILED row(s) FAILED -- re-run to retry; PutItem is idempotent." >&2
        exit 1
    fi
    exit 0
fi

echo "Scanning '$TX_TABLE' and '$BINDINGS_TABLE'..."
aws dynamodb scan --table-name "$TX_TABLE" \
    --projection-expression "userId, txId, content" --output json > "$WORK/tx.json"
aws dynamodb scan --table-name "$BINDINGS_TABLE" --output json > "$WORK/bind.json"

# A partial scan would read as "MISSING" for every transaction it failed to fetch, i.e. it would propose
# deleting live bindings. The CLI paginates on its own, so a LastEvaluatedKey here means it stopped early.
for f in tx bind; do
    if [[ "$(jq -r '.LastEvaluatedKey // "none"' "$WORK/$f.json")" != "none" ]]; then
        echo "Refusing to act on a PARTIAL scan of $f (LastEvaluatedKey present)." >&2
        exit 1
    fi
done

# Classify every binding row that has a TRANSACTION side, ONCE; the summary and the delete list are both
# derived from that. Sides are matched on id*_type rather than the "4_" prefix, since the prefix is an
# implementation detail of TypeAndId (BindingDAO); stripping it leaves exactly `userId,txId`.
jq -c --slurpfile tx "$WORK/tx.json" '
    def txKey: sub("^[0-9]+_"; "");   # what is left IS `userId,txId`
    ([$tx[0].Items[]
        | {key: (.userId.S + "," + .txId.S),
           value: (if ((.content.S | fromjson | .deleted) // null) == null then "live" else "dead" end)}]
        | from_entries) as $txState |
    .Items[]
    | . as $row
    | [ (if .id1_type.S == "TRANSACTION" then (.id1.S | txKey) else empty end),
        (if .id2_type.S == "TRANSACTION" then (.id2.S | txKey) else empty end) ] as $txSides
    | select(($txSides | length) > 0)
    | ($txSides | map($txState[.] // "missing")) as $states
    | {id1: $row.id1.S, id2: $row.id2.S,
       item: $row,                      # the COMPLETE row, which is what makes the journal a lossless undo
       edge: ($row.id1_type.S + "->" + $row.id2_type.S),
       tx: ($txSides | join("|")),
       why: (if ($states | index("live")) != null then "live"
             elif ($states | index("dead")) != null then "DEAD"
             else "MISSING" end)}
    ' "$WORK/bind.json" > "$WORK/classified.json"

echo "TRANSACTION binding rows by state (both directions counted, since each is its own row):"
jq -rs 'group_by(.why) | map("  \(.[0].why): \(length)") | .[]' "$WORK/classified.json"

jq -c --argjson includeMissing "$INCLUDE_MISSING" \
    'select(.why == "DEAD" or (.why == "MISSING" and $includeMissing == 1))' \
    "$WORK/classified.json" > "$WORK/records.json"

COUNT=$(wc -l < "$WORK/records.json" | tr -d ' ')
echo "Binding rows this run would delete: $COUNT"
if [[ "$COUNT" -eq 0 ]]; then
    echo "Nothing to do."
    exit 0
fi

# The journal is written BEFORE any delete, and deliberately NOT under $WORK, which the EXIT trap removes.
jq -c '.item' "$WORK/records.json" > "$JOURNAL"
echo "Journal (the undo for this run): $JOURNAL"

if [[ $APPLY -eq 0 ]]; then
    jq -r '"WOULD DELETE \(.why) \(.edge) id1=\(.id1) id2=\(.id2) (tx \(.tx))"' "$WORK/records.json"
    echo "Done. (dry run -- nothing deleted, and the journal above describes rows that are still there)"
    exit 0
fi

delete_one() {
    local rec="$1" id1 id2 why err
    # The key goes back EXACTLY as stored; nothing is reconstructed from the parsed halves.
    id1=$(jq -r '.id1' <<< "$rec")
    id2=$(jq -r '.id2' <<< "$rec")
    why=$(jq -r '.why' <<< "$rec")
    err=$(mktemp "$WORK/err.XXXXXX")
    if aws dynamodb delete-item --table-name "$BINDINGS_TABLE" \
            --key "$(jq -c '{id1: {S: .id1}, id2: {S: .id2}}' <<< "$rec")" >/dev/null 2>"$err"; then
        echo "DELETED $why id1=$id1 id2=$id2"
    else
        echo "FAILED  $why id1=$id1 id2=$id2 -- $(tr '\n' ' ' < "$err")" >&2
        mktemp "$WORK/failed.XXXXXX" >/dev/null
    fi
    rm -f "$err"
}
export -f delete_one

echo "Deleting $COUNT rows, $CONCURRENCY at a time..."
# NUL-delimited so no record is ever word-split or quote-mangled by xargs.
tr '\n' '\0' < "$WORK/records.json" |
    xargs -0 -P "$CONCURRENCY" -n 1 bash -c 'delete_one "$0"'

FAILED=$(find "$WORK" -name 'failed.*' -type f | wc -l | tr -d ' ')
echo "Done. Deleted $((COUNT - FAILED)) of $COUNT rows."
echo "To put them all back:  $0 --restore-from $JOURNAL"
trip_invalidate_cache binding
if [[ "$FAILED" -gt 0 ]]; then
    echo "$FAILED row(s) FAILED -- see the messages above; re-run to retry just those." >&2
    exit 1
fi
