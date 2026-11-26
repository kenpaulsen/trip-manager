package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.List;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

/**
 * The trip editor's staff dialogs ({@code WEB-INF/tripStaffDialog.xhtml}: the Spiritual Director and the
 * Coordinators). Picking staff means searching people, so it takes People Admin ({@code peopleAdmin}) for the
 * trip's organization (owner, 2026-11-26); a site admin holds it everywhere. Being the org's admin is not
 * enough on its own: an org admin grants it to themselves like any other org privilege. Without it the editor
 * shows a dialog saying so in place of the staff dialog, which is not rendered at all.
 *
 * <p>Every answer comes from the STORED trip when editing and the caller's own draft when creating
 * ({@link TripEditDrafts#editorTrip}), so the page's gate, the search and the add check agree. The search
 * offers the trip's org members ONLY (tenancy); a trip with no organization is site-admin territory, and only
 * there does the global search back the picker. The completion endpoint and the add and remove commands are
 * POSTable without the dialog, so each checks the privilege itself.
 */
@Named("tripStaff")
@ApplicationScoped
public class TripStaffCommands {

    /** Result cap for {@link #complete}, matching the people pickers' default. */
    static final int PICKER_LIMIT = 25;

    private final Supplier<Caller> callerSource;
    private final Supplier<OrgCommands> orgSource;
    private final Supplier<TripCommands> tripSource;

    public TripStaffCommands() {
        this(Caller::current, () -> org.paulsens.trip.api.Beans.get(OrgCommands.class),
                () -> org.paulsens.trip.api.Beans.get(TripCommands.class));
    }

    /** Test seam: {@code Caller.current()} needs a FacesContext and {@code Beans.get} a CDI container. */
    TripStaffCommands(final Supplier<Caller> callerSource, final Supplier<OrgCommands> orgSource,
            final Supplier<TripCommands> tripSource) {
        this.callerSource = callerSource;
        this.orgSource = orgSource;
        this.tripSource = tripSource;
    }

    /** Whether the caller may pick staff on the trip the editor is working on: the page's gate. */
    public boolean mayPickOnEditor() {
        return mayPick(tripFromView());
    }

    /** People Admin for the trip's organization; a site admin always, and only a site admin for an org-less trip. */
    public boolean mayPick(final Trip trip) {
        if (trip == null) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (caller.isSiteAdmin()) {
            return true;
        }
        final String orgId = trip.getOrgId();
        return orgId != null && !orgId.isBlank() && caller.has(PrivilegeCommands.PEOPLE_ADMIN, orgId);
    }

    /** The staff picker's autocomplete: the trip's org members matching {@code query}, or nothing. */
    public List<Person> complete(final String query) {
        final Trip trip = tripFromView();
        if (!mayPick(trip)) {
            return List.of();
        }
        final OrgCommands orgs = orgSource.get();
        final Organization owner = ownerOf(trip, orgs);
        if (owner == null) {
            // No organization to scope to: none set, OR its row did not resolve (a failed read answers empty, and
            // mayPick admits a holder of the grant scoped to that id). Only a site admin then searches everyone.
            return callerSource.get().isSiteAdmin()
                    ? DAO.getInstance().searchPeople(query, PICKER_LIMIT, Cached.NO) : List.of();
        }
        return PeoplePicker.pick(orgs.getMembers(owner), query, PICKER_LIMIT);
    }

    /**
     * Adds {@code personId} to the working copy's staff in {@code role}, when the caller may pick staff and the
     * person is a member of the trip's organization: the picker offers only members, but its converter loads any
     * id it is sent.
     */
    public boolean add(final Trip workingCopy, final String role, final Person.Id personId) {
        final Trip trip = tripFromView();
        if (workingCopy == null || personId == null || !mayPick(trip)) {
            return PageFeedback.refuse(Text.get("feedback.shared.notAllowed"));
        }
        final OrgCommands orgs = orgSource.get();
        final Organization owner = ownerOf(trip, orgs);
        // No resolved organization, no membership rule to apply: only a site admin, as in complete.
        final boolean allowed = (owner == null) ? callerSource.get().isSiteAdmin()
                : orgs.isMember(owner.getId().getValue(), personId);
        if (!allowed) {
            return PageFeedback.refuse(Text.get("feedback.shared.notAllowed"));
        }
        tripSource.get().addStaff(workingCopy, role, personId);
        return true;
    }

    /** Removes {@code personId} from the working copy's staff in {@code role}, when the caller may pick staff. */
    public boolean remove(final Trip workingCopy, final String role, final Person.Id personId) {
        if (workingCopy == null || !mayPick(tripFromView())) {
            return PageFeedback.refuse(Text.get("feedback.shared.notAllowed"));
        }
        tripSource.get().removeStaff(workingCopy, role, personId);
        return true;
    }

    /** The trip's organization, or null when it has none or its row does not resolve. */
    private static Organization ownerOf(final Trip trip, final OrgCommands orgs) {
        final String orgId = trip.getOrgId();
        return (orgId == null || orgId.isBlank()) ? null : orgs.findOrganization(orgId);
    }

    /**
     * The trip the editor page is working on, or null off-page. Seam: tests hand the trip back directly
     * ({@code ScopeUtil} needs a FacesContext).
     */
    protected Trip tripFromView() {
        return org.paulsens.trip.api.Beans.get(TripEditDrafts.class).editorTripInView(callerSource.get().personId());
    }
}
