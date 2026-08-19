package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
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
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.OrgMember;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPaymentConfig;
import org.paulsens.trip.pay.ProcessorPing;
import org.paulsens.trip.security.ProcessorSecrets;
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

    public OrgCommands() {
        this(Caller::current);
    }

    /** Test seam (the {@link FamilyCommands} pattern): {@code Caller.current()} needs a FacesContext. */
    public OrgCommands(final Supplier<Caller> callerSource) {
        this(callerSource, () -> org.paulsens.trip.api.Beans.get(MailCommands.class));
    }

    /** Full test seam: mail too ({@code Beans.get} needs a CDI container unit tests do not have). */
    public OrgCommands(final Supplier<Caller> callerSource, final Supplier<MailCommands> mailSource) {
        this.callerSource = callerSource;
        this.mailSource = mailSource;
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
        if (orgId == null || orgId.isBlank()) {
            return fail("Unable to save", "Unknown organization.");
        }
        final Organization fresh = DAO.getInstance()
                .getOrganization(Organization.Id.from(orgId.trim()), Cached.NO).orElse(null);
        if (fresh == null) {
            return fail("Unable to save", "Unknown organization.");
        }
        fresh.setName(name);
        fresh.setAbbreviation(abbreviation);
        fresh.setContactEmail(contactEmail);
        return saveOrganization(fresh);
    }

    /**
     * Adds a person to an organization (idempotent). Membership row first, then the derived
     * {@code Person.orgIds} delta. Returns false (with a growl) on refusal or failure.
     */
    public boolean addMember(final String orgId, final Person.Id personId) {
        final Caller current = caller();
        final Organization org = findOrganization(orgId);
        if (org == null || personId == null) {
            return fail("Unable to add", "Unknown organization or person.");
        }
        if (!canManageOrg(orgId)) {
            return fail("Not allowed", "Only this organization's admins can add members.");
        }
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
        audit(current, org, "Added " + describe(person) + " to organization '" + org.getName() + "'");
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
        audit(current, org, "Removed " + (person == null ? personId.getValue() : describe(person))
                + " from organization '" + org.getName() + "'");
        return true;
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
     * sample values and mails it to the signed-in admin -- the one thing that must work before a real payer
     * hits it. The trip passed in is the edit DRAFT, so unsaved dialog choices are what get tested.
     */
    public boolean sendPaymentTestMail(final Trip trip) {
        final Caller current = caller();
        final Person me = (current.isAuthenticated())
                ? DAO.getInstance().getPerson(current.personId(), Cached.NO).orElse(null) : null;
        if (me == null || me.getEmail() == null || me.getEmail().isBlank()) {
            return fail("No address", "Your account has no email address to send the test to.");
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
                samplePaymentValues(trip, me, effective), me.getEmail(), effective.getMailFrom(),
                effective.getReplyTo(), current.auditActor());
        if (!sent) {
            return fail("Not sent", "The test email could not be sent; is the template installed?");
        }
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO,
                "Test email sent to " + me.getEmail(), null);
        return true;
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
