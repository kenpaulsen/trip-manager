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

- `PhotoChatResource` is deliberately NOT `@TripApi`: the auth filter is name-bound, so its GETs serve
  anonymous readers (comments follow the photo -- `docs/photo-comments.md`); mutations enforce the session
  themselves and answer 401 JSON. `BaseResource.personId` still recognises a bearer caller there (the
  servlet edge resolves the principal for every `/api/` request; only the filter's stamp is missing).
- API authentication is dual: session cookie OR `Authorization: Bearer` (feature-gated by
  `api.token.enabled`, default off). Read `docs/api-tokens.md` before touching `TripAuthFilter`,
  `AuthResource`, `TokenService`, `RememberMeService`, `SelectorTokens`, the `auth_tokens` table, or
  anything that mints or validates a credential -- it holds the invariants (fully-attributed audit,
  bounded revocation, one Valkey GET on the hot path) every change must preserve.

- Redaction is authorization: a DTO field the caller may not see is redacted by the mapper, not blocked by a
  route. `AccessLevel` is unranked -- do not compare its constants with ordinal logic.
- `Beans.get` must select with the `Any` qualifier: `@FacesConfig` (on `PassCommands`) suppresses `@Default`,
  so a bare `CDI.current().select(type)` 500s at runtime and no unit test catches it.
