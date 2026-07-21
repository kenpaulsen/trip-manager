#!/bin/bash
# Stamps `groupPeople` (the full member list of a Shared/Batch group) into legacy transaction rows
# whose `content` JSON predates membership stamping. Membership for a group = the sorted userIds of
# all non-deleted rows sharing that groupId. Idempotent -- rows that already have groupPeople are
# skipped. See docs/migrations/group-tx-membership.md (including the cache-clear step afterwards).
#
# Usage: migrate-group-tx-membership.sh [--profile <p>] [--region <r>] [--table <t>] [--dry-run]
set -euo pipefail

TABLE="transactions"
DRY_RUN=0
AWS_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --profile) AWS_ARGS+=(--profile "$2"); shift 2 ;;
        --region)  AWS_ARGS+=(--region "$2"); shift 2 ;;
        --table)   TABLE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

echo "Scanning table '$TABLE'..."
SCAN=$(aws "${AWS_ARGS[@]}" dynamodb scan --table-name "$TABLE" \
    --projection-expression "userId, txId, content" --output json)

# Emit one TSV line per row needing a fix: userId <TAB> txId <TAB> newContentJson
echo "$SCAN" | jq -r '
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
    | [.uid, .txId, ((.c + {groupPeople: $members[.c.groupId]}) | tojson)]
    | @tsv' |
while IFS=$'\t' read -r uid txid newcontent; do
    echo "STAMP groupPeople: userId=$uid txId=$txid -> $(echo "$newcontent" | jq -c '.groupPeople')"
    [[ $DRY_RUN -eq 1 ]] && continue
    KEY=$(jq -cn --arg u "$uid" --arg t "$txid" '{userId: {S: $u}, txId: {S: $t}}')
    VALS=$(jq -cn --arg c "$newcontent" '{":c": {S: $c}}')
    aws "${AWS_ARGS[@]}" dynamodb update-item --table-name "$TABLE" \
        --key "$KEY" \
        --update-expression "SET content = :c" \
        --expression-attribute-values "$VALS" >/dev/null
done
echo "Done.$([[ $DRY_RUN -eq 1 ]] && echo ' (dry run -- nothing written)')"
echo "Remember: clear the shared cache (admin action) or restart Tomcat so cached tx partitions refresh."
