#!/bin/bash
#
# register-person.sh -- Registers a person for a trip exactly as the website's
# /trip/register.jsf flow would (creating their account first when needed), by
# invoking org.paulsens.trip.scripts.RegisterPerson against the real DynamoDB
# tables. Non-interactive: designed to be called from data-migration scripts.
#
# Usage:   ./register-person.sh --email who@example.com --admission full-rosen \
#              --opt 1=Yes --opt 2=None \
#              [--first Nanette --last Bonsby --sex Female --birthdate 1971-04-02] \
#              [--created 2026-03-10T13:07:00] [--discount-code speaker] \
#              [--pay-amount 295.00 --pay-date 2026-03-10T13:07:00 --pay-note "..."] \
#              [--dry-run]
# Run with --help for the full argument list.
#
# Requirements: java + mvn on PATH; AWS credentials available via the default
# AWS credential chain (env vars, ~/.aws/credentials, or SSO).
#
# Exit codes: 0 ok; 64 usage error; 3 trip not found; 4 write failed;
# 5 already registered; 70 build failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRIP_ROOT="$(dirname "$SCRIPT_DIR")"            # .../trip (maven root)
TRIP_MODULE="$TRIP_ROOT/trip"                   # .../trip/trip (war module)
CLASSES="$TRIP_MODULE/target/classes"
CLASSPATH_FILE="$TRIP_MODULE/target/register-cli-classpath.txt"
BUILD_STAMP="$TRIP_MODULE/target/register-cli-build.stamp"
MAIN_CLASS="org.paulsens.trip.scripts.RegisterPerson"

# DynamoDB region: the mir-medjugorje tables live in us-east-1 (the us-west-2
# tables visible from the default AWS profile are the OLD system's account).
# Override with TRIP_DYNAMO_REGION if they ever move. Remember to also select
# the correct AWS account, e.g. AWS_PROFILE=mirmedj.
DYNAMO_REGION="${TRIP_DYNAMO_REGION:-us-east-1}"

# Build compiled classes and a dependency classpath once; reuse until sources or
# a pom change. The stamp file records the last successful build (the classpath
# file's mtime can't be used: maven skips rewriting it when its content is
# unchanged). Delete $BUILD_STAMP (or run: mvn clean) to force a rebuild.
needs_build() {
    [ ! -d "$CLASSES" ] && return 0
    [ ! -f "$CLASSPATH_FILE" ] && return 0
    [ ! -f "$BUILD_STAMP" ] && return 0
    # Rebuild if any source file or pom is newer than the last successful build
    [ -n "$(find "$TRIP_MODULE/src/main/java" "$TRIP_MODULE/pom.xml" "$TRIP_ROOT/pom.xml" \
            -newer "$BUILD_STAMP" -print -quit 2>/dev/null)" ] && return 0
    return 1
}

if needs_build; then
    echo "Building trip module (first run or sources changed)..." >&2
    if ! (cd "$TRIP_ROOT" && mvn -q -DskipTests install \
            && mvn -q -pl trip dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE"); then
        echo "ERROR: maven build failed" >&2
        exit 70
    fi
    touch "$BUILD_STAMP"
fi

exec java -Dtrip.dynamo.region="$DYNAMO_REGION" \
    -cp "$CLASSES:$(cat "$CLASSPATH_FILE")" "$MAIN_CLASS" "$@"
