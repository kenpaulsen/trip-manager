---
paths:
  - "trip/src/main/java/org/paulsens/trip/api/**"
  - "trip/src/test/java/org/paulsens/trip/api/**"
---

# REST API (`org.paulsens.trip.api`, served at `/api/*`)

Jersey servlet (declared in the live web.xml, sibling repo) running `TripApiApplication` -- resources are
registered **explicitly** in `getClasses()`, no package scanning; a new resource must be added there (and to
`ApiSurfaceTest.RESOURCES`). Resources include auth, people, trips, registrations, transactions, todos,
privileges, chat, chat-admin, photo-chat, audit, config, mail, payments, the Stripe webhook
(`webhooks/stripe/{configId}`, deliberately not `@TripApi`: its authority is Stripe's HMAC signature over the
body, verified with that config's stored signing secret; `docs/payments.md`), deploy, lodging, family, budget and
expenses (`getClasses()` is the authoritative list) +
`TripAuthFilter`, `JsonExceptionMapper`, `ObjectMapperProvider`. DTOs in `api/dto`, MapStruct mappers in
`api/mapper`. Versioning is via the `Accept` media type, not a URL segment.

- The API is the native client's (UniteTrip iOS, `../UniteTrip`) only door, so a page feature the app needs
  gets a resource that WRAPS the page's bean -- never a second implementation. Person-scoped reads take the
  SUBJECT explicitly (`?personId=` or the path id) and authorize it with `BaseResource.subjectOf`/`actsFor`
  (self or `canAccessUserId`; site admin never implied) -- the website's acting-for without a session, and
  the one spelling of the rule. There is deliberately no act-as header: identity never changes. Where a bean
  only growled, it gained an `*Outcome` sibling that returns a coded result and the page method delegates to
  it (`FamilyCommands.FamilyResult`, `RegistrationCommands.PartyOutcome`, `ProfilePhotoCommands.PhotoResult`).
- Photo uploads on the API are RAW bodies (`image/*` or `application/octet-stream`, crop in query params),
  read through `BaseResource.readUpload`, which refuses over-cap bodies before buffering. The API servlet
  has no `multipart-config` and must not get one (Tomcat's `maxPartCount` counts ordinary fields).
- Every URL that leaves the API is absolute (`BaseResource.absoluteUrl`): a mobile client cannot resolve a
  context-relative `/profile-photos/...` the way a page can. `GET /api/media/bases` names the photo bases.
- A PAGE URL in a response (`TripDto.detailsUrl`, any new one) is built on the trip's OWNING org's site,
  `SiteUrls.baseUrlForTrip(trip, REG_MAIL_BASE_URL, config)` (the base tickets and mail use), never from the
  request host: the apps call the API on the marketing host, where `/api/` reaches every org but a page reaches
  only marketing-listed trips ("This page is not available."). Do not widen page reach in `SessionRecoveryFilter`
  instead (owner rejected it). Plain `localhost` resolves as a shared site, `www.localhost` as the marketing one.

- `PhotoChatResource` is deliberately NOT `@TripApi`: the auth filter is name-bound, so its GETs serve
  anonymous readers (comments follow the photo -- `docs/photo-comments.md`); mutations enforce the session
  themselves and answer 401 JSON. `BaseResource.personId` still recognises a bearer caller there (the
  servlet edge resolves the principal for every `/api/` request; only the filter's stamp is missing).
- `DiscoverResource` (`/api/discover/trips`, medjugorje#155) is the other one that is NOT `@TripApi`, and the only
  ANONYMOUS read that crosses organizations: GET only, sessionless (`web/SessionlessPaths` keeps the filters
  from touching a session there), rate-limited per address (`PublicRateLimiter`). It reads ONLY `DiscoverIndex`
  (trips already passed through `ListingScope.discoverable` and mapped to the allow-list `DiscoverTripDto`),
  never the DAO, a `Trip`, `TripMapper` or `personId()`. `docs/discover-tenancy-review.md` before any change.
- API authentication is dual: session cookie OR `Authorization: Bearer` (feature-gated by
  `api.token.enabled`, default off). Read `docs/api-tokens.md` before touching `TripAuthFilter`,
  `AuthResource`, `TokenService`, `RememberMeService`, `SelectorTokens`, the `auth_tokens` table, or
  anything that mints or validates a credential -- it holds the invariants (fully-attributed audit,
  bounded revocation, one Valkey GET on the hot path) every change must preserve.

- An endpoint that manages the CALLER'S OWN sign-in (passkeys, signed-in devices) refuses while
  `BaseResource.viewingAs()`: during an administrator's View As the session's person is the one being viewed,
  so "mine" is theirs. Changing ANOTHER person's sign-in (their email above all) is never a matter of profile
  reach: ask `OrgCommands.canChangeSignInOf` (`docs/org-admin.md`, "Changing how someone signs in").
- Redaction is authorization: a DTO field the caller may not see is redacted by the mapper, not blocked by a
  route. `AccessLevel` is unranked -- do not compare its constants with ordinal logic.
- `Beans.get` must select with the `Any` qualifier: `@FacesConfig` (on `PassCommands`) suppresses `@Default`,
  so a bare `CDI.current().select(type)` 500s at runtime and no unit test catches it.
- PUT: an ABSENT field means unchanged (responses are redacted, so echoing one back must never erase what the
  caller could not see); `""` clears. Writes are gated field by field by the same capabilities as reads. Enums
  go out as `name()` and parse by name with a description fallback (`parseStatus`), never
  `withStatusString`/`fromDescription`, or a GET-then-PUT wipes the status.
- `TripsResource.applyPeople` silently DROPS roster ids that are not members of the trip's org. A family-created
  member joins the anchor's orgs at creation (`OrgCommands.addCreatedFamilyMember`); a LATER org join never
  propagates to family members (owner design).
- The caller names the actor; beans never guess: `MailCommands.send` and `DeployCommands.start` have no
  actor-less form, `AuditActor.system()` is timer work. A `Caller` (request-scoped privilege cache) never
  crosses an async boundary; an `AuditActor` may. Do not make one implement the other.
- Redirect allow-lists: a prefix must end at a URL boundary (`/`, `?`, `#`, end), else
  `https://good.example.evil.example` passes. Test the real function, never a copy of its rule.
