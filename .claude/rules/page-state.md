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
