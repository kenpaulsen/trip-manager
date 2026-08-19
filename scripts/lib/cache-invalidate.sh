# Shared helper for migration scripts that write DynamoDB behind the application's back: tells the
# running app to invalidate a cache scope so the change is visible immediately, instead of whenever the
# soft-TTL poll heals it. Source this file, then call `trip_invalidate_cache <scope>` after a live run.
#
# Scopes are DAO.CacheScope names (case-insensitive): person, family, trip, trip_event, reg, tx, todo,
# pdv, priv, config, media, template, content, org, binding, all.
#
# Opt-in and BEST-EFFORT by design: it needs TRIP_APP_URL (e.g. https://example.org) plus an admin login
# (TRIP_ADMIN_EMAIL; password from TRIP_ADMIN_PASSWORD or an interactive prompt, never echoed or stored
# on disk). Anything missing or failing prints the old manual instruction and returns 0 -- a cache that
# stays warm a few hours is never worth failing a completed migration over.
#
# Auth is a plain admin session (the only mechanism the API has); a non-interactive machine token is
# future work tracked in trip/docs/caching.md.

trip_invalidate_cache() {
    local scope="$1"
    if [[ -z "${TRIP_APP_URL:-}" ]]; then
        _trip_cache_manual_reminder "TRIP_APP_URL is not set"
        return 0
    fi
    if [[ -z "${TRIP_ADMIN_EMAIL:-}" ]]; then
        _trip_cache_manual_reminder "TRIP_ADMIN_EMAIL is not set"
        return 0
    fi
    local pw="${TRIP_ADMIN_PASSWORD:-}"
    if [[ -z "$pw" ]]; then
        if [[ ! -t 0 ]]; then
            _trip_cache_manual_reminder "no TRIP_ADMIN_PASSWORD and no terminal to prompt on"
            return 0
        fi
        read -r -s -p "Admin password for $TRIP_ADMIN_EMAIL (cache invalidation): " pw; echo
    fi

    local jar rc=0
    jar=$(mktemp)
    # Login -> invalidate -> logout, one cookie jar, torn down whatever happens. jq -n builds the login
    # body so an exotic password can never break out of the JSON.
    if jq -n --arg e "$TRIP_ADMIN_EMAIL" --arg p "$pw" '{email: $e, password: $p}' |
        curl -sf -c "$jar" -H 'Content-Type: application/json' -d @- \
            "$TRIP_APP_URL/api/auth/login" > /dev/null; then
        if curl -sf -b "$jar" -H 'X-Trip-Api: 1' -H 'Content-Type: application/json' \
                -d "{\"scope\":\"$scope\"}" "$TRIP_APP_URL/api/cache/invalidate" > /dev/null; then
            echo "Cache scope '$scope' invalidated via $TRIP_APP_URL."
        else
            rc=1
        fi
        curl -sf -b "$jar" -H 'X-Trip-Api: 1' -X POST "$TRIP_APP_URL/api/auth/logout" > /dev/null || true
    else
        rc=1
    fi
    rm -f "$jar"
    if [[ "$rc" -ne 0 ]]; then
        _trip_cache_manual_reminder "the API call failed"
    fi
    return 0
}

_trip_cache_manual_reminder() {
    echo "NOTE: cache not invalidated ($1)." >&2
    echo "      Log in as an admin and press 'Clear caches' on the admin Settings page," >&2
    echo "      or wait for the soft-TTL refresh. (Set TRIP_APP_URL + TRIP_ADMIN_EMAIL to automate.)" >&2
}
