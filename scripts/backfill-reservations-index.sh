#!/bin/bash
# Promotes each reservation's hotel and stay-end (from the `content` JSON) to top-level
# `accommodationId` and `stayEnd` attributes on the `lodging_reservations` table so the
# by-accommodation GSI can answer "every trip's stays at this hotel" without a table scan.
# Reservations without BOTH a hotel and an end date get both attributes removed -- the index is
# sparse on purpose, and half a key pair would index a row the query can never match.
# Idempotent -- safe to re-run. See docs/migrations/reservations-by-accommodation-gsi.md.
#
# `stayEnd` is the stored LocalDateTime truncated to minutes (yyyy-MM-ddTHH:mm), which is what
# LodgingDAO writes: the sort key is compared lexically, so every row must carry the same width or
# a BETWEEN window silently skips the odd ones out. Cancelled reservations are indexed like any
# other, deliberately -- a cancelled stay still occupied the room until it was released, and the
# DAO's own write path makes no status distinction either.
#
# `content` and `version` are never touched: this script must not look like an application write to
# the optimistic-version guard, and rewriting `content` would invalidate every cached Reservation.
#
# After a live run the app's lodging cache is invalidated automatically when TRIP_APP_URL and
# TRIP_ADMIN_EMAIL are exported (see lib/cache-invalidate.sh); otherwise a manual clear-caches
# reminder is printed.
#
# Usage: backfill-reservations-index.sh [--profile <p>] [--region <r>] [--table <t>] [--dry-run]
set -euo pipefail

TABLE="lodging_reservations"
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
# aws cli v2 auto-paginates. The key is composite, so both parts ride every line. Emit:
# tripId <TAB> id <TAB> wantAcc <TAB> wantEnd <TAB> haveAcc <TAB> haveEnd   ("-" means none)
aws "${AWS_ARGS[@]}" dynamodb scan --table-name "$TABLE" \
        --projection-expression "tripId, id, content, accommodationId, stayEnd" --output json |
    jq -r '.Items[] |
        (.content.S | fromjson) as $c |
        (($c.accommodationId // "") | tostring) as $acc |
        (($c.end // "") | tostring) as $end |
        # Minute granularity, and only from a value long enough to actually hold it -- a shorter
        # string is malformed, not truncatable, and must leave the row out of the index.
        (if ($end | length) >= 16 then ($end[0:16]) else "" end) as $end |
        (if $acc == "" or $end == "" then "-" else $acc end) as $wantAcc |
        (if $acc == "" or $end == "" then "-" else $end end) as $wantEnd |
        [.tripId.S, .id.S, $wantAcc, $wantEnd, (.accommodationId.S // "-"), (.stayEnd.S // "-")]
        | @tsv' |
while IFS=$'\t' read -r tripId id wantAcc wantEnd haveAcc haveEnd; do
    if [[ "$wantAcc" == "$haveAcc" && "$wantEnd" == "$haveEnd" ]]; then
        continue
    fi
    key="{\"tripId\":{\"S\":\"$tripId\"},\"id\":{\"S\":\"$id\"}}"
    if [[ "$wantAcc" == "-" ]]; then
        echo "REMOVE index attrs: tripId=$tripId id=$id (was: $haveAcc / $haveEnd)"
        [[ $DRY_RUN -eq 1 ]] && continue
        aws "${AWS_ARGS[@]}" dynamodb update-item --table-name "$TABLE" \
            --key "$key" \
            --update-expression "REMOVE accommodationId, stayEnd" >/dev/null
    else
        echo "SET accommodationId=$wantAcc stayEnd=$wantEnd: tripId=$tripId id=$id (was: $haveAcc / $haveEnd)"
        [[ $DRY_RUN -eq 1 ]] && continue
        aws "${AWS_ARGS[@]}" dynamodb update-item --table-name "$TABLE" \
            --key "$key" \
            --update-expression "SET accommodationId = :a, stayEnd = :e" \
            --expression-attribute-values "{\":a\":{\"S\":\"$wantAcc\"},\":e\":{\"S\":\"$wantEnd\"}}" >/dev/null
    fi
done
echo "Done.$([[ $DRY_RUN -eq 1 ]] && echo ' (dry run -- nothing written)')"
if [[ $DRY_RUN -eq 0 ]]; then
    trip_invalidate_cache lodging
fi
