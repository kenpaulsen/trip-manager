# Page state and row identity

Required reading before writing a new page, adding a scope variable, or wiring a row command. Both rules
here were paid for with production incidents, and both are enforced by tests that fail on new violations.

The two rules, in one line each:

1. **Domain objects live in `requestScope`.** Ids and scalars are what a view or a session may hold.
2. **A row command acts on an identity the render baked in**, never on the row that happens to sit at that
   index when the postback decodes.

---

## 1. Scope policy

| Scope | Lifetime | What may go in it |
|---|---|---|
| `requestScope` | one request/postback | **Domain objects.** Resolved from the DAO caches each request; never serialized |
| `viewScope` | the open page + its postbacks — **serialized into the HTTP session** | Ids, flags, scalars, id lists, small scalar row models; a few sanctioned edit buffers |
| `sessionScope` | until logout/expiry | Identity, navigation, preferences, tokens — scalars and tiny value types |
| `applicationScope` | JVM lifetime | Nothing new. **The DAO caches ARE the application-scoped layer** |

### Why viewScope counts as the session

`web.xml` sets `STATE_SAVING_METHOD=server`, so Mojarra stores each view's state — the viewScope map
included — inside the HTTP session. **A `Trip` in viewScope is a `Trip` in the session.** That indirection
is what made the 2026-08-14 outage hard to see: adding one field to `Trip` changed the class's computed
`serialVersionUID`, every session written before the deploy became unreadable, and every returning visitor
got a 500 while incognito worked fine.

The failure is silent at the time you write it. Nothing throws when the object goes into the view and
nothing throws when it comes back out; the bill arrives on the next deploy that changes the class's shape.

### Why not application scope either

`people.getPerson(id)` already **is** the app-scoped hashmap lookup — a `PointCache` over Valkey, shared
across instances, invalidated on write, returning defensive copies. An EL `applicationScope.allPeople`
duplicates it without the invalidation, the staleness control, or the copy semantics. With the cache next
to the DAO, per-request resolve is nearly free, and higher-level caching earns nothing.

### The patterns

**Display page** — pin the id once, resolve the object every request:

```
if (viewScope.theTripId == null) {
    ... work out WHICH trip, authorize, then: viewScope.theTripId = pickTrip.id;
}
// Every request, including each postback: initPage re-runs, so this is the object's whole lifetime.
requestScope.theTrip = trip.getTrip(viewScope.theTripId);
```

`initPage` re-runs on **every** postback by design — that is what makes this work. Guard the *decision*
(which trip? which person?) with a null check; never guard the *resolve*.

`getTrip`/`getPerson` **never return null** — they answer a blank object with a fresh id. Where "does not
exist" is semantic, prove existence with `id.equals(fresh.getId())` rather than a null check.

**Edit page** — the WIP object cannot live in the view either, so it lives on the heap behind a token:
app-scoped `tripEditDrafts` (`org.paulsens.trip.action.TripEditDrafts`, mirroring `PendingUploads`). The
view keeps `tripDraftToken`; `initPage` resolves the same heap instance each postback, so partial ajax
submits accumulate into one object. Save/Cancel discards the token.

**People lists** — `List<Person.Id>` in the view (`people.toIds(...)`), `people.getPerson(id)` per row.
Keep the list **mutable**: PrimeFaces dataTables sort their value list in place, and an immutable
`Stream.toList()` 500s the page.

**Backing beans count too.** A bean that reads the Trip object out of the view map (`getViewMap("theTrip")`)
starves the moment its page is converted, and the symptom lands somewhere unrelated — a cropper that never
appears, a chat send that loses its attachments. Beans read `theTripId` and resolve.

### Sanctioned exceptions

Small edit buffers for non-Trip dialogs (`person`/`newPerson`/`tx`/`editEvent`/`editContent`/
`editTemplate`), `pv` (a frozen `PrivacyView` — masking must not flip mid-view), `auditPage` (AuditDAO is
deliberately uncached, so per-request means a DynamoDB query per postback), `dashModel` (third-party
PrimeFaces layout state), scalar row models, `regDraft`, `viewAsStack`, and PrimeFaces'
`DYNAMIC_RESOURCES_MAPPING`. Anything session-reachable **must** be `Serializable` with a declared
`serialVersionUID`, and must not depend on a constructor running — Kryo sets fields directly.

### The guard

`medjugorje/webtest/.../SessionScopePolicyIT` scans the XHTML source for `viewScope.x = <domain-valued EL>`
in three ratchets (Trip-valued, Person-valued, other domain types). A new violation fails the build; a new
exception has to be argued for in that file. Its blind spot: `x = call(...).member` derives are invisible
to it, so a derive that smuggles an object into the view still needs your eyes.

---

## 2. Row identity

Because tables now resolve per request, **the row list at decode is not the list that rendered**. Another
admin's edit, a sort, a deletion, or a filter change between render and click is enough to shift positions.
A command that reads `#{row.id}` at decode time reads whatever landed at that index — the wrong record,
silently, with no error anywhere. For a delete that means the wrong row is deleted.

Pick by what the command does:

**a) `f:param` — for a command that acts on a record.** The value is baked in at RENDER, so it carries the
identity of the row the user actually saw. The handler re-resolves it fresh (which also means it edits
current data, not a snapshot) and re-checks authorization.

```xml
<p:commandButton value="Delete" update=":form:growl">
    <f:param name="delMemberId" value="#{member.id.value}" />
    <jsft:event type="command">
        if (family.deleteMember(people.id(param.delMemberId))) {
            jsft.redirect(viewScope.selfUrl);
        }
    </jsft:event>
</p:commandButton>
```

> **`f:param` does NOT ride a plain `ajax="false"` command-button submit** — only links and ajax requests
> carry it. Make the button ajax (`update=":form:growl"` is usually enough, since the template growl
> auto-updates). Otherwise the parameter never arrives and the action silently does nothing.

**b) Frozen key list — for a command that binds INTO the row object.** Cell editors and dialogs whose
fields write straight into the row cannot be served by a parameter: the object the decode writes to has to
*be* the object the render produced. Freeze a scalar key list in the view and resolve current rows in that
frozen order each request (`trip.eventIdsOf`/`eventsForFrozenIds`, `todo.todoKeysOf`/`todosForFrozenKeys`,
`content.forFrozenIds`). Rows that vanished are **skipped**, not shifted — shifting is the bug being fixed.
Re-freeze whenever the set legitimately changes; every mutation on these pages redirects, which does it.

**c) Scalar row model — for a table that SORTS.** PrimeFaces sorts the value list in place, so a per-request
list breaks row identity outright. The view holds plain scalar rows (`RegistrationCommands.RegRow`:
`@Data`, no-arg constructor, declared UID) and the real records resolve per request from one bulk read
keyed by the row's id.

**d) Safe by construction.** A table whose rows are already scalars keyed by id (rooms, the `vals` map)
needs nothing — but its build-once behavior is then load-bearing, so leave it alone.

Whatever the mechanism: **money and membership paths re-read fresh** (`getTripForEdit`, `Cached.NO`) before
mutating, and re-check the precondition they were rendered under.

---

## Checklist for a new page

- [ ] Every domain object in `requestScope`, resolved in `initPage` unconditionally; only ids in the view.
- [ ] Existence proved with `id.equals(...)`, never a null check on a `get*` result.
- [ ] Person lists are id lists; mutable if a dataTable sorts them.
- [ ] Every row command carries `f:param` identity, a frozen key, or a scalar row — none decode by position.
- [ ] Any button carrying an `f:param` is ajax.
- [ ] Anything left in the view is `Serializable`, has a declared `serialVersionUID`, and survives Kryo
      skipping its constructor.
- [ ] No `--` inside an XML comment (Facelets rejects the whole page; the build refuses to stage it).
- [ ] A webtest drives the page's key interactions — including each row command, proving it hits the row
      that was clicked.
