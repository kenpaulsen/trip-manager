#!/bin/bash
# Promotes each person's email (from the `content` JSON) to a top-level, lowercased `email`
# attribute on the `people` table so the email-index GSI can serve lookups without a scan.
# Deleted people / people without an email get the attribute removed (they stay out of the index).
# Idempotent -- safe to re-run. See docs/migrations/people-email-gsi.md.
#
# After a live run the app's person cache is invalidated automatically when TRIP_APP_URL and
# TRIP_ADMIN_EMAIL are exported (see lib/cache-invalidate.sh); otherwise a manual clear-caches
# reminder is printed.
#
# Usage: backfill-people-email.sh [--profile <p>] [--region <r>] [--table <t>] [--dry-run]
set -euo pipefail

TABLE="people"
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

. "$(dirname "$0")/lib/cache-invalidate.sh"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

echo "Scanning table '$TABLE'..."
# aws cli v2 auto-paginates. Emit: id <TAB> wantedEmail <TAB> currentAttr  ("-" means none)
aws "${AWS_ARGS[@]}" dynamodb scan --table-name "$TABLE" \
        --projection-expression "id, content, email" --output json |
    jq -r '.Items[] |
        (.id.S) as $id |
        (.content.S | fromjson) as $c |
        (if ($c.deleted // null) != null then "-"
         else (($c.email // "") | ascii_downcase | gsub("^\\s+|\\s+$"; "")) end) as $want |
        ((if $want == "" then "-" else $want end)) as $want |
        [$id, $want, (.email.S // "-")] | @tsv' |
while IFS=$'\t' read -r id want have; do
    if [[ "$want" == "$have" ]]; then
        continue
    fi
    if [[ "$want" == "-" ]]; then
        echo "REMOVE email attr: id=$id (was: $have)"
        [[ $DRY_RUN -eq 1 ]] && continue
        aws "${AWS_ARGS[@]}" dynamodb update-item --table-name "$TABLE" \
            --key "{\"id\":{\"S\":\"$id\"}}" \
            --update-expression "REMOVE email" >/dev/null
    else
        echo "SET email=$want: id=$id (was: $have)"
        [[ $DRY_RUN -eq 1 ]] && continue
        aws "${AWS_ARGS[@]}" dynamodb update-item --table-name "$TABLE" \
            --key "{\"id\":{\"S\":\"$id\"}}" \
            --update-expression "SET email = :e" \
            --expression-attribute-values "{\":e\":{\"S\":\"$want\"}}" >/dev/null
    fi
done
echo "Done.$([[ $DRY_RUN -eq 1 ]] && echo ' (dry run -- nothing written)')"
if [[ $DRY_RUN -eq 0 ]]; then
    trip_invalidate_cache person
fi
