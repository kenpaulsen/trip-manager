#!/bin/bash
# Stamps `groupPeople` (the full member list of a Shared/Batch group) into legacy transaction rows
# whose `content` JSON predates membership stamping. Membership for a group = the sorted userIds of
# all non-deleted rows sharing that groupId. Idempotent -- rows that already have groupPeople are
# skipped. See docs/migrations/group-tx-membership.md. After a live run the app's tx cache is
# invalidated automatically when TRIP_APP_URL and TRIP_ADMIN_EMAIL are exported (see
# lib/cache-invalidate.sh); otherwise a manual clear-caches reminder is printed.
#
# Updates run CONCURRENCY at a time (default 25). A failed row is reported and counted but never
# aborts the run; the script exits non-zero if any row failed, and is safe to re-run to retry them.
#
# Usage: migrate-group-tx-membership.sh [--profile <p>] [--region <r>] [--table <t>]
#                                       [--concurrency <n>] [--dry-run]
set -euo pipefail

TABLE="transactions"
DRY_RUN=0
CONCURRENCY=25
while [[ $# -gt 0 ]]; do
    case "$1" in
        # Exported (not passed as flags) so the parallel workers inherit them.
        --profile)     export AWS_PROFILE="$2"; shift 2 ;;
        --region)      export AWS_DEFAULT_REGION="$2"; shift 2 ;;
        --table)       TABLE="$2"; shift 2 ;;
        --concurrency) CONCURRENCY="$2"; shift 2 ;;
        --dry-run)     DRY_RUN=1; shift ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

. "$(dirname "$0")/lib/cache-invalidate.sh"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export WORK TABLE

echo "Scanning table '$TABLE'..."
aws dynamodb scan --table-name "$TABLE" \
    --projection-expression "userId, txId, content" --output json > "$WORK/scan.json"

# One compact JSON record per line for each row needing a fix. Records must NOT go through @tsv:
# it escapes backslashes, which would double every backslash inside `content` (any note holding a
# quote is stored as \" ) and write back invalid JSON.
jq -c '
    [.Items[] | {uid: .userId.S, txId: .txId.S, c: (.content.S | fromjson)}] as $rows |
    ($rows
        | map(select((.c.groupId // null) != null and (.c.deleted // null) == null))
        | group_by(.c.groupId)
        | map({key: .[0].c.groupId, value: (map(.uid) | unique | sort)})
        | from_entries) as $members |
    $rows[]
    | select((.c.groupId // null) != null)
    | select((.c.deleted // null) == null)
    | select((.c.groupPeople // []) | length == 0)
    | select(($members[.c.groupId] // []) | length > 0)
    | {uid: .uid, txId: .txId, content: ((.c + {groupPeople: $members[.c.groupId]}) | tojson)}
    ' "$WORK/scan.json" > "$WORK/records.json"

COUNT=$(wc -l < "$WORK/records.json" | tr -d ' ')
echo "Rows needing groupPeople: $COUNT"
if [[ "$COUNT" -eq 0 ]]; then
    echo "Nothing to do."
    exit 0
fi

if [[ $DRY_RUN -eq 1 ]]; then
    jq -r '"WOULD STAMP userId=\(.uid) txId=\(.txId) groupPeople=\(.content | fromjson | .groupPeople)"' \
        "$WORK/records.json"
    echo "Done. (dry run -- nothing written)"
    exit 0
fi

apply_one() {
    local rec="$1" uid txid people key vals err
    IFS=$'\t' read -r uid txid people < <(
        jq -r '[.uid, .txId, (.content | fromjson | .groupPeople | join(","))] | @tsv' <<< "$rec")
    key=$(jq -c '{userId: {S: .uid}, txId: {S: .txId}}' <<< "$rec")
    vals=$(jq -c '{":c": {S: .content}}' <<< "$rec")
    err=$(mktemp "$WORK/err.XXXXXX")
    if aws dynamodb update-item --table-name "$TABLE" --key "$key" \
            --update-expression "SET content = :c" \
            --expression-attribute-values "$vals" >/dev/null 2>"$err"; then
        echo "STAMPED userId=$uid txId=$txid groupPeople=[$people]"
    else
        echo "FAILED  userId=$uid txId=$txid -- $(tr '\n' ' ' < "$err")" >&2
        mktemp "$WORK/failed.XXXXXX" >/dev/null
    fi
    rm -f "$err"
}
export -f apply_one

echo "Updating $COUNT rows, $CONCURRENCY at a time..."
# NUL-delimited so no record is ever word-split or quote-mangled by xargs.
tr '\n' '\0' < "$WORK/records.json" |
    xargs -0 -P "$CONCURRENCY" -n 1 bash -c 'apply_one "$0"'

FAILED=$(find "$WORK" -name 'failed.*' -type f | wc -l | tr -d ' ')
echo "Done. Updated $((COUNT - FAILED)) of $COUNT rows."
trip_invalidate_cache tx
if [[ "$FAILED" -gt 0 ]]; then
    echo "$FAILED row(s) FAILED -- see the messages above; re-run to retry just those." >&2
    exit 1
fi
