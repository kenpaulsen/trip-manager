# register-person.sh — Registration Migration CLI

Registers a person for a trip **exactly** as the website's
`/trip/register.jsf?trip=...` flow would, including creating their account
first when they don't exist yet. Non-interactive, designed to be driven by
other scripts for data migration.

```
./register-person.sh --email <email> --admission <optionId> --opt <id>=<value> ... [options]
```

Requirements: `java` and `mvn` on the PATH (first run builds the trip module),
and AWS credentials **for the mir-medjugorje AWS account** available via the
default credential chain — typically `AWS_PROFILE=mirmedj ./register-person.sh ...`.
Writes go to DynamoDB in **us-east-1** (override with `TRIP_DYNAMO_REGION`).
Beware: the laptop's default AWS profile may point at the old system's account,
whose us-west-2 tables have the same names but old data.

---

## What it writes

| Table | When | Content |
|---|---|---|
| `people` | Only if no person exists with `--email` | Person record (same JSON the webapp writes) |
| `pass` | Only when the person is created | Login credentials with a random 10-char password |
| `registrations` | Always | Registration with status `Pending` |
| `transactions` + `bindings` | Only with `--pay-amount` | Payment Transaction, bound to the trip (mirrors the PayPal capture path) |

Deliberate differences from the web flow: **no emails are sent**, status is
always `Pending` (confirm via the admin UI afterwards, which triggers the
normal confirmation email), and `--discount-code` is stored as-is without
validation.

## Output (stdout, machine-readable)

```
CREATED_PERSON id=<uuid> email=<email> password=<10 chars>   # only when created — CAPTURE THIS
EXISTING_PERSON id=<uuid> email=<email>
REGISTERED trip=<tripId> status=Pending created=<ts> admission=<id>
TRANSACTION txId=<id> amount=<f> date=<ts>                   # only with --pay-amount
```

**Capture stdout in your migration wrapper** — the generated password is not
stored anywhere else in readable form.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 64 | Usage error (bad/missing arguments, unknown admission, unanswered question) |
| 3 | Trip not found |
| 4 | A write to DynamoDB failed |
| 5 | Person is already registered for this trip (safe to ignore on re-runs) |
| 70 | Maven build failed |

---

## Arguments

### Always required

| Argument | Description |
|---|---|
| `--email <email>` | Person's email (account login). Lowercased automatically. |
| `--admission <optionId>` | Admission option id defined on the trip, e.g. `full-rosen`. Validated; on error the message lists all valid ids. |

### Required only when the person does not exist yet

| Argument | Description |
|---|---|
| `--first <name>` | First name |
| `--last <name>` | Last name |

If the person already exists, these are ignored — the script never modifies
profile fields of an existing person.

### Optional

| Argument | Description |
|---|---|
| `--sex <Male\|Female>` | Sex, case-insensitive (only used when creating the person) |
| `--birthdate <yyyy-MM-dd>` | Birth date, e.g. `1971-04-02` (only used when creating the person). **Without it, `_registrantType` defaults to `adult`** — age-based child/free pricing can't be derived, so supply it when you have it. |
| `--opt <id>=<value>` | Answer to a trip registration question (see table below). Repeat once per question. All answers are optional — unanswered questions are simply omitted from the registration (no empty strings written). Ids that don't exist on the trip produce a warning but are still written. |
| `--trip <tripId>` | Trip id. Default: `cd0b85e9-6722-4ac0-831e-90b40ba3be9a` (SummerFest 2026 Orlando) |
| `--cell <phone>` | Cell phone (only used when creating the person) |
| `--created <ISO date-time>` | Registration `created` timestamp, e.g. `2026-03-02T04:48:26`. Default: now. **Use the original date for migrated data** — the badge-printing-deadline warning on confirm.xhtml is driven by this. |
| `--discount-code <text>` | Stored under `options['_discountCode']`. Does **not** need to match a configured DiscountCode — use it to tag imported user types (`speaker`, `helper`, ...). |
| `--pay-amount <amount>` | Record a payment Transaction. Accepts `295`, `295.00`, or `$295.00`. |
| `--pay-date <ISO date-time>` | Transaction date. Default: `--created`, else now. Use the original payment time. |
| `--pay-note <text>` | Transaction note. Default: `Imported payment`. Good place for the original product label, e.g. `Rosen Centre Guest Discount (Early Bird) $295`. |
| `--pay-id <id>` | Transaction id, e.g. the original PayPal id. Default: random UUID. |
| `--dry-run` | Validate everything and print what would happen; writes nothing. |
| `--help` | Print usage. |

---

## SummerFest 2026 registration questions (`--opt` ids)

These are the per-trip "Trip Options" questions for trip
`cd0b85e9-6722-4ac0-831e-90b40ba3be9a`, answered on the web form and stored
under the numeric keys of the registration's `options` map. Typical values
are taken from real registrations.

| `--opt` id | Question | Typical values |
|---|---|---|
| `0` | Part of a Group? | `no`, or the group name |
| `1` | Country of Residence? | `US`, `Canada`, ... |
| `2` | Parish / Diocese? | e.g. `St Stephen the Martyr` |
| `3` | T-Shirt Size? | `S`, `M`, `L`, `XL`, ... |
| `4` | Do you have any allergies or medical conditions? | `no`, or details |
| `5` | Do you require any special assistance? | `no`, or details |
| `6` | Do you have any dietary requirements? | `no`, or details |
| `7` | How did you hear about the Medjugorje Summer Fest? | `Facebook`, a person's name, ... |
| `8` | Do you require translation from English to Spanish? | `no`, `yes` |
| `9` | Stay at Rosen Centre Hotel? | `no`, `yes` — see note below |
| `10` | Subscribe to our email list? | `no`, `yes` |

All question answers are optional: supply what your source data has and omit
the rest — omitted questions are left out of the registration entirely (this
matches how hidden questions look in web-created registrations). Question `9`
is legacy: Rosen-ness is now encoded by the admission option.

### Reserved option keys (for reference — do not pass via `--opt`)

Older/imported registration JSON may also contain these keys. What the script
writes automatically:

| Key | Written by script? | Meaning |
|---|---|---|
| `_registrantType` | Yes (derived from birthdate) | `adult`, `child`, or `3_and_under` |
| `_admission` | Yes (`--admission`) | Chosen admission option id |
| `_discountCode` | With `--discount-code` | Discount code id / import tag |
| `_discount` | No | Legacy admin discount tag/price override (e.g. `early-bird`); set via admin UI if needed |
| `_regType` | No | Legacy registration type (e.g. `full`); not written by the current web flow |
| `opt9` | No | Legacy Rosen-Centre flag from before admission options existed |

---

## Examples

Person exists, just register them (helper, free):

```sh
./register-person.sh \
    --email helper@example.com \
    --admission full \
    --discount-code helper \
    --opt 0=no --opt 1=US --opt 2="St Mary's" --opt 3=L --opt 4=no \
    --opt 5=no --opt 6=no --opt 7=Staff --opt 8=no --opt 10=yes
```

Full migration row — person doesn't exist, paid $295 on the old system on
Mar 10, 2026 at 13:07:

```sh
./register-person.sh \
    --email nsbonsby@aol.com \
    --first Nanette --last Bonsby --sex Female --birthdate 1971-04-02 \
    --cell 301-366-7301 \
    --admission full-rosen \
    --created 2026-03-10T13:07:00 \
    --opt 0=no --opt 1=USA --opt 2="Diocese of Venice Florida" --opt 3=M \
    --opt 4=no --opt 5=N/A --opt 6=N/A --opt 7=Facebook --opt 8=no --opt 10=no \
    --pay-amount 295.00 \
    --pay-date 2026-03-10T13:07:00 \
    --pay-note 'Rosen Centre Guest Discount (Early Bird) $295' \
    --pay-id AV78WB5U622D4
```

Validate a whole import before writing anything:

```sh
./register-person.sh --dry-run --email test@example.com ... && echo "would succeed"
```

---

## Operational notes

- **Restart Tomcat after a bulk import.** The running webapp caches people
  and registrations in memory indefinitely and has no cache-refresh endpoint;
  imported rows won't appear on the site until it restarts.
- Re-running the same person is safe: exit code 5, nothing modified.
- The first run of the script (and any run after source changes) triggers a
  maven build; expect a delay. Delete
  `trip/target/register-cli-classpath.txt` to force a rebuild.
