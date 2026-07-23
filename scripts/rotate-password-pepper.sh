#!/bin/bash
# Rotates the password pepper stored in AWS Secrets Manager, and (with --migrate) sweeps any remaining
# plaintext passwords in the `pass` table up to the current pepper. These are two distinct, mutually
# exclusive operations; a single run touches the secret at most once.
#
# ROTATE (default): reads the current secret, adds the new key as version N+1, and RETAINS every prior
#   key so existing hashes keep verifying and re-pepper themselves on each user's next login. It never
#   drops old keys (a hard replace would lock out everyone whose hash used an older pepper), and it
#   cannot re-hash existing hashes in bulk -- that needs the plaintext, which isn't stored. New key
#   defaults to a fresh `openssl rand -base64 32`; it is validated as real base64 before anything is
#   written (guarding against storing an unexpanded "$KEY").
#
# MIGRATE (--migrate): runs org.paulsens.trip.dynamo.PasswordMigration, which hashes only the rows that
#   are still plaintext (already-hashed rows are left alone). The BCrypt+HMAC envelope must match the
#   app exactly, so this cannot be done in shell -- it shells out to the built Java tool, which reads the
#   pepper ONCE and reuses it across all --parallel workers. Requires a built app (mvn install) so the
#   classpath under target/ROOT exists.
#
# Both modes DRY RUN by default and write only with --apply.
#
# Usage:
#   rotate-password-pepper.sh [--apply] [--region <r>] [--secret <name>] [--profile <p>] [--value <b64>]
#   rotate-password-pepper.sh --migrate [--apply] [--region <r>] [--secret <name>] [--profile <p>]
#                             [--parallel <n>] [--app-root <dir>]
set -euo pipefail

REGION="us-west-2"
SECRET="trip/password-pepper"
PROFILE=""
VALUE=""            # rotation: the new key (base64); empty => generate a fresh random one
PARALLEL=8          # migration: rows hashed/written at a time
APP_ROOT=""         # migration: exploded WAR root (default: this repo's target/ROOT)
MIGRATE=0
APPLY=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() { sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --region)          REGION="$2"; shift 2 ;;
        --secret|--secret-name) SECRET="$2"; shift 2 ;;
        --profile)         PROFILE="$2"; shift 2 ;;
        --value|--key)     VALUE="$2"; shift 2 ;;
        --parallel|--concurrency) PARALLEL="$2"; shift 2 ;;
        --app-root)        APP_ROOT="$2"; shift 2 ;;
        --migrate)         MIGRATE=1; shift ;;
        --apply)           APPLY=1; shift ;;
        -h|--help)         usage 0 ;;
        *) echo "Unknown arg: $1" >&2; usage 1 >&2 ;;
    esac
done

# The AWS CLI and the Java tool both pick these up; exporting keeps the two modes pointing at one place.
export AWS_DEFAULT_REGION="$REGION"
[[ -n "$PROFILE" ]] && export AWS_PROFILE="$PROFILE"

command -v aws >/dev/null || { echo "aws CLI is required" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------------------
if [[ "$MIGRATE" -eq 1 ]]; then
    command -v java >/dev/null || { echo "java is required for --migrate" >&2; exit 1; }
    [[ -z "$APP_ROOT" ]] && APP_ROOT="$SCRIPT_DIR/../trip/target/ROOT"
    CLASSES="$APP_ROOT/WEB-INF/classes"
    if [[ ! -d "$CLASSES" ]]; then
        echo "Built classes not found at '$CLASSES'." >&2
        echo "Build first (cd trip && mvn install) or pass --app-root <exploded-WAR-root>." >&2
        exit 1
    fi
    CP="$CLASSES:$APP_ROOT/WEB-INF/lib/*"
    # The Java tool reads the pepper via this secret name and targets DynamoDB in this region.
    export TRIP_PASSWORD_PEPPER_SECRET="$SECRET"
    export TRIP_DYNAMO_REGION="$REGION"
    JAVA_ARGS=(--parallel "$PARALLEL")
    if [[ "$APPLY" -eq 1 ]]; then
        echo "Migrating plaintext passwords (region '$REGION', pepper secret '$SECRET', ${PARALLEL} at a time)..."
        JAVA_ARGS+=(--apply)
    else
        echo "DRY RUN migration (pass --apply to write). Region '$REGION', pepper secret '$SECRET'."
    fi
    exec java -cp "$CP" org.paulsens.trip.dynamo.PasswordMigration "${JAVA_ARGS[@]}"
fi

# ---------------------------------------------------------------------------------------------------------
# ROTATE
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

[[ -z "$VALUE" ]] && VALUE="$(openssl rand -base64 32)"
# Fail closed if the new key is not strictly valid base64 (e.g. an unexpanded "$KEY"); otherwise the app's strict
# decoder would reject it at startup. `openssl base64 -d` is too lenient (it skips stray characters), so match the
# standard base64 alphabet directly, the way java.util.Base64.getDecoder() does.
if [[ ! "$VALUE" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || (( ${#VALUE} % 4 != 0 )); then
    echo "The new pepper key is not valid base64. Refusing to write it." >&2
    echo "Generate one with: openssl rand -base64 32" >&2
    exit 1
fi

CURRENT="$(aws secretsmanager get-secret-value --region "$REGION" --secret-id "$SECRET" \
    --query SecretString --output text 2>/dev/null)" || {
    echo "Could not read secret '$SECRET' in '$REGION'. Create it first (see setup-instructions.txt)." >&2
    exit 1
}

# Build the next secret value: bump the version, retain every prior key (including the old current one).
NEW_JSON="$(jq -cn --arg cur "$CURRENT" --arg newkey "$VALUE" '
    ($cur | gsub("^\\s+|\\s+$"; "")) as $c
    | (if ($c | startswith("{")) then ($c | fromjson) else {version: 1, key: $c} end) as $obj
    | ($obj.version // 1) as $ver
    | (($obj.keys // {}) + (if ($obj.key // null) != null then {($ver | tostring): $obj.key} else {} end)) as $keep
    | {version: ($ver + 1), key: $newkey, keys: $keep}
')"

OLD_VER="$(jq -r '.keys | keys | map(tonumber) | max // 0' <<< "$NEW_JSON")"
NEW_VER="$(jq -r '.version' <<< "$NEW_JSON")"
RETAINED="$(jq -r '.keys | keys | map(tonumber) | sort | join(", ")' <<< "$NEW_JSON")"

echo "Secret:   $SECRET  (region $REGION)"
echo "Rotate:   version $OLD_VER -> $NEW_VER"
echo "Retained: versions [$RETAINED] (old hashes still verify; they re-pepper on next login)"
echo "New key:  $(printf '%s' "$VALUE" | wc -c | tr -d ' ') base64 chars (value not shown)"

if [[ "$APPLY" -ne 1 ]]; then
    echo "DRY RUN -- nothing written. Re-run with --apply to store the new version."
    exit 0
fi

aws secretsmanager put-secret-value --region "$REGION" --secret-id "$SECRET" \
    --secret-string "$NEW_JSON" --query 'VersionId' --output text >/dev/null
echo "Done. Restart the app so it loads pepper version $NEW_VER. Existing logins keep working;"
echo "each re-peppers on the user's next successful login. Run with --migrate to sweep any plaintext rows."
