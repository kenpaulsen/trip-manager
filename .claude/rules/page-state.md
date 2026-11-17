---
paths:
  - "trip/src/main/java/org/paulsens/trip/action/**"
  - "trip/src/main/java/org/paulsens/trip/web/**"
  - "trip/src/main/java/org/paulsens/trip/jsf/**"
---

# Page state and row identity -- `docs/page-state-and-identity.md` (required reading)

Two rules that outrank convenience everywhere, and that a new page must follow from its first line:

1. **Domain objects live in `requestScope`, resolved from the DAO caches every request.** A view or a
   session may hold ids and scalars only -- `STATE_SAVING_METHOD=server` serializes viewScope INTO the
   session, so an object in a view is an object in the session, and a later change to that class's shape
   500s every returning visitor (the 2026-08-14 outage). The DAO caches already ARE the app-scoped layer --
   `people.getPerson(id)` is the hashmap lookup -- so per-request resolve is nearly free and a higher-level
   cache earns nothing. Beans obey this too: a bean reading `getViewMap("theTrip")` starves the moment its
   page is converted.
2. **Row commands act on an identity baked in at RENDER**, never on a row position: `f:param` for commands
   that act on a record, a frozen key list for editors that bind INTO the row, a scalar row model for
   tables that sort. Tables resolve per request now, so the decode-time list is not the rendered list.
   NB an `f:param` does NOT ride a plain `ajax="false"` button submit -- such buttons must be ajax.

Guarded by `../medjugorje/webtest/.../SessionScopePolicyIT` (source-scan ratchet, three families of
violation) and by a webtest per row command. The doc carries the patterns, the sanctioned exceptions, and
the new-page checklist.

## More page and bean traps

- **Which trip**: `trip.getTripForUser(curr, user, showAll, param.trip)` answers an explicit id's trip or NULL,
  never a substitute; the own/recent/joinable ladder runs only with no id. On null, redirect to
  `trip.noTripUrl(userId, param.trip)`. Never add a page-local fallback (`chat.tripForChatPage` is a guest/family
  GRANT, not a fallback). Ratchets: `TripResolutionTest`, `../medjugorje/webtest/.../ExplicitTripRefusalPwIT`.
- Who sees a person's itinerary is decided ONLY by `ItineraryAccess` (`docs/shared-itineraries.md`); never
  re-derive it on a page or resource.
- Never put `final` on an instance method of a CDI bean or any superclass (`static` is fine): Weld refuses the
  proxy LAZILY (WELD-001480) on `Beans.get`/injection only, so EL pages, the unit suite and startup all stay green.
- Every `uploadBean` handed to `WEB-INF/photoUploadDialog.xhtml` extends `action/PhotoUploadBean` (dialog
  contract, token flow, `claim`, test seams); a new upload dialog extends it, never copies a bean, and its
  `targetId()` derives from the SAME seam the bean's authorization uses.
- Page messages go through `PageFeedback` (`info/warn/error`, `refuse`, `refuseAction`, `callbackParam`); its join
  rule puts everything in the SUMMARY ("Summary: detail"). Never hand `ex.getMessage()` to the page: use
  `PageFeedback.failure(summary)`. Local-mode object maps use `media.LocalObjectStore`.
- **A servlet URL must not end in `.pdf`**: `PdfRedirectFilter` (on `/*`) 301s every such path to the media CDN
  when deployed and stands aside locally, so it passes every test. Use an extension-less path and name the file in
  `Content-Disposition` (`RoomInvoicePdfServlet`).
- `LodgingCommands` sits at the 3000-line checkstyle cap and the owner refused splitting it by its section
  banners: put new code in another class, and do not re-propose the split.
