package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.OrgMember;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPaymentConfig;
import org.primefaces.PrimeFaces;
import org.paulsens.trip.pay.ProcessorPing;
import org.paulsens.trip.security.ProcessorSecrets;
import org.paulsens.trip.site.SiteUrls;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Organizations: the tenancy boundary of the platform (see the workspace design principle -- trips,
 * transactions, and org-owned configuration must never cross it). This bean is the ONLY writer of org
 * structure, the same security-boundary stance {@link FamilyCommands} takes for families.
 *
 * <p>The {@code org_members} row is the source of truth for membership; every member's
 * {@link Person#getOrgIds()} list is derived from it by SURGICAL deltas (add/remove the specific id, never
 * wholesale replacement). Admins live on the org row itself ({@link Organization#getAdminIds()}), guarded by
 * its optimistic version. Write order everywhere: the membership row first, then the derived person write
 * computed from the in-memory object just saved (a re-read can be stale in production), then audit.
 *
 * <p>Authorization: site admins (role {@code admin}) reach everything; org admins reach exactly their own
 * org's structure and (later phases) its payment-processor configuration. Creating an organization -- and
 * renaming one, because {@code Trip.provider} display strings derive from the name -- is site-admin only.
 */
@Slf4j
@Named("org")
@ApplicationScoped
public class OrgCommands {
    private final Supplier<Caller> callerSource;
    private final Supplier<MailCommands> mailSource;
    private final Supplier<SupportChatCommands> supportSource;
    /** Lazily built (never in a constructor: {@link MailAddressCommands} reads settings). */
    private volatile MailAddressCommands mailAddrCache;

    public OrgCommands() {
        this(Caller::current);
    }

    /** Test seam (the {@link FamilyCommands} pattern): {@code Caller.current()} needs a FacesContext. */
    public OrgCommands(final Supplier<Caller> callerSource) {
        this(callerSource, () -> org.paulsens.trip.api.Beans.get(MailCommands.class));
    }

    /**
     * Test seam: mail too ({@code Beans.get} needs a CDI container unit tests do not have). The default
     * support-channel commands are built over THIS caller source, so a notice filed from here is authored
     * by the same caller the command ran as.
     */
    public OrgCommands(final Supplier<Caller> callerSource, final Supplier<MailCommands> mailSource) {
        this(callerSource, mailSource, () -> new SupportChatCommands(
                new org.paulsens.trip.chat.ChatRateLimiter(DAO.getInstance().getCacheClient()),
                new ConfigCommands(), new MailCommands(), callerSource));
    }

    /** Full test seam: the support channel too ({@link #grantCreatorTripRoles}' missing-roles notice). */
    public OrgCommands(final Supplier<Caller> callerSource, final Supplier<MailCommands> mailSource,
            final Supplier<SupportChatCommands> supportSource) {
        this.callerSource = callerSource;
        this.mailSource = mailSource;
        this.supportSource = supportSource;
    }

    // ------------------------------------------------------------------ reads

    /** Every organization, sorted by name for display. */
    public List<Organization> getOrganizations() {
        return DAO.getInstance().getOrganizations(Cached.YES).stream()
                .sorted(Comparator.comparing(Organization::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The organization, or a BLANK unsaved one when the id is unknown -- the bean-layer never-null contract
     * (EL null checks don't fire). Code that must distinguish a miss uses {@link #findOrganization}.
     */
    public Organization getOrganization(final String orgId) {
        final Organization found = findOrganization(orgId);
        return (found == null) ? new Organization() : found;
    }

    /** The organization, or null when the id is blank or unknown. */
    public Organization findOrganization(final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getOrganization(Organization.Id.from(orgId.trim()), Cached.YES).orElse(null);
    }

    /** Whether the signed-in user may manage this org's structure and configuration. */
    public boolean canManageOrg(final String orgId) {
        // A null/blank org is manageable by NOBODY -- the site-admin shortcut must not bless a missing id
        // (a no-param orgSettings visit NPE'd through exactly that hole).
        if (orgId == null || orgId.isBlank()) {
            return false;
        }
        final Caller current = caller();
        if (!current.isAuthenticated()) {
            return false;
        }
        if (current.isSiteAdmin()) {
            return true;
        }
        final Organization org = findOrganization(orgId);
        return org != null && org.isAdmin(current.personId());
    }

    /** Orgs the signed-in user administers: all of them for a site admin, their own for an org admin. */
    public List<Organization> getManageableOrgs() {
        final Caller current = caller();
        if (!current.isAuthenticated()) {
            return List.of();
        }
        return current.isSiteAdmin()
                ? getOrganizations()
                : getOrganizations().stream().filter(org -> org.isAdmin(current.personId())).toList();
    }

    /**
     * Autocomplete over the manageable orgs: contains-match, case-insensitive. Every org picker uses this
     * (a plain dropdown stops scaling around 100+ orgs -- a stated design constraint).
     */
    public List<Organization> completeOrgs(final String query) {
        return matching(getManageableOrgs(), query);
    }

    /** Autocomplete over EVERY org, for site-admin-only pickers (the org switcher). */
    public List<Organization> completeAllOrgs(final String query) {
        return caller().isSiteAdmin() ? matching(getOrganizations(), query) : List.of();
    }

    /** Membership rows resolved to people, org-roster order (by person id -- stable, not name-sorted). */
    public List<Person> getMembers(final Organization org) {
        final List<Person> result = new ArrayList<>();
        if (org == null || org.getId() == null) {
            return result;
        }
        for (final OrgMember member : DAO.getInstance().getOrgMembers(org.getId(), Cached.YES)) {
            DAO.getInstance().getPerson(member.getPersonId(), Cached.YES).ifPresent(result::add);
        }
        return result;
    }

    /** The organizations this person belongs to, resolved from the derived back-pointer list. */
    public List<Organization> membershipsOf(final Person person) {
        if (person == null) {
            return List.of();
        }
        return person.getOrgIds().stream()
                .map(orgId -> DAO.getInstance().getOrganization(orgId, Cached.YES).orElse(null))
                .filter(org -> org != null)
                .toList();
    }

    /** Membership row count, without resolving people (the admin table shows one number per org). */
    public int getMemberCount(final Organization organization) {
        if (organization == null || organization.getId() == null) {
            return 0;
        }
        return DAO.getInstance().getOrgMembers(organization.getId(), Cached.YES).size();
    }

    public boolean isMember(final String orgId, final Person.Id personId) {
        if (orgId == null || orgId.isBlank() || personId == null) {
            return false;
        }
        return DAO.getInstance()
                .getOrgMember(Organization.Id.from(orgId.trim()), personId, Cached.NO)
                .isPresent();
    }

    // ------------------------------------------------------------------ writes

    /**
     * Creates an organization. Site-admin only: an org is a tenancy root, not something an org admin mints.
     * Returns the saved org, or null (with a growl) on refusal or failure.
     */
    public Organization createOrganization(final String name, final String abbreviation,
            final String contactEmail) {
        final Caller current = caller();
        if (!current.isSiteAdmin()) {
            return failOrg("Not allowed", "Only a site administrator can create an organization.");
        }
        if (name == null || name.isBlank()) {
            return failOrg("Name required", "An organization needs a name.");
        }
        final String wanted = name.trim();
        final boolean taken = getOrganizations().stream()
                .anyMatch(existing -> wanted.equalsIgnoreCase(existing.getName()));
        if (taken) {
            return failOrg("Duplicate name", "An organization named \"" + wanted + "\" already exists.");
        }
        final Organization org = Organization.builder()
                .name(wanted)
                .abbreviation(abbreviation)
                .contactEmail(contactEmail)
                .createdBy(current.personId())
                .created(LocalDateTime.now())
                .build();
        if (!saveOrgOrWarn(org)) {
            return null;
        }
        audit(current, org, "Organization '" + wanted + "' created");
        return org;
    }

    /**
     * Saves edits to an existing organization. Org admins may edit contact details; RENAMING is site-admin
     * only, because {@code Trip.provider} display strings on public pages derive from the name.
     */
    public boolean saveOrganization(final Organization org) {
        final Caller current = caller();
        if (org == null || org.getVersion() == 0L) {
            return fail("Unable to save", "Unknown organization.");
        }
        if (!canManageOrg(org.getId().getValue())) {
            return fail("Not allowed", "Only this organization's admins can edit it.");
        }
        final Organization stored = findOrganization(org.getId().getValue());
        if (stored == null) {
            return fail("Unable to save", "Unknown organization.");
        }
        if (!current.isSiteAdmin() && !equalsIgnoreCaseSafe(stored.getName(), org.getName())) {
            return fail("Not allowed", "Only a site administrator can rename an organization "
                    + "(public pages display the name).");
        }
        if (org.getName() == null || org.getName().isBlank()) {
            return fail("Name required", "An organization needs a name.");
        }
        if (!saveOrgOrWarn(org)) {
            return false;
        }
        audit(current, org, "Organization '" + org.getName() + "' edited");
        return true;
    }

    /**
     * Page-facing edit: applies the given field values onto a FRESH read of the org (never a stale page
     * snapshot) and saves through {@link #saveOrganization}'s authorization + rename rules.
     */
    public boolean saveOrgEdits(final String orgId, final String name, final String abbreviation,
            final String contactEmail) {
        final Organization fresh = freshOrg(orgId);
        if (fresh == null) {
            return fail("Unable to save", "Unknown organization.");
        }
        fresh.setName(name);
        fresh.setAbbreviation(abbreviation);
        fresh.setContactEmail(contactEmail);
        return saveOrganization(fresh);
    }

    /** An uncached read of the org to edit -- an edit seed must never come from the shared cache. */
    private Organization freshOrg(final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        return DAO.getInstance()
                .getOrganization(Organization.Id.from(orgId.trim()), Cached.NO).orElse(null);
    }

    /**
     * Adds a person to an organization (idempotent). Membership row first, then the derived
     * {@code Person.orgIds} delta. Returns false (with a growl) on refusal or failure.
     */
    public boolean addMember(final String orgId, final Person.Id personId) {
        if (findOrganization(orgId) == null || personId == null) {
            return fail("Unable to add", "Unknown organization or person.");
        }
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can add members.");
        }
        return writeMembership(orgId, personId);
    }

    /**
     * Membership write for a person a people admin just CREATED. Unlike {@link #addMember} (org-admin only,
     * because pulling an EXISTING person into an org is an access change), creation tenants a brand-new
     * person into an org the creator holds {@code peopleAdmin} in -- nobody's existing access moves.
     */
    public boolean addCreatedPerson(final String orgId, final Person.Id personId) {
        if (findOrganization(orgId) == null || personId == null) {
            return fail("Unable to add", "Unknown organization or person.");
        }
        if (!canViewOrgPeople(orgId)) {
            return fail("Not allowed", "Creating a person here requires people-admin access to this "
                    + "organization.");
        }
        return writeMembership(orgId, personId);
    }

    /**
     * The org-people page's add-by-email path: an exact-address lookup, so an org admin can add a person
     * they already know without a directory typeahead (the name autocomplete is site-admin-only -- search
     * across every account leaked other tenants' people to org admins). Returns {@code null} when the
     * request was fully handled here (person found and added, or refused with a growl); returns the
     * trimmed address when it belongs to NO account, publishing the {@code showInvite} ajax callback
     * param so the page can open its "invite by email?" dialog ({@link #sendOrgInvite}).
     */
    public String addMemberByEmail(final String orgId, final String email) {
        if (findOrganization(orgId) == null) {
            fail("Unable to add", "Unknown organization.");
            return null;
        }
        if (!canManageOrg(orgId)) {
            fail("Not allowed", "Only this organization's admins can add members.");
            return null;
        }
        final String addr = normalizeEmail(email);
        if (addr == null) {
            fail("Email required", "Enter the person's email address.");
            return null;
        }
        final Person match = DAO.getInstance().getPersonByEmail(addr, Cached.NO);
        if (match == null) {
            publishParam("showInvite", true);
            return addr;
        }
        if (writeMembership(orgId, match.getId())) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO,
                    "Added " + match.getPreferredName() + " " + match.getLast() + ".", null);
        }
        return null;
    }

    /**
     * Emails an account invitation ({@code org-invite} MAIL template) to an address
     * {@link #addMemberByEmail} found no account for. Stateless by design: nothing is recorded and no
     * membership is pre-granted -- the admin adds the address again once the account exists. An account
     * that appears between the check and the send folds into a plain add, which is what the admin wanted.
     */
    public boolean sendOrgInvite(final String orgId, final String email) {
        final Caller current = caller();
        final Organization org = findOrganization(orgId);
        if (org == null) {
            return fail("Unable to invite", "Unknown organization.");
        }
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can invite members.");
        }
        final String addr = normalizeEmail(email);
        if (addr == null) {
            return fail("Email required", "Enter the person's email address.");
        }
        final Person match = DAO.getInstance().getPersonByEmail(addr, Cached.NO);
        if (match != null) {
            return writeMembership(orgId, match.getId());
        }
        final ConfigCommands config = new ConfigCommands();
        // The invitee lands on the ORG's site when it has one (its own name, its own host), else on the
        // shared site the org is listed on -- derived from the org, never from the host the admin is on.
        final String siteUrl = SiteUrls.baseUrl(org, KnownSettings.REG_MAIL_BASE_URL, config);
        final boolean ownSite = org.getSlug() != null && !org.getSlug().isBlank();
        final java.util.Map<String, Object> values = java.util.Map.of(
                "orgName", org.getName(),
                "siteName", ownSite ? org.getName() : config.siteString(KnownSettings.SITE_ORG_NAME),
                "siteHost", SiteUrls.hostOf(siteUrl),
                "createAccountUrl", inviteLoginUrl(siteUrl, addr));
        // The invite already has its org in hand, so org-first directly; the site email is the fallback
        // (REG_MAIL_REPLY_TO may hold the 'org' sentinel now, so it is resolved, never read raw).
        final MailAddressCommands addresses = new MailAddressCommands(config);
        final String replyTo = (org.getContactEmail() == null || org.getContactEmail().isBlank())
                ? addresses.orgReplyTo(KnownSettings.REG_MAIL_REPLY_TO, org) : org.getContactEmail();
        final boolean sent = mailSource.get().sendManagedTemplate(
                StarterTemplates.ORG_INVITE_ID, values, addr,
                addresses.from(KnownSettings.REG_MAIL_FROM), replyTo, current.auditActor());
        if (!sent) {
            return fail("Not sent", "The invitation could not be sent; is the org-invite template "
                    + "installed?");
        }
        audit(current, org, "Invited " + addr + " to organization '" + org.getName() + "'");
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, "Invitation sent to " + addr + ".",
                null);
        return true;
    }

    /**
     * The invitation's link: the site's LOGIN page with the invitee's address pre-filled ({@code ?email=},
     * read into requestScope by the page -- no session for the click). Deliberately not the create-account
     * page (the pre-2026-09-01 link): an account may have appeared since the invite was sent, and the
     * login page's Next is what checks -- an existing account signs in (password, code or passkey), an
     * unknown address continues to Create Account with the email carried over. Either way the visitor
     * is on the org's site, so the existing sign-up join ({@link #joinSiteOrgOnSignup}) still applies. The
     * template token keeps its {@code createAccountUrl} name so installed rows need no re-install.
     */
    static String inviteLoginUrl(final String siteUrl, final String email) {
        return siteUrl + "/account/login.jsf?email="
                + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Trimmed address, or null unless it has at least one char on each side of an {@code @}. */
    private static String normalizeEmail(final String email) {
        final String addr = (email == null) ? "" : email.trim();
        final int at = addr.indexOf('@');
        return (at < 1 || at == addr.length() - 1) ? null : addr;
    }

    /** One named ajax callback param; a no-op outside a Faces ajax request (unit tests, REST). */
    private static void publishParam(final String name, final Object value) {
        if (jakarta.faces.context.FacesContext.getCurrentInstance() == null) {
            return;
        }
        final org.primefaces.PrimeFaces pf = org.primefaces.PrimeFaces.current();
        if (pf.isAjaxRequest()) {
            pf.ajax().addCallbackParam(name, value);
        }
    }

    private boolean writeMembership(final String orgId, final Person.Id personId) {
        return writeMembership(orgId, personId, null);
    }

    /**
     * @param how the audit trail's account of WHY this membership was written (null = an admin added them);
     *            the self-join paths pass the event that granted it, so a roster reviewer can tell a
     *            sign-up or a trip registration from an admin's add.
     */
    private boolean writeMembership(final String orgId, final Person.Id personId, final String how) {
        final Caller current = caller();
        final Organization org = findOrganization(orgId);
        final Person person = DAO.getInstance().getPerson(personId, Cached.NO).orElse(null);
        if (person == null) {
            return fail("Unable to add", "Unknown person.");
        }
        if (DAO.getInstance().getOrgMember(org.getId(), personId, Cached.NO).isPresent()) {
            return true;
        }
        try {
            if (!DAO.getInstance().saveOrgMember(
                    new OrgMember(org.getId(), personId, LocalDateTime.now()))) {
                return fail("Unable to add", "Unable to save the membership.");
            }
        } catch (final IOException ex) {
            log.error("Unable to save org membership {} -> {}", personId, org.getId(), ex);
            return fail("Unable to add", "Unable to save the membership: " + ex.getMessage());
        }
        if (!person.getOrgIds().contains(org.getId())) {
            person.getOrgIds().add(org.getId());
            savePersonOrWarn(person);
        }
        audit(current, org, "Added " + describe(person) + " to organization '" + org.getName() + "'"
                + (how == null ? "" : " (" + how + ")"));
        return true;
    }

    /**
     * Removes a person from an organization. Refused for an org admin (revoke admin first -- keeps
     * {@code adminIds} inside the roster) and for the person's LAST org (every person must belong to at
     * least one organization; move them by adding the new org first).
     */
    public boolean removeMember(final String orgId, final Person.Id personId) {
        final Caller current = caller();
        final Organization org = findOrganization(orgId);
        if (org == null || personId == null) {
            return fail("Unable to remove", "Unknown organization or person.");
        }
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can remove members.");
        }
        if (org.isAdmin(personId)) {
            return fail("Admin first", "This person is an admin of the organization. "
                    + "Revoke their admin role before removing them.");
        }
        if (isOnAnyOrgTrip(orgId, personId)) {
            return fail("On a trip", "This person is on a trip belonging to this organization. "
                    + "Remove them from the trip before removing them from the organization.");
        }
        if (DAO.getInstance().getOrgMember(org.getId(), personId, Cached.NO).isEmpty()) {
            return fail("Not a member", "This person is not a member of the organization.");
        }
        final Person person = DAO.getInstance().getPerson(personId, Cached.NO).orElse(null);
        if (person != null && person.getOrgIds().contains(org.getId()) && person.getOrgIds().size() <= 1) {
            return fail("Last organization", "Every person must belong to at least one organization. "
                    + "Add them to their new organization first.");
        }
        if (!DAO.getInstance().deleteOrgMember(org.getId(), personId)) {
            return fail("Unable to remove", "Unable to remove the membership.");
        }
        if (person != null && person.getOrgIds().remove(org.getId())) {
            savePersonOrWarn(person);
        }
        revokeOrgPrivilegesOf(org, personId);
        audit(current, org, "Removed " + (person == null ? personId.getValue() : describe(person))
                + " from organization '" + org.getName() + "'");
        return true;
    }

    /**
     * Strips every org-scoped privilege a departing member held here -- a grant must not outlive the
     * membership it was bounded by (grants require membership, so leaving revokes). Each revocation audits
     * its own delta through the privilege save.
     */
    private void revokeOrgPrivilegesOf(final Organization org, final Person.Id personId) {
        final PrivilegeCommands priv = privCommands();
        for (final String base : PrivilegeCommands.ORG_SCOPED_BASES) {
            final Privilege row = priv.getPrivilege(base, org.getId().getValue());
            if (row.getPeople().contains(personId)) {
                priv.savePrivilege(row.withoutPerson(personId), caller().auditActor());
            }
        }
    }

    /**
     * Grants or revokes org-admin. Granting auto-adds membership (admins are members); the org row's
     * optimistic version serializes concurrent admin edits.
     */
    public boolean setOrgAdmin(final String orgId, final Person.Id personId, final boolean admin) {
        final Caller current = caller();
        final Organization org = findOrganization(orgId);
        if (org == null || personId == null) {
            return fail("Unable to save", "Unknown organization or person.");
        }
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can change its admins.");
        }
        if (admin == org.isAdmin(personId)) {
            return true;
        }
        if (admin && !addMember(orgId, personId)) {
            return false;
        }
        // addMember may have raced another writer on the org row; recompute the delta against a fresh read.
        final Organization fresh = DAO.getInstance()
                .getOrganization(org.getId(), Cached.NO).orElse(null);
        if (fresh == null) {
            return fail("Unable to save", "Unknown organization.");
        }
        // Checked on the fresh row (the one being saved): revoking the ONLY admin would lock every
        // non-site-admin out of managing the org (user decision 2026-08-25).
        if (!admin && fresh.isAdmin(personId) && fresh.getAdminIds().size() <= 1) {
            return fail("Last admin", "This is the organization's only admin. "
                    + "Appoint another admin before revoking this one.");
        }
        if (admin) {
            fresh.getAdminIds().add(personId);
        } else {
            fresh.getAdminIds().remove(personId);
        }
        if (!saveOrgOrWarn(fresh)) {
            return false;
        }
        audit(current, fresh, (admin ? "Granted" : "Revoked") + " org admin for " + personId.getValue()
                + " on organization '" + fresh.getName() + "'");
        return true;
    }

    // ------------------------------------------------------------------ self-join (org sites)

    /**
     * The one membership write a person earns for THEMSELVES, and the only two events that earn it
     * (user-locked, 2026-09-01): creating an account on an organization's own site, and registering for
     * one of the organization's trips. Browsing an org site never joins -- an existing account reads
     * {@code acme.unitetrip.com} exactly as an outsider until it registers there or accepts an invite --
     * which is why this takes no org id from the caller: the org comes from the request's host or the
     * trip's owner, never from a parameter a page could be talked into passing.
     *
     * <p>Authorization is the event itself: the account exists (so the caller IS the person), or the
     * registration was accepted by {@code RegistrationCommands.registerParty}'s own checks. A person
     * already in the org is a no-op; an org the site or trip does not name is a no-op too, never a
     * refusal growl -- both callers are mid-flow on a page whose real work already succeeded.
     */
    private boolean selfJoin(final String orgId, final Person.Id personId, final String how) {
        if (orgId == null || orgId.isBlank() || personId == null || findOrganization(orgId) == null) {
            return false;
        }
        return writeMembership(orgId, personId, how);
    }

    /**
     * Auto-join for a brand-new account created on an ORG host: the signed-in caller (the account the
     * create page just established a session for) joins the site's organization. On a shared or marketing
     * host there is no organization to join and this answers false without a message.
     */
    public boolean joinSiteOrgOnSignup() {
        final SiteContext site = SiteContext.current();
        final Caller current = caller();
        if (!site.isOrg() || !current.isAuthenticated()) {
            return false;
        }
        return selfJoin(site.orgId().getValue(), current.personId(), "signed up on the organization's site");
    }

    /**
     * Join-on-registration: a traveler whose registration for {@code trip} was just accepted becomes a
     * member of the trip's owning organization (a roster member who is not in the org would otherwise be
     * unremovable from a People page that never lists them). Org-less trips are a no-op.
     */
    public boolean joinOnRegistration(final Trip trip, final Person.Id travelerId) {
        if (trip == null || trip.getOrgId() == null) {
            return false;
        }
        return selfJoin(trip.getOrgId(), travelerId, "registered for trip '" + trip.getTitle() + "'");
    }

    // ------------------------------------------------------------------ org-scoped privileges

    /**
     * Whether the signed-in user holds the given base privilege in ANY of their orgs (site admin: always).
     * The menu and the shared people/mail pages gate on this -- "does an org-scoped door exist for this
     * person at all" -- while each page then enforces its own per-subject or per-org check.
     */
    public boolean holdsAnywhere(final String base) {
        return !orgsWithPriv(base).isEmpty();
    }

    /**
     * The orgs in which the signed-in user holds the given base privilege (site admin: all of them). Scans
     * every org rather than just the caller's memberships so the answer matches {@link #canViewOrgHub} --
     * the org list is small and cached, and {@link Caller#has} memoizes per request.
     */
    public List<Organization> orgsWithPriv(final String base) {
        final Caller current = caller();
        if (base == null || !current.isAuthenticated()) {
            return List.of();
        }
        if (current.isSiteAdmin()) {
            return getOrganizations();
        }
        return getOrganizations().stream()
                .filter(org -> current.has(base, org.getId().getValue()))
                .toList();
    }

    /**
     * The orgs whose hub the signed-in user can open: the ones they administer plus the ones where they hold
     * any org-scoped privilege. Drives the menu's org entries, so reachability and the hub gate agree.
     */
    public List<Organization> visibleOrgs() {
        final Caller current = caller();
        if (!current.isAuthenticated()) {
            return List.of();
        }
        // On an organization's own site the menu names that org alone -- even for a site admin, another
        // tenant's name is not that site's business (the shared site keeps the full list).
        final SiteContext site = SiteContext.current();
        final List<Organization> onThisSite = getOrganizations().stream()
                .filter(org -> site.admits(org.getId().getValue()))
                .toList();
        if (current.isSiteAdmin()) {
            return onThisSite;
        }
        return onThisSite.stream()
                .filter(org -> canViewOrgHub(org.getId().getValue()))
                .toList();
    }

    /**
     * The organizations the site admin's topbar org selector offers on a SHARED site: the ones that share
     * it -- no site of their own. A hosted org's content is seen on its own site, never selected into a
     * shared site's menus. Site admins only (the selector is theirs); empty for everyone else.
     */
    public List<Organization> switchableOrgs() {
        if (!caller().isSiteAdmin()) {
            return List.of();
        }
        return getOrganizations().stream()
                .filter(org -> org.getSlug() == null || org.getSlug().isBlank())
                .toList();
    }

    /** Whether the signed-in user may create a trip belonging to this org. */
    public boolean canCreateTripFor(final String orgId) {
        return canManageOrg(orgId)
                || (orgId != null && !orgId.isBlank() && caller().has(PrivilegeCommands.ADD_TRIP, orgId));
    }

    /** The org hub (dashboard): admins plus anyone holding an org-scoped privilege there. */
    public boolean canViewOrgHub(final String orgId) {
        if (canManageOrg(orgId)) {
            return true;
        }
        if (orgId == null || orgId.isBlank()) {
            return false;
        }
        final Caller current = caller();
        // The operational grants open the dashboard; an org's content/media editors work on its SITE.
        return PrivilegeCommands.ORG_HUB_BASES.stream().anyMatch(base -> current.has(base, orgId));
    }

    /** The org Trips page: admins, plus addTrip holders (they need the list their button lives on). */
    public boolean canViewOrgTrips(final String orgId) {
        return canCreateTripFor(orgId);
    }

    /** The org People page: admins get the controls, peopleAdmin holders a read-only browse. */
    public boolean canViewOrgPeople(final String orgId) {
        return canManageOrg(orgId)
                || (orgId != null && !orgId.isBlank() && caller().has(PrivilegeCommands.PEOPLE_ADMIN, orgId));
    }

    /**
     * Whether the signed-in user may administer this person's record: site admin, or holder of
     * {@code peopleAdmin} in an org the subject belongs to. Both org lists are tiny and
     * {@link Caller#has} memoizes per request, so this is safe inside a search-result filter.
     */
    public boolean canAdminPerson(final Person.Id subjectId) {
        final Caller current = caller();
        if (subjectId == null || !current.isAuthenticated()) {
            return false;
        }
        if (current.isSiteAdmin()) {
            return true;
        }
        final Person subject = DAO.getInstance().getPerson(subjectId, Cached.YES).orElse(null);
        if (subject == null) {
            return false;
        }
        return subject.getOrgIds().stream()
                .anyMatch(orgId -> current.has(PrivilegeCommands.PEOPLE_ADMIN, orgId.getValue()));
    }

    /**
     * The org-scoped privilege bases this org may grant, allow-list-filtered -- the ONE source for both the
     * People page's toggles/chips and {@link #grantOrgPrivilege}'s enforcement, so UI and server cannot
     * drift. Empty unless the viewer can at least see the People page.
     */
    public List<String> grantableOrgPrivileges(final String orgId) {
        final Organization org = findOrganization(orgId);
        if (org == null || !canViewOrgPeople(orgId)) {
            return List.of();
        }
        return PrivilegeCommands.ORG_SCOPED_BASES.stream().filter(org::mayGrant).toList();
    }

    /** Display names for the trip-scoped role bases, as the trip editor has always labelled them. */
    private static final java.util.Map<String, String> TRIP_ROLE_NAMES = java.util.Map.of(
            PrivilegeCommands.TRIP_MGR, "Editor Admin",
            PrivilegeCommands.TRIP_FIN_ADMIN, "Finance Admin",
            PrivilegeCommands.TRIP_FIN_VIEW, "Finance Viewer",
            PrivilegeCommands.TRIP_VIEW, "Viewer",
            PrivilegeCommands.CHAT_MGR, "Chat Admin",
            PrivilegeCommands.REGISTRATION_ADMIN, "Registration Admin");

    /**
     * The trip editor's manager-role definitions ({@code name} / {@code desc} / {@code base} maps), built
     * from {@link #grantableTripBases} so the rendered role list and the {@link #setTripRole} enforcement
     * are the same filter. Replaces the list the page used to assemble inline (which could not be
     * allow-list-aware).
     */
    public List<java.util.Map<String, String>> tripRoleDefs(final Trip trip) {
        return grantableTripBases(trip).stream().map(base -> roleDef(trip, base)).toList();
    }

    private java.util.Map<String, String> roleDef(final Trip trip, final String base) {
        final String name = TRIP_ROLE_NAMES.getOrDefault(base, base);
        return java.util.Map.of("name", name, "desc", trip.getTitle() + " - " + name, "base", base);
    }

    /** ALL trip-scoped role bases, unfiltered -- the display list for who-holds-what (never a grant list). */
    public List<String> allTripRoleBases() {
        return PrivilegeCommands.TRIP_SCOPED_BASES;
    }

    /**
     * The roles this person holds on the trip, spanning ALL bases (a role outside the org's allow-list must
     * still show on its holder), each as a role-def map plus a {@code grantable} flag ("true"/"false") --
     * the flag drives whether the manager roster renders the chip's remove control, mirroring
     * {@link #setTripRole}'s enforcement so a rendered X can never be a refused click.
     */
    public List<java.util.Map<String, String>> heldTripRoles(final Trip trip, final Person.Id personId) {
        if (trip == null || personId == null) {
            return List.of();
        }
        final PrivilegeCommands priv = privCommands();
        final List<String> grantable = grantableTripBases(trip);
        return allTripRoleBases().stream()
                .filter(base -> priv.check(base, trip.getId(), personId))
                .map(base -> heldRoleDef(trip, base, grantable.contains(base)))
                .toList();
    }

    private java.util.Map<String, String> heldRoleDef(final Trip trip, final String base,
            final boolean grantable) {
        final String name = TRIP_ROLE_NAMES.getOrDefault(base, base);
        return java.util.Map.of("name", name, "desc", trip.getTitle() + " - " + name, "base", base,
                "grantable", Boolean.toString(grantable));
    }

    /**
     * The roles the viewer may still grant this person on the trip: the allow-list-filtered grant list minus
     * what they already hold -- the manager roster's per-row "Add role" menu items.
     */
    public List<java.util.Map<String, String>> addableTripRoles(final Trip trip, final Person.Id personId) {
        if (trip == null || personId == null) {
            return List.of();
        }
        final PrivilegeCommands priv = privCommands();
        return grantableTripBases(trip).stream()
                .filter(base -> !priv.check(base, trip.getId(), personId))
                .map(base -> roleDef(trip, base))
                .toList();
    }

    /** The standard roles a creator receives on their just-created trip. */
    private static final List<String> CREATOR_TRIP_BASES = List.of(PrivilegeCommands.TRIP_MGR,
            PrivilegeCommands.TRIP_VIEW, PrivilegeCommands.REGISTRATION_ADMIN);

    /**
     * Grants the caller their standard roles on a trip they JUST created -- call only from the create
     * paths (page save, REST POST), never as a general grant API: it deliberately skips the org-admin
     * gate ({@link #setTripRole}) because an {@code addTrip@org} creator is usually not an org admin,
     * and creation without the ability to edit would be useless.
     *
     * <p>The org's allow-list still bounds it (user decision 2026-08-24: no bypass). Withheld roles come
     * back as DISPLAY names for the page's warning dialog, a notice is filed to the support channel so a
     * site admin can follow up, and the {@code showRoleWarning} ajax callback param is published for the
     * page's oncomplete. An empty result means every role landed.
     */
    public List<String> grantCreatorTripRoles(final Trip trip) {
        final Caller current = caller();
        if (trip == null || trip.getOrgId() == null || !current.isAuthenticated()) {
            return List.of();
        }
        final Organization owner = findOrganization(trip.getOrgId());
        if (owner == null) {
            return List.of();
        }
        final PrivilegeCommands priv = privCommands();
        final List<String> missing = new ArrayList<>();
        for (final String base : CREATOR_TRIP_BASES) {
            if (current.isSiteAdmin() || owner.mayGrant(base)) {
                final Privilege row = priv.getOrCreate(base, trip.getId(),
                        trip.getTitle() + " - " + TRIP_ROLE_NAMES.getOrDefault(base, base));
                priv.savePrivilege(row.withNewPerson(current.personId()), current.auditActor());
            } else {
                missing.add(TRIP_ROLE_NAMES.getOrDefault(base, base));
            }
        }
        if (!missing.isEmpty()) {
            supportSource.get().fileMissingTripRolesNotice(trip.getId(), trip.getTitle(),
                    owner.getName(), missing);
            publishParam("showRoleWarning", true);
        }
        return missing;
    }

    /**
     * {@link #addableTripRoles} for the role-picker overlay, whose subject arrives as the raw id string the
     * opening click stashed in the view (null before any row has been clicked). A distinct name, not an
     * overload: EL resolves overloads by runtime argument type and a null string would match either.
     */
    public List<java.util.Map<String, String>> addableRolesFor(final Trip trip, final String personId) {
        if (personId == null || personId.isBlank()) {
            return List.of();
        }
        return addableTripRoles(trip, Person.Id.from(personId));
    }

    /** Result cap for {@link #completeTripManagerCandidates}, matching the people pickers' default. */
    private static final int MANAGER_PICKER_LIMIT = 25;

    /**
     * Autocomplete for the trip editor's Add Manager picker: members of the trip's owning org ONLY --
     * tenancy: an org admin must never see (or be able to search) people outside their org, so the global
     * people search is off limits here. The subject trip resolves from the page's pinned id
     * ({@code viewScope.theTripId}, the {@link BadgePhotoCommands} pattern) because an autocomplete query
     * request carries nothing else. Answers empty when the viewer may not manage the trip's roles: the
     * completion endpoint is POSTable without ever opening the dialog, so the authorization lives on the
     * data source, not the button. An org-less trip is site-admin-only territory (see
     * {@link #grantableTripBases}), and only there does the global search still back the picker.
     */
    public List<Person> completeTripManagerCandidates(final String query) {
        final Trip trip = tripFromView();
        if (trip == null || grantableTripBases(trip).isEmpty()) {
            return List.of();
        }
        final Organization owner = (trip.getOrgId() == null) ? null : findOrganization(trip.getOrgId());
        if (owner == null) {
            return caller().isSiteAdmin()
                    ? DAO.getInstance().searchPeople(query, MANAGER_PICKER_LIMIT, Cached.NO) : List.of();
        }
        final String needle = (query == null) ? "" : query.trim().toLowerCase(Locale.ROOT);
        return getMembers(owner).stream()
                .filter(member -> matchesPicker(member, needle))
                .sorted(Comparator.comparing(OrgCommands::rosterSortKey))
                .limit(MANAGER_PICKER_LIMIT)
                .toList();
    }

    /** Case-insensitive substring over the names and email the picker's row label shows. */
    private static boolean matchesPicker(final Person person, final String lowerNeedle) {
        if (lowerNeedle.isEmpty()) {
            return true;
        }
        final String haystack = (person.getPreferredName() + " " + person.getFirst() + " "
                + person.getLast() + " " + person.getEmail()).toLowerCase(Locale.ROOT);
        return haystack.contains(lowerNeedle);
    }

    private static String rosterSortKey(final Person person) {
        return (person.getLast() + " " + person.getPreferredName()).toLowerCase(Locale.ROOT);
    }

    /**
     * The trip the editor page pins in the view, or null off-page. Seam: tests hand the trip back directly
     * ({@code ScopeUtil} needs a FacesContext).
     */
    protected Trip tripFromView() {
        final Object id = org.paulsens.trip.util.ScopeUtil.getInstance().getViewMap("theTripId");
        return (id == null) ? null : DAO.getInstance().getTrip(id.toString(), Cached.YES).orElse(null);
    }

    /** The trip-scoped role bases the given trip's org may grant (site admin: unfiltered). */
    public List<String> grantableTripBases(final Trip trip) {
        if (trip == null) {
            return List.of();
        }
        if (caller().isSiteAdmin()) {
            return PrivilegeCommands.TRIP_SCOPED_BASES;
        }
        final Organization owner = (trip.getOrgId() == null) ? null : findOrganization(trip.getOrgId());
        if (owner == null || !canManageOrg(owner.getId().getValue())) {
            return List.of();
        }
        return PrivilegeCommands.TRIP_SCOPED_BASES.stream().filter(owner::mayGrant).toList();
    }

    /**
     * Grants an org-scoped privilege to an org member. Org-admin only; the base must be one the org's
     * allow-list permits (site admins bypass the allow-list); the grantee must be a member but need NOT be
     * an org admin. The privilege save itself audits the grant delta.
     */
    public boolean grantOrgPrivilege(final String orgId, final Person.Id personId, final String base) {
        final Organization org = findOrganization(orgId);
        if (org == null || personId == null) {
            return fail("Unable to grant", "Unknown organization or person.");
        }
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can grant privileges.");
        }
        if (!PrivilegeCommands.ORG_SCOPED_BASES.contains(base)) {
            return fail("Unable to grant", "'" + base + "' is not an organization-scoped privilege.");
        }
        if (!caller().isSiteAdmin() && !org.mayGrant(base)) {
            return fail("Not available", "This organization does not have access to '" + base + "'.");
        }
        if (!isMember(orgId, personId)) {
            return fail("Not a member", "Privileges can only be granted to members of the organization.");
        }
        final PrivilegeCommands priv = privCommands();
        // Canonical description (org bases always have one): what the row MEANS beats "Acme peopleAdmin"
        // in the editor's Description column, and matches what the privilege editors themselves stamp.
        final Privilege row = priv.getOrCreate(base, org.getId().getValue(), priv.baseDescription(base));
        return priv.savePrivilege(row.withNewPerson(personId), caller().auditActor());
    }

    /**
     * Revokes an org-scoped privilege. Org-admin only, but deliberately NOT allow-list-checked: revocation
     * must keep working after a site admin restricts the org, or stale grants become unremovable.
     */
    public boolean revokeOrgPrivilege(final String orgId, final Person.Id personId, final String base) {
        if (findOrganization(orgId) == null || personId == null) {
            return fail("Unable to revoke", "Unknown organization or person.");
        }
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can revoke privileges.");
        }
        final PrivilegeCommands priv = privCommands();
        final Privilege row = priv.getPrivilege(base, orgId);
        if (!row.getPeople().contains(personId)) {
            return true;
        }
        return priv.savePrivilege(row.withoutPerson(personId), caller().auditActor());
    }

    /** Display names for the org-scoped privilege bases, matching the trip roster's friendly labels. */
    private static final java.util.Map<String, String> ORG_PRIV_NAMES = java.util.Map.of(
            PrivilegeCommands.PEOPLE_ADMIN, "People Admin",
            PrivilegeCommands.ADD_TRIP, "Create Trips",
            PrivilegeCommands.EMAIL_ADMIN, "Email Admin",
            PrivilegeCommands.PAYMENTS_ADMIN, "Payments Admin",
            PrivilegeCommands.CONTENT_ADMIN, "Site Content Editor",
            PrivilegeCommands.MEDIA_ADMIN, "Site Media Editor");

    /**
     * The org-scoped privileges this person holds here, each as a {@code name}/{@code desc}/{@code base}
     * map -- the People page's chip row. Spans ALL org bases (a grant outside the current allow-list must
     * still show on its holder) and carries no grantable flag, because {@link #revokeOrgPrivilege} is
     * deliberately not allow-list-checked: every rendered chip is removable by an org admin.
     */
    public List<java.util.Map<String, String>> heldOrgPrivs(final String orgId, final Person.Id personId) {
        if (personId == null || !canViewOrgPeople(orgId)) {
            return List.of();
        }
        final PrivilegeCommands priv = privCommands();
        return PrivilegeCommands.ORG_SCOPED_BASES.stream()
                .filter(base -> priv.check(base, orgId, personId))
                .map(this::orgPrivDef)
                .toList();
    }

    /**
     * The org-scoped privileges an admin may still grant this person: {@link #grantableOrgPrivileges}
     * (the allow-list filter) minus what they already hold -- the chip row's "+ Add privilege" menu.
     */
    public List<java.util.Map<String, String>> addableOrgPrivs(final String orgId,
            final Person.Id personId) {
        if (personId == null) {
            return List.of();
        }
        final PrivilegeCommands priv = privCommands();
        return grantableOrgPrivileges(orgId).stream()
                .filter(base -> !priv.check(base, orgId, personId))
                .map(this::orgPrivDef)
                .toList();
    }

    /**
     * {@link #addableOrgPrivs} for the privilege-picker overlay, whose subject arrives as the raw id
     * string the opening click stashed in the view (null before any row has been clicked). A distinct
     * name, not an overload: EL resolves overloads by runtime argument type and a null string would
     * match either.
     */
    public List<java.util.Map<String, String>> addableOrgPrivsFor(final String orgId,
            final String personId) {
        if (personId == null || personId.isBlank()) {
            return List.of();
        }
        return addableOrgPrivs(orgId, Person.Id.from(personId));
    }

    private java.util.Map<String, String> orgPrivDef(final String base) {
        return java.util.Map.of("name", ORG_PRIV_NAMES.getOrDefault(base, base),
                "desc", privCommands().baseDescription(base), "base", base);
    }

    /**
     * Grants or revokes a trip-scoped role -- the server-side enforcement behind the trip editor's manager
     * checkboxes. Site admin, or an admin of the trip's org whose allow-list permits the base.
     */
    public boolean setTripRole(final String tripId, final Person.Id personId, final String base,
            final boolean granted) {
        final Trip trip = (tripId == null || tripId.isBlank()) ? null
                : DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        if (trip == null || personId == null) {
            return fail("Unable to save", "Unknown trip or person.");
        }
        if (!caller().isSiteAdmin() && !canManageOrg(trip.getOrgId())) {
            return fail("Not allowed", "Only this trip's organization admins can change trip roles.");
        }
        if (!grantableTripBases(trip).contains(base)) {
            return fail("Not available", "'" + base + "' is not a role this organization can grant.");
        }
        // Tenancy: an org's trip roles go to that org's members, period (the same stance as
        // grantOrgPrivilege, site admins included). Revokes stay unchecked so a departed member's
        // stale role is still removable. Org-less trips have no boundary to enforce.
        if (granted && trip.getOrgId() != null && !trip.getOrgId().isBlank()
                && !isMember(trip.getOrgId(), personId)) {
            return fail("Not a member", "Trip roles can only be granted to members of the trip's "
                    + "organization. Add the person to the organization first.");
        }
        final PrivilegeCommands priv = privCommands();
        final Privilege row = priv.getOrCreate(base, trip.getId(),
                base + " for trip '" + trip.getTitle() + "'");
        final Privilege changed = granted ? row.withNewPerson(personId) : row.withoutPerson(personId);
        // Same-object means no membership change; skip the no-op save (and its "unchanged" audit row).
        return changed == row || priv.savePrivilege(changed, caller().auditActor());
    }

    // ------------------------------------------------------------------ org-bounded mail merge

    /** Whether the signed-in user may use the mail-merge surface at all. */
    public boolean canMail() {
        return caller().isSiteAdmin() || holdsAnywhere(PrivilegeCommands.EMAIL_ADMIN);
    }

    /**
     * Every address the signed-in user may mail, lower-cased -- or {@code null} for a site admin
     * (unrestricted). For an {@code emailAdmin@org} holder: the members of each such org plus the rosters of
     * those orgs' trips. Computed on demand only (send/search time) -- never per row at render.
     */
    public java.util.Set<String> allowedRecipientEmails() {
        if (caller().isSiteAdmin()) {
            return null;
        }
        final java.util.Set<String> allowed = new java.util.HashSet<>();
        for (final Organization org : orgsWithPriv(PrivilegeCommands.EMAIL_ADMIN)) {
            getMembers(org).forEach(member -> addEmail(allowed, member.getEmail()));
            for (final Trip trip : orgTrips(org.getId().getValue())) {
                for (final Person.Id personId : trip.getPeople()) {
                    DAO.getInstance().getPerson(personId, Cached.YES)
                            .ifPresent(person -> addEmail(allowed, person.getEmail()));
                }
            }
        }
        return allowed;
    }

    /** Whether the signed-in user may mail this trip's roster (bounds the mail page's trip picker AND its
     *  roster listing, so a forged trip id cannot enumerate another tenant's addresses). */
    public boolean canMailTrip(final String tripId) {
        final Trip trip = (tripId == null || tripId.isBlank()) ? null
                : DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        if (trip == null || !canMail()) {
            return false;
        }
        if (caller().isSiteAdmin()) {
            return true;
        }
        return trip.getOrgId() != null
                && caller().has(PrivilegeCommands.EMAIL_ADMIN, trip.getOrgId());
    }

    /** The trips whose rosters the signed-in user may mail, newest first. */
    public List<Trip> mailableTrips(final int limit) {
        if (!canMail()) {
            return List.of();
        }
        // Site-gated like every trip picker: the merge page resolves its pick through TripCommands.getTrip,
        // which answers blank for a trip this host does not reach (a hosted org's roster is mailed from
        // the org's own host).
        final List<Trip> recent = DAO.getInstance().getRecentTrips(limit, Cached.YES).stream()
                .filter(trip -> ListingScope.reachable(trip.getOrgId()))
                .toList();
        if (caller().isSiteAdmin()) {
            return recent;
        }
        return recent.stream().filter(trip -> canMailTrip(trip.getId())).toList();
    }

    /** People search bounded to the addresses the signed-in user may mail (site admin: unbounded). */
    public List<Person> searchMailablePeople(final String query, final int limit) {
        if (!canMail()) {
            return List.of();
        }
        final java.util.Set<String> allowed = allowedRecipientEmails();
        return PersonCommands.getPersonCommands().searchPeople(query, limit * 4).stream()
                .filter(person -> allowed == null || isAllowedEmail(allowed, person.getEmail()))
                .limit(limit)
                .toList();
    }

    /**
     * The mail-merge send, org-bounded server-side: recipients (and bcc entries) outside the caller's
     * allowed set are DROPPED with a growl naming the count, and the send proceeds for the rest. This is
     * the enforcement point behind the Send button -- the page never composes the filter itself.
     */
    public boolean sendMerge(final String from, final List<String> toEmails, final String bcc,
            final String replyTo, final String subject, final String body) {
        if (!canMail()) {
            return fail("Not allowed", "Sending mail requires the emailAdmin privilege.");
        }
        final java.util.Set<String> allowed = allowedRecipientEmails();
        final List<String> toList = (toEmails == null) ? List.of() : toEmails;
        final List<String> accepted = toList.stream()
                .filter(email -> allowed == null || isAllowedEmail(allowed, email))
                .toList();
        final int rejected = toList.size() - accepted.size();
        if (rejected > 0) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, rejected + " recipient(s) outside "
                    + "your organization(s) were skipped.", null);
        }
        if (accepted.isEmpty()) {
            return fail("Nobody to mail", "No recipients are within your organization(s).");
        }
        // The composer can only produce an allowed domain, so this is the forged-post guard: a From on a
        // domain SES has not verified for this tenant is refused HERE rather than silently at SES.
        if (!mailAddr().isSendable(from, mergeMailDomains())) {
            return fail("Invalid From", "The From address must use one of your verified sending domains.");
        }
        final String boundedBcc = boundBcc(bcc, allowed);
        final MailCommands mail = mailSource.get();
        mail.sendTemplate(from, mail.emailsToPeople(accepted), boundedBcc, replyTo, subject, body);
        return true;
    }

    // ------------------------------------------------------------------ sending domains
    //
    // SES only accepts a From on a domain it has verified, so every From box in the app is a domain
    // DROPDOWN plus a typed mailbox (/WEB-INF/mailFromComposer.xhtml). What the dropdown offers is the
    // org's allow-list: site admins narrow an org to the domains it owns, so one tenant can never send as
    // another's. An org that has never been narrowed (null or empty list) is unrestricted -- every
    // existing org keeps working with no migration.

    /** Every domain SES has verified for sending, sorted; empty when SES is unreachable. */
    public List<String> siteSendingDomains() {
        // Local mode answers the fixed fake list without resolving the mail bean: SES is unreachable off
        // AWS anyway, and Beans.get needs a CDI container the unit-test JVM does not have.
        return FakeData.isLocal() ? MailCommands.LOCAL_SENDING_DOMAINS
                : mailSource.get().verifiedSendingDomains();
    }

    /** The address composer/splitter, over THIS bean's domain source so a test seam reaches it too. */
    private MailAddressCommands mailAddr() {
        MailAddressCommands addr = mailAddrCache;
        if (addr == null) {
            addr = new MailAddressCommands(this::siteSendingDomains);
            mailAddrCache = addr;
        }
        return addr;
    }

    /** The domains this organization's From addresses may use -- its allow-list narrowed to what SES
     *  currently verifies (a domain dropped in SES stops being offered even if still listed here). */
    public List<String> mailDomains(final String orgId) {
        return allowedDomains(findOrganization(orgId));
    }

    /** {@link #mailDomains} for the org that owns a trip; an org-less trip gets the site-wide list. */
    public List<String> mailDomainsForTrip(final Trip trip) {
        return allowedDomains(ownerOf(trip));
    }

    /** The org's preferred domain when it is still allowed, else "" (the composer picks the first). */
    public String defaultMailDomain(final String orgId) {
        return preferredDomain(findOrganization(orgId));
    }

    /** {@link #defaultMailDomain} for the org that owns a trip. */
    public String defaultMailDomainForTrip(final Trip trip) {
        return preferredDomain(ownerOf(trip));
    }

    /** The allow-list as one display string for the Organizations table -- "any verified" when unset. */
    public String mailDomainsLabel(final Organization org) {
        final List<String> stored = (org == null) ? null : org.getMailDomains();
        return (stored == null || stored.isEmpty()) ? "any verified" : String.join(", ", stored);
    }

    /** The raw allow-list for the site-admin editor: what IS checked, empty meaning "not restricted". */
    public List<String> storedMailDomains(final String orgId) {
        final Organization org = findOrganization(orgId);
        final List<String> stored = (org == null) ? null : org.getMailDomains();
        return (stored == null) ? List.of() : List.copyOf(stored);
    }

    /** The org's stored subdomain slug for the site-admin editor ("" when the org has no subdomain site). */
    public String storedSlug(final String orgId) {
        final Organization org = findOrganization(orgId);
        return (org == null || org.getSlug() == null) ? "" : org.getSlug();
    }

    private Organization ownerOf(final Trip trip) {
        return (trip == null) ? null : findOrganization(trip.getOrgId());
    }

    private List<String> allowedDomains(final Organization org) {
        final List<String> verified = siteSendingDomains();
        final List<String> restricted = (org == null) ? null : org.getMailDomains();
        if (restricted == null || restricted.isEmpty()) {
            return verified;
        }
        final java.util.Set<String> wanted = restricted.stream()
                .filter(domain -> domain != null && !domain.isBlank())
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return verified.stream().filter(wanted::contains).toList();
    }

    private String preferredDomain(final Organization org) {
        final String wanted = (org == null || org.getDefaultMailDomain() == null) ? ""
                : org.getDefaultMailDomain().trim().toLowerCase(Locale.ROOT);
        return allowedDomains(org).contains(wanted) ? wanted : "";
    }

    /** {@link #saveOrgEdits(String, String, String, String, List, String, String)} leaving the slug alone. */
    public boolean saveOrgEdits(final String orgId, final String name, final String abbreviation,
            final String contactEmail, final List<String> domains, final String defaultDomain) {
        return saveOrgEdits(orgId, name, abbreviation, contactEmail, domains, defaultDomain, null);
    }

    /** The 7-arg save leaving the org's shared-sites choice alone. */
    public boolean saveOrgEdits(final String orgId, final String name, final String abbreviation,
            final String contactEmail, final List<String> domains, final String defaultDomain,
            final String slug) {
        return saveOrgEdits(orgId, name, abbreviation, contactEmail, domains, defaultDomain, slug, null);
    }

    /** Whether the org currently allows shared sites to show its content (the profile checkbox's seed). */
    public boolean storedAllowSharedSites(final String orgId) {
        final Organization org = findOrganization(orgId);
        return org == null || org.allowsSharedSites();
    }

    /**
     * Page-facing profile save with the mail-domain and subdomain rows: {@code domains} (the site-admin
     * allow-list) and {@code slug} (the org's subdomain) are applied ONLY for a site admin -- an org
     * admin's post carries the same fields, and silently ignoring them is what keeps the shared include
     * safe to render for both. {@code defaultDomain} is an org-admin choice, kept only while the
     * allow-list still permits it, and so is {@code allowShared} -- the org side of the shared-site gate
     * ({@link Organization#getAllowSharedSites()}; null = leave it alone).
     */
    public boolean saveOrgEdits(final String orgId, final String name, final String abbreviation,
            final String contactEmail, final List<String> domains, final String defaultDomain,
            final String slug, final Boolean allowShared) {
        final Organization fresh = freshOrg(orgId);
        if (fresh == null) {
            return fail("Unable to save", "Unknown organization.");
        }
        fresh.setName(name);
        fresh.setAbbreviation(abbreviation);
        fresh.setContactEmail(contactEmail);
        if (caller().isSiteAdmin()) {
            fresh.setMailDomains(cleanDomains(domains));
            if (!applySlug(fresh, slug)) {
                return false;
            }
        }
        if (allowShared != null) {
            // Stored as null when allowed (the default every existing row already has), false when not.
            fresh.setAllowSharedSites(allowShared ? null : Boolean.FALSE);
        }
        fresh.setDefaultMailDomain(chosenDefault(fresh, defaultDomain));
        if (!saveOrganization(fresh)) {
            return false;
        }
        if (fresh.getSlug() != null) {
            // A subdomain's first assignment also gives the site something to show (once; see
            // ensureHomePage). A seeding failure growls on its own -- the profile itself did save.
            ensureHomePage(orgId);
        }
        return true;
    }

    /**
     * Seeds the organization's default home page (see {@code OrgPageBootstrap}) the first time it is asked
     * for -- on the subdomain's assignment, and lazily from the org dashboard for an org whose slug predates
     * seeding. Exactly once per org: the org row records the seeding, so an org that later empties its page
     * keeps it empty. Site admins and the org's own admins may trigger it; everyone else is a no-op.
     *
     * @return whether the org now has (or already had) its seeded page; false when it has no subdomain, the
     *         caller may not manage it, or the seed failed (with a growl)
     */
    public boolean ensureHomePage(final String orgId) {
        final Organization org = freshOrg(orgId);
        if (org == null || org.getSlug() == null || org.getSlug().isBlank()) {
            return false;
        }
        if (org.getHomePageSeededAt() != null) {
            return true;
        }
        if (!canManageOrg(orgId)) {
            return false;
        }
        return seedHomePage(org);
    }

    private boolean seedHomePage(final Organization org) {
        final String pageKey = OrgPageBootstrap.pageKey(org.getId());
        try {
            // A page someone already authored by hand is theirs: seeding only ever fills an EMPTY page.
            if (DAO.getInstance().getContentForSection(pageKey, Cached.NO).isEmpty()) {
                final int retain = new ConfigCommands().getInt(KnownSettings.CONTENT_VERSIONS_RETAINED, 0, 50);
                for (final ContentInstance row : OrgPageBootstrap.rows(org, this::currentTemplateVersion)) {
                    if (!DAO.getInstance().saveContent(row, retain)) {
                        return fail("Home page not created",
                                "The starter page for '" + org.getName() + "' could not be saved.");
                    }
                }
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to seed the home page of organization {}", org.getId(), ex);
            return fail("Home page not created", "The starter page could not be saved: " + ex.getMessage());
        }
        org.setHomePageSeededAt(LocalDateTime.now());
        if (!saveOrgOrWarn(org)) {
            return false;
        }
        audit(caller(), org, "Default home page seeded for the site '" + org.getSlug() + "'");
        return true;
    }

    /** The version a starter row pins: the template's CURRENT one, or 1 when it cannot be read. */
    private int currentTemplateVersion(final String templateId) {
        try {
            return DAO.getInstance().getTemplate(templateId, Cached.NO)
                    .map(ContentTemplate::getVersion)
                    .filter(version -> version > 0)
                    .orElse(1);
        } catch (final RuntimeException ex) {
            log.warn("Unable to read template {} while seeding a home page; pinning v1", templateId, ex);
            return 1;
        }
    }

    /**
     * The subdomain labels no org may claim: platform names (present and plausible future), mail
     * plumbing, and anything an attacker could pass off as the product itself. Checked at ASSIGNMENT --
     * retrofitting a reservation would evict a live tenant, so err on the side of reserving now.
     */
    static final java.util.Set<String> RESERVED_SLUGS = java.util.Set.of(
            "www", "mail", "smtp", "imap", "pop", "bounce", "mx", "ns1", "ns2", "api", "app", "admin",
            "static", "cdn", "assets", "files", "my", "status", "support", "help", "blog", "docs", "dev",
            "test", "staging", "demo", "login", "auth", "sso", "account", "accounts", "secure", "billing",
            "pay", "payments", "unitetrip", "autodiscover", "autoconfig", "mta-sts", "unsubscribe");

    /** Lowercase DNS-label grammar: no leading/trailing hyphen, 63 chars max. */
    private static final java.util.regex.Pattern SLUG_SHAPE =
            java.util.regex.Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    /**
     * Applies the site-admin's slug edit onto the org being saved, or refuses (growl + false) when the
     * value is malformed, reserved, or already another org's. {@code null} means "leave it alone" (callers
     * without a slug field in hand); an explicit BLANK clears it -- the ONLINE downgrade to the shared-site
     * tier, with the org's data and content kept for a later re-assignment.
     */
    private boolean applySlug(final Organization org, final String slug) {
        if (slug == null) {
            return true;
        }
        if (slug.isBlank()) {
            org.setSlug(null);
            return true;
        }
        final String wanted = slug.trim().toLowerCase(Locale.ROOT);
        if (!SLUG_SHAPE.matcher(wanted).matches()) {
            return fail("Invalid subdomain", "A subdomain is lowercase letters, digits and hyphens "
                    + "(not at the ends), at most 63 characters.");
        }
        if (RESERVED_SLUGS.contains(wanted)) {
            return fail("Reserved subdomain", "\"" + wanted + "\" is reserved for the platform.");
        }
        final boolean taken = DAO.getInstance().getOrganizations(Cached.NO).stream()
                .anyMatch(other -> !other.getId().equals(org.getId()) && wanted.equals(other.getSlug()));
        if (taken) {
            return fail("Subdomain taken", "Another organization already uses \"" + wanted + "\".");
        }
        org.setSlug(wanted);
        return true;
    }

    /** Null (never restricted) for an empty pick, else the lower-cased, de-duplicated list. */
    private static List<String> cleanDomains(final List<String> domains) {
        if (domains == null) {
            return null;
        }
        final List<String> clean = domains.stream()
                .filter(domain -> domain != null && !domain.isBlank())
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        return clean.isEmpty() ? null : clean;
    }

    /** The submitted default domain, kept only when the org's (just-updated) allow-list permits it. */
    private String chosenDefault(final Organization org, final String defaultDomain) {
        if (defaultDomain == null || defaultDomain.isBlank()) {
            return null;
        }
        final String wanted = defaultDomain.trim().toLowerCase(Locale.ROOT);
        return allowedDomains(org).contains(wanted) ? wanted : null;
    }

    /**
     * The organization whose mail settings drive the merge page for this caller, or null. Deliberately
     * null for a SITE admin: they hold emailAdmin everywhere, so "the first org alphabetically" would be
     * an arbitrary tenant's contact address and allow-list standing in for the whole site.
     */
    public Organization mailingOrg() {
        return caller().isSiteAdmin() ? null
                : orgsWithPriv(PrivilegeCommands.EMAIL_ADMIN).stream().findFirst().orElse(null);
    }

    /** The domains the mail-merge From composer offers: the caller's org allow-list, or all verified. */
    public List<String> mergeMailDomains() {
        return allowedDomains(mailingOrg());
    }

    /** The org's preferred domain for the merge composer, or "" when none applies. */
    public String mergeDefaultDomain() {
        return preferredDomain(mailingOrg());
    }

    /**
     * The mail-merge From seed. The org's contact address when SES can actually SEND as it -- that is the
     * address people expect to see, and it needs no Reply-To to work. Otherwise (the common case: a parish
     * gmail or an unverified domain) the Site email seeds the composer and the contact address becomes the
     * Reply-To instead, via {@link #mergeReplyToSeed}. Typing an unverified From here used to be accepted
     * by the page and then refused by SES at send time, with nothing explaining why.
     */
    public String mergeFromSeed() {
        final Organization org = mailingOrg();
        final String contact = (org == null) ? null : org.getContactEmail();
        final MailAddressCommands addr = mailAddr();
        return addr.isSendable(contact, allowedDomains(org)) ? contact : addr.siteFrom();
    }

    /** The merge Reply-To seed: the org's contact address when it could NOT be the From, else the From. */
    public String mergeReplyToSeed() {
        final Organization org = mailingOrg();
        final String contact = (org == null) ? null : org.getContactEmail();
        final MailAddressCommands addr = mailAddr();
        if (contact != null && !contact.isBlank() && !addr.isSendable(contact, allowedDomains(org))) {
            return MailAddressCommands.addressOf(contact);
        }
        return MailAddressCommands.addressOf(mergeFromSeed());
    }

    /**
     * Composes the merge page's From from its composer fields, growling and answering null when the
     * mailbox is malformed or the domain is not one this caller may send from.
     */
    public String composeMergeFrom(final String name, final String local, final String domain) {
        return mailAddr().composeAddress(name, local, domain, mergeMailDomains(), "The From address");
    }

    private List<Trip> orgTrips(final String orgId) {
        return DAO.getInstance().getRecentTrips(400, Cached.YES).stream()
                .filter(trip -> orgId.equals(trip.getOrgId()))
                .toList();
    }

    private static void addEmail(final java.util.Set<String> allowed, final String email) {
        if (email != null && !email.isBlank()) {
            allowed.add(email.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static boolean isAllowedEmail(final java.util.Set<String> allowed, final String email) {
        return email != null && allowed.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    /** The caller-bounded form of {@link #boundBcc} for edges that assemble their own sends (REST). */
    public String boundedBcc(final String bcc) {
        return boundBcc(bcc, allowedRecipientEmails());
    }

    /** BCC entries ride every message, so they are bounded exactly like recipients (comma-separated). */
    private static String boundBcc(final String bcc, final java.util.Set<String> allowed) {
        if (bcc == null || bcc.isBlank() || allowed == null) {
            return bcc;
        }
        final List<String> kept = java.util.Arrays.stream(bcc.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty() && isAllowedEmail(allowed, entry))
                .toList();
        return kept.isEmpty() ? null : String.join(",", kept);
    }

    /** Whether this person is on the roster of any trip belonging to this org (blocks member removal). */
    public boolean isOnAnyOrgTrip(final String orgId, final Person.Id personId) {
        if (orgId == null || orgId.isBlank() || personId == null) {
            return false;
        }
        return DAO.getInstance().getTripsForUser(personId, Cached.YES).stream()
                .anyMatch(trip -> orgId.equals(trip.getOrgId()));
    }

    /**
     * Site-admin edit of the org's allow-list. {@code null} resets to "never restricted" (everything);
     * non-null lists are validated against the known grantable bases so a typo cannot silently disable a
     * feature.
     */
    public boolean setGrantablePrivileges(final String orgId, final List<String> bases) {
        final Caller current = caller();
        if (!current.isSiteAdmin()) {
            return fail("Not allowed", "Only a site administrator can restrict an organization's privileges.");
        }
        final Organization fresh = (orgId == null || orgId.isBlank()) ? null
                : DAO.getInstance().getOrganization(Organization.Id.from(orgId.trim()), Cached.NO).orElse(null);
        if (fresh == null) {
            return fail("Unable to save", "Unknown organization.");
        }
        final List<String> cleaned;
        if (bases == null) {
            cleaned = null;
        } else {
            cleaned = bases.stream().distinct().toList();
            final List<String> unknown = cleaned.stream().filter(base -> !isGrantableBase(base)).toList();
            if (!unknown.isEmpty()) {
                return fail("Unable to save", "Unknown privilege name(s): " + String.join(", ", unknown));
            }
        }
        fresh.setGrantablePrivileges(cleaned);
        if (!saveOrgOrWarn(fresh)) {
            return false;
        }
        audit(current, fresh, "Allow-list set to " + (cleaned == null ? "ALL (never restricted)" : cleaned)
                + " for organization '" + fresh.getName() + "'");
        return true;
    }

    /**
     * The org's allow-list as the site-admin editor's checkboxes should render it: the stored list, or every
     * grantable base when the org was never restricted (null). Read-only display sugar -- enforcement stays
     * in {@link Organization#mayGrant}.
     */
    public List<String> effectiveGrantable(final String orgId) {
        final Organization org = findOrganization(orgId);
        if (org == null) {
            return List.of();
        }
        return (org.getGrantablePrivileges() == null) ? allGrantableBases() : org.getGrantablePrivileges();
    }

    /** Every base name an allow-list may contain, for the site-admin editor's checkboxes. */
    public List<String> allGrantableBases() {
        final List<String> all = new ArrayList<>(PrivilegeCommands.TRIP_SCOPED_BASES);
        all.addAll(PrivilegeCommands.ORG_SCOPED_BASES);
        return all;
    }

    private static boolean isGrantableBase(final String base) {
        return PrivilegeCommands.TRIP_SCOPED_BASES.contains(base)
                || PrivilegeCommands.ORG_SCOPED_BASES.contains(base);
    }

    private PrivilegeCommands privCommands() {
        return new PrivilegeCommands();
    }

    // ------------------------------------------------------------------ payment processor configs

    /** Processor type names for the settings page's picker. */
    public List<String> getProcessorTypes() {
        return java.util.Arrays.stream(ProcessorType.values()).map(Enum::name).toList();
    }

    /** This org's processor configs, label-sorted, for its settings page. Admin-gated like the page. */
    public List<PaymentProcessorConfig> getProcessorConfigs(final String orgId) {
        if (!canManageOrg(orgId)) {
            return List.of();
        }
        return DAO.getInstance()
                .getPaymentProcessorConfigs(Organization.Id.from(orgId.trim()), Cached.YES).stream()
                .sorted(Comparator.comparing(PaymentProcessorConfig::getLabel,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** One config, or null. The (orgId, configId) composite get IS the tenancy check. */
    public PaymentProcessorConfig findProcessorConfig(final String orgId, final String configId) {
        if (orgId == null || orgId.isBlank() || configId == null || configId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getPaymentProcessorConfig(Organization.Id.from(orgId.trim()),
                PaymentProcessorConfig.Id.from(configId.trim()), Cached.NO).orElse(null);
    }

    /**
     * Page-facing create/edit (blank configId = create). Field values are applied onto a FRESH read; the
     * optimistic version surfaces lost admin races. Secrets never travel through here -- see
     * {@link #setProcessorSecret}.
     */
    public boolean saveProcessorConfig(final String orgId, final String configId, final String label,
            final String typeName, final String modeName, final boolean enabled,
            final String clientId, final String sandboxClientId, final int feeBps, final int feeFixedCents) {
        final Caller current = caller();
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can manage payment processors.");
        }
        if (label == null || label.isBlank()) {
            return fail("Label required", "Give this processor configuration a label.");
        }
        final ProcessorType type = parseType(typeName);
        if (type == null) {
            return fail("Type required", "Pick a processor type.");
        }
        final PaymentProcessorConfig config;
        if (configId == null || configId.isBlank()) {
            config = PaymentProcessorConfig.builder()
                    .orgId(Organization.Id.from(orgId.trim()))
                    .createdBy(current.personId())
                    .created(LocalDateTime.now())
                    .build();
        } else {
            config = findProcessorConfig(orgId, configId);
            if (config == null) {
                return fail("Unable to save", "Unknown processor configuration.");
            }
        }
        config.setLabel(label);
        config.setType(type);
        config.setMode("LIVE".equalsIgnoreCase(modeName)
                ? PaymentProcessorConfig.ProcessorMode.LIVE
                : PaymentProcessorConfig.ProcessorMode.SANDBOX);
        config.setEnabled(enabled);
        config.getPublicConfig().put("clientId", (clientId == null) ? "" : clientId.trim());
        config.getSandboxPublicConfig().put("clientId",
                (sandboxClientId == null) ? "" : sandboxClientId.trim());
        config.setFeeBps(Math.max(0, feeBps));
        config.setFeeFixedCents(Math.max(0, feeFixedCents));
        if (!saveProcessorOrWarn(config)) {
            return false;
        }
        audit(current, findOrganization(orgId),
                "Processor config '" + label + "' (" + type + ") saved; enabled=" + enabled);
        return true;
    }

    /**
     * Stores pasted secrets for a config (write-only from the UI; blank means "leave unchanged"). Values go
     * to the payment secret, never to DynamoDB.
     */
    public boolean setProcessorSecret(final String orgId, final String configId,
            final String liveSecret, final String sandboxSecret) {
        final Caller current = caller();
        final PaymentProcessorConfig config = findProcessorConfig(orgId, configId);
        if (config == null || !canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can manage payment processors.");
        }
        final java.util.Map<String, String> fields = new java.util.HashMap<>();
        if (liveSecret != null && !liveSecret.isBlank()) {
            fields.put("clientSecret", liveSecret);
        }
        if (sandboxSecret != null && !sandboxSecret.isBlank()) {
            fields.put("clientSecretSandbox", sandboxSecret);
        }
        if (fields.isEmpty()) {
            return true;
        }
        if (!ProcessorSecrets.getInstance().put(configId.trim(), fields)) {
            return fail("Unable to save", "The secret store rejected the write; nothing was changed.");
        }
        audit(current, findOrganization(orgId), "Secrets updated for processor config '"
                + config.getLabel() + "' (" + String.join(", ", fields.keySet()) + ")");
        return true;
    }

    /** Whether a (live/sandbox) secret is stored, for the settings page's badges. */
    public boolean hasProcessorSecret(final String configId, final boolean sandbox) {
        return configId != null && ProcessorSecrets.getInstance()
                .hasSecret(configId.trim(), sandbox ? "clientSecretSandbox" : "clientSecret");
    }

    /** The Test-connection button: null-success message contract turned into a growl by the page. */
    public boolean testProcessorConnection(final String orgId, final String configId, final boolean sandbox) {
        final PaymentProcessorConfig config = findProcessorConfig(orgId, configId);
        if (config == null || !canManageOrg(orgId)) {
            return fail("Not allowed", "Unknown processor configuration.");
        }
        final String secret = ProcessorSecrets.getInstance().get(configId.trim())
                .get(sandbox ? "clientSecretSandbox" : "clientSecret");
        final String failure = new ProcessorPing().ping(config, sandbox, secret);
        if (failure != null) {
            return fail("Connection failed", failure);
        }
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO,
                "Connection OK: '" + config.getLabel() + "'" + (sandbox ? " (sandbox)" : ""), null);
        return true;
    }

    /** Removal is SITE-admin only; the normal retirement path is disabling (history references configs). */
    public boolean deleteProcessorConfig(final String orgId, final String configId) {
        final Caller current = caller();
        final PaymentProcessorConfig config = findProcessorConfig(orgId, configId);
        if (config == null) {
            return fail("Unable to delete", "Unknown processor configuration.");
        }
        if (!current.isSiteAdmin()) {
            return fail("Not allowed", "Only a site administrator can delete a processor configuration; "
                    + "disable it instead.");
        }
        if (!DAO.getInstance().deletePaymentProcessorConfig(config.getOrgId(), config.getId())) {
            return fail("Unable to delete", "The delete did not complete.");
        }
        ProcessorSecrets.getInstance().put(configId.trim(),
                java.util.Map.of("clientSecret", "", "clientSecretSandbox", ""));
        audit(current, findOrganization(orgId), "Processor config '" + config.getLabel() + "' DELETED");
        return true;
    }

    // ------------------------------------------------------------------ payment config ladder

    /**
     * The fully-resolved payment configuration for this trip: trip overrides &rarr; org defaults &rarr; site
     * settings. Never null; {@code isPayable()} on the result answers "can this trip take payments".
     */
    public TripPaymentConfig effectivePaymentConfig(final Trip trip) {
        if (trip == null) {
            return siteDefaults();
        }
        final Organization owner = (trip.getOrgId() == null || trip.getOrgId().isBlank()) ? null
                : DAO.getInstance().getOrganization(Organization.Id.from(trip.getOrgId()), Cached.YES)
                        .orElse(null);
        final TripPaymentConfig orgRung = (owner == null)
                ? siteDefaults()
                : owner.getPaymentDefaults().overlayOn(siteDefaults());
        return trip.getPaymentConfig().overlayOn(orgRung);
    }

    // ------------------------------------------------------------------ the payment dialog's From composer

    /** "custom" when the trip overrides the From, "" when it inherits -- the dialog's mode menu value. */
    public String paymentFromMode(final Trip trip) {
        final String own = (trip == null) ? null : trip.getPaymentConfig().getMailFrom();
        return (own == null || own.isBlank()) ? "" : "custom";
    }

    /** What the dialog's From composer seeds from: the trip's own override, else the inherited value. */
    public String paymentFromSeed(final Trip trip) {
        final String own = (trip == null) ? null : trip.getPaymentConfig().getMailFrom();
        if (own != null && !own.isBlank()) {
            return own;
        }
        final String inherited = effectivePaymentConfig(trip).getMailFrom();
        return (inherited == null || inherited.isBlank()) ? mailAddr().siteFrom() : inherited;
    }

    /**
     * Writes the dialog's From composer back onto the working payment config: blank mode clears the
     * override (inherit from the org, then the site), "custom" composes and VALIDATES against the owning
     * org's allowed domains. Returns false -- with a growl and the config untouched -- on an invalid
     * entry, so the dialog's Done/Send-Test can abort instead of storing an address SES will refuse.
     */
    public boolean applyPaymentFrom(final Trip trip, final String mode, final String name,
            final String local, final String domain) {
        if (trip == null) {
            return false;
        }
        if (mode == null || mode.isBlank()) {
            trip.getPaymentConfig().setMailFrom(null);
            return publishFromOk(true);
        }
        final String composed = mailAddr()
                .composeAddress(name, local, domain, mailDomainsForTrip(trip), "The From address");
        if (composed == null) {
            return publishFromOk(false);
        }
        trip.getPaymentConfig().setMailFrom(composed);
        return publishFromOk(true);
    }

    /** Mirrors the outcome to the client so the dialog stays OPEN on a bad address (the growl alone,
     *  with the dialog closing, reads as "saved anyway"). Seam-safe: no FacesContext in unit tests. */
    private boolean publishFromOk(final boolean ok) {
        if (FacesContext.getCurrentInstance() != null && PrimeFaces.current().isAjaxRequest()) {
            PrimeFaces.current().ajax().addCallbackParam("payFromOk", ok);
        }
        return ok;
    }

    /**
     * Enabled processor configs a TRIP EDITOR may pick from: the trip's org's, only. Deliberately not
     * org-admin-gated -- trip managers pick from configs, they do not manage them, and rows carry no secret
     * material. An org-less (legacy) trip has no choices.
     */
    public List<PaymentProcessorConfig> getConfigChoicesForTrip(final Trip trip) {
        if (trip == null || trip.getOrgId() == null || trip.getOrgId().isBlank()
                || !caller().isAuthenticated()) {
            return List.of();
        }
        return DAO.getInstance()
                .getPaymentProcessorConfigs(Organization.Id.from(trip.getOrgId()), Cached.YES).stream()
                .filter(PaymentProcessorConfig::isEnabled)
                .sorted(Comparator.comparing(PaymentProcessorConfig::getLabel,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /**
     * The trip-editor dialog's "Send test email": renders the trip's EFFECTIVE confirmation template with
     * sample values and mails it to the given address (the dialog's To field, prefilled with the signed-in
     * admin's own address -- user request 2026-08-24: prompt, never assume). The trip passed in is the edit
     * DRAFT, so unsaved dialog choices are what get tested.
     */
    public boolean sendPaymentTestMail(final Trip trip, final String to) {
        final Caller current = caller();
        final Person me = (current.isAuthenticated())
                ? DAO.getInstance().getPerson(current.personId(), Cached.NO).orElse(null) : null;
        if (me == null) {
            return fail("Not signed in", "Sign in to send a test email.");
        }
        final String addr = normalizeEmail(to);
        if (addr == null) {
            return fail("No address", "Enter the email address to send the test to.");
        }
        final TripPaymentConfig effective = effectivePaymentConfig(trip);
        if (effective.getConfirmationTemplateId() == null) {
            return fail("No template", "Pick a confirmation template (or set the site default).");
        }
        if (effective.getMailFrom() == null) {
            return fail("No From address", "Set a From address on the trip, the organization, or the "
                    + "site settings.");
        }
        final boolean sent = mailSource.get().sendManagedTemplate(effective.getConfirmationTemplateId(),
                samplePaymentValues(trip, me, effective), addr, effective.getMailFrom(),
                effective.getReplyTo(), current.auditActor());
        if (!sent) {
            return fail("Not sent", "The test email could not be sent; is the template installed?");
        }
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO,
                "Test email sent to " + addr, null);
        return true;
    }

    /**
     * The payment dialog's template PREVIEW: subject and body of the trip's effective confirmation mail
     * rendered with the same sample values the test send uses. "" when no template resolves anywhere or
     * the render fails -- a preview must show its empty state, never error. Two EL faces rather than the
     * {@code ManagedMail} record (viewScope/EL should not depend on record-accessor resolution).
     */
    public String previewPaymentMailSubject(final Trip trip) {
        final MailCommands.ManagedMail rendered = renderSamplePaymentMail(trip);
        return (rendered == null) ? "" : rendered.subject();
    }

    /** The rendered body HTML for the preview pane; see {@link #previewPaymentMailSubject}. */
    public String previewPaymentMailBody(final Trip trip) {
        final MailCommands.ManagedMail rendered = renderSamplePaymentMail(trip);
        return (rendered == null) ? "" : rendered.body();
    }

    private MailCommands.ManagedMail renderSamplePaymentMail(final Trip trip) {
        final Caller current = caller();
        final Person me = (current.isAuthenticated())
                ? DAO.getInstance().getPerson(current.personId(), Cached.NO).orElse(null) : null;
        if (trip == null || me == null) {
            return null;
        }
        final TripPaymentConfig effective = effectivePaymentConfig(trip);
        if (effective.getConfirmationTemplateId() == null) {
            return null;
        }
        return mailSource.get().renderManagedTemplate(effective.getConfirmationTemplateId(),
                samplePaymentValues(trip, me, effective));
    }

    /** Sample token values for the test send (the real flow's PaymentMailer fills these from a Payment). */
    private java.util.Map<String, Object> samplePaymentValues(final Trip trip, final Person me,
            final TripPaymentConfig effective) {
        final Organization owner = (trip == null || trip.getOrgId() == null) ? null
                : findOrganization(trip.getOrgId());
        final java.util.Map<String, Object> values = new java.util.HashMap<>();
        effective.getExtraTokens().forEach(values::put);
        values.put("payerName", me.getPreferredName() + " " + me.getLast());
        values.put("tripTitle", (trip == null || trip.getTitle() == null) ? "Sample Trip" : trip.getTitle());
        values.put("totalPaid", "$2,500.00 (SAMPLE)");
        values.put("feeNote", "Includes a $75.00 processor fee for the trip portion (sample).");
        values.put("donationAmount", "$1,000.00");
        values.put("donationNote", "Thank you for your donation of $1,000.00! (sample)");
        values.put("captureId", "TEST-000000");
        values.put("processorName", "Test Processor");
        values.put("paymentDate", java.time.LocalDate.now().toString());
        values.put("orgName", (owner == null) ? "the organization" : owner.getName());
        values.put("amountsBlock", new org.paulsens.trip.chat.MailTemplates.Raw(
                "<ul><li>" + org.paulsens.trip.chat.MailTemplates.escape(
                        me.getPreferredName() + " " + me.getLast()) + ": $475.00 (sample)</li></ul>"));
        return values;
    }

    /** Extra-token map &rarr; the dialog textarea's one-per-line {@code token=value} text. */
    public String tokensToText(final java.util.Map<String, String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        return tokens.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** The reverse: parses the textarea back onto the draft's map (lines without '=' are ignored). */
    public void applyTokensText(final TripPaymentConfig config, final String text) {
        if (config == null) {
            return;
        }
        config.getExtraTokens().clear();
        if (text == null || text.isBlank()) {
            return;
        }
        for (final String line : text.split("\\R")) {
            final int eq = line.indexOf('=');
            if (eq > 0) {
                config.getExtraTokens().put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
    }

    /** The site-settings rung of the ladder, built from {@link KnownSettings}. */
    private TripPaymentConfig siteDefaults() {
        final ConfigCommands config = new ConfigCommands();
        final String feesRaw = config.getString(KnownSettings.PAYMENT_FEES_PAID_BY);
        return TripPaymentConfig.builder()
                .feesPaidBy("PAYER".equalsIgnoreCase(feesRaw) ? FeesPaidBy.PAYER : FeesPaidBy.ORGANIZATION)
                .donationEnabled(Boolean.FALSE)
                .confirmationTemplateId(config.getString(KnownSettings.PAYMENT_CONFIRM_TEMPLATE))
                .mailFrom(config.getString(KnownSettings.PAYMENT_MAIL_FROM))
                .build();
    }

    // ------------------------------------------------------------------ per-org settings ladder
    //
    // The payment-config ladder above, generalized: any setting KnownSettings marks org-overridable can be
    // set per organization, and ConfigCommands resolves org override -> site row -> compiled default on the
    // org's own host automatically. This region is the org rung's editor surface and its explicit-org
    // resolution for code that has no request (docs/org-admin.md, "Per-org settings").

    /** The settings an organization may override, in page order -- the org settings editor's rows. */
    public List<org.paulsens.trip.model.SettingDef> orgSettingDefs() {
        return KnownSettings.orgOverridable();
    }

    /**
     * The editor's map: setting name &rarr; the org's stored override, "" when it inherits, for every
     * org-overridable setting. Blank means inherit -- the Settings page's own convention, and what makes
     * "clear the box" the way to give a value back to the site.
     */
    public java.util.Map<String, String> orgSettingsEdit(final String orgId) {
        final Organization org = findOrganization(orgId);
        final java.util.Map<String, String> edit = new java.util.LinkedHashMap<>();
        for (final org.paulsens.trip.model.SettingDef def : orgSettingDefs()) {
            final String value = (org == null) ? null : org.settingOverride(def.getName());
            edit.put(def.getName(), value == null ? "" : value);
        }
        return edit;
    }

    /**
     * What the organization gets when it leaves a setting blank -- the editor's placeholder: the SITE's
     * value (stored or default), or for an org-only setting the compiled default, since an org host never
     * inherits the site's row for those. Read through {@code siteString}, so the answer is the same whether
     * the admin is browsing the shared host or the org's own.
     */
    public String inheritedSetting(final org.paulsens.trip.model.SettingDef def) {
        return def.isOrgOnly() ? def.getDefaultValue() : new ConfigCommands().siteString(def);
    }

    /**
     * A setting as it applies to ONE organization, named explicitly -- for background code (digest and
     * notification senders, schedulers) that runs with no request bound and must derive the org from the
     * entity in hand rather than from a site context. Pages and request-bound beans need nothing: the
     * {@code SettingDef} overloads on {@code ConfigCommands} already resolve the ladder on an org host.
     */
    public String effectiveSetting(final org.paulsens.trip.model.SettingDef def, final String orgId) {
        return new ConfigCommands().getString(def, findOrganization(orgId));
    }

    /**
     * Saves an organization's setting overrides from the editor's map ({@link #orgSettingsEdit}): each entry
     * is a setting name and its new override, blank meaning inherit. Refuses (growl, nothing written) a key
     * that is not org-overridable -- the map is posted from a browser, so the server decides what an org may
     * touch -- and a value that does not parse as the setting's declared type. Applied onto a FRESH read
     * and written only when something actually changed; site admins and the org's own admins may save.
     */
    public boolean saveOrgSettings(final String orgId, final java.util.Map<String, String> values) {
        final Caller current = caller();
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can change its settings.");
        }
        final Organization fresh = freshOrg(orgId);
        if (fresh == null) {
            return fail("Unable to save", "Unknown organization.");
        }
        if (values == null) {
            return true;
        }
        final List<String> changed = new ArrayList<>();
        for (final java.util.Map.Entry<String, String> entry : values.entrySet()) {
            final String change = applyOverride(fresh, entry.getKey(), entry.getValue());
            if (change == null) {
                return false;
            }
            if (!change.isEmpty()) {
                changed.add(change);
            }
        }
        if (changed.isEmpty()) {
            return true;
        }
        if (!saveOrgOrWarn(fresh)) {
            return false;
        }
        audit(current, fresh, "Settings changed: " + String.join("; ", changed));
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, "Settings saved.", null);
        return true;
    }

    /**
     * One override onto the org being saved: "" when nothing changed, a description of the change when it
     * did, or null (growled) when the key or value is refused.
     */
    private String applyOverride(final Organization org, final String key, final String rawValue) {
        final org.paulsens.trip.model.SettingDef def = KnownSettings.findOrgOverridable(key).orElse(null);
        if (def == null) {
            return failText("Not saved", "'" + key + "' is not a setting an organization can override.");
        }
        final String value = (rawValue == null) ? "" : rawValue.trim();
        // The same judge as the site Settings page: type, then the declaration's choices / URL rule.
        final String rejection = new ConfigCommands().rejection(new org.paulsens.trip.model.Config(
                def.getName(), value, def.getType(), null, null, null));
        if (rejection != null) {
            return failText("Not saved", rejection);
        }
        final java.util.Map<String, String> overrides = org.getSettingsOverrides();
        final String before = overrides.get(def.getName());
        if (value.isEmpty()) {
            return (overrides.remove(def.getName()) == null) ? "" : def.getName() + " = (inherit)";
        }
        if (value.equals(before)) {
            return "";
        }
        overrides.put(def.getName(), value);
        return def.getName() + " = " + value;
    }

    private String failText(final String summary, final String detail) {
        fail(summary, detail);
        return null;
    }

    private boolean saveProcessorOrWarn(final PaymentProcessorConfig config) {
        try {
            return DAO.getInstance().savePaymentProcessorConfig(config);
        } catch (final ConditionalCheckFailedException ex) {
            return fail("Config changed", "Someone else changed this processor configuration at the same "
                    + "time. Please reload the page and try again.");
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to save processor config {}", config.getId(), ex);
            return fail("Unable to save", "Unable to save the configuration: " + ex.getMessage());
        }
    }

    private static ProcessorType parseType(final String typeName) {
        try {
            return (typeName == null || typeName.isBlank()) ? null
                    : ProcessorType.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Caller caller() {
        return callerSource.get();
    }

    private boolean saveOrgOrWarn(final Organization org) {
        try {
            return DAO.getInstance().saveOrganization(org);
        } catch (final ConditionalCheckFailedException ex) {
            return fail("Organization changed", "Someone else changed this organization at the same time. "
                    + "Please reload the page and try again.");
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to save organization {}", org.getId(), ex);
            return fail("Unable to save", "Unable to save the organization: " + ex.getMessage());
        }
    }

    private void savePersonOrWarn(final Person person) {
        if (!PersonCommands.getPersonCommands().savePerson(person)) {
            // The membership row (source of truth) is already written; resync heals the derived list later.
            log.warn("Org membership saved but Person.orgIds sync failed for {}", person.getId());
        }
    }

    private void audit(final Caller current, final Organization org, final String message) {
        Audit.builder(AuditAction.ORGANIZATION, AuditOutcome.SUCCESS)
                .actor(current.auditActor())
                .target(AuditEventBuilder.TARGET_ORGANIZATION, org.getId().getValue())
                .message(message)
                .log();
    }

    private static List<Organization> matching(final List<Organization> orgs, final String query) {
        final String needle = (query == null) ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return orgs;
        }
        return orgs.stream()
                .filter(org -> containsIgnoreCase(org.getName(), needle)
                        || containsIgnoreCase(org.getAbbreviation(), needle))
                .toList();
    }

    private static boolean containsIgnoreCase(final String haystack, final String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static boolean equalsIgnoreCaseSafe(final String a, final String b) {
        return (a == null) ? (b == null) : a.equalsIgnoreCase(b);
    }

    private static String describe(final Person person) {
        return person.getPreferredName() + " " + person.getLast() + " (" + person.getId().getValue() + ")";
    }

    private boolean fail(final String summary, final String detail) {
        // Growl detail is never rendered site-wide; the summary must carry the message on its own.
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary + ": " + detail, detail);
        return false;
    }

    private Organization failOrg(final String summary, final String detail) {
        fail(summary, detail);
        return null;
    }
}
