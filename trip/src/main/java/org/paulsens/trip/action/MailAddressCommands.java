package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.EmailAddresses;

/**
 * Resolves every configurable mail address the application sends WITH (From) or TO (recipient/Reply-To).
 * Each slot is one {@link KnownSettings} entry whose value is either the sentinel {@code site} (the Site
 * email setting), the sentinel {@code org} (the owning organization's contact email from its org Profile
 * page, falling back to the Site email -- only slots with a trip in hand), the sentinel
 * {@code facilitators} (the trip's facilitators' emails -- the same contacts the registrant is shown --
 * falling back like {@code org}; same trip-in-hand slots), or a literal address. The
 * Settings page's Email addresses section edits these through {@link #editState()}/{@link #applyEdits},
 * which render as a mode menu plus a local-part/domain composer instead of raw text.
 *
 * <p>SES only enforces verification on the FROM identity, so From slots are composed against
 * {@link MailCommands#verifiedSendingDomains()} while recipient and Reply-To slots accept any well-formed
 * address (an organization's contact email is often external). From slots never honor {@code org} for the
 * same reason: an unverified org domain as From would fail every send.
 */
@Slf4j
@Named("mailAddr")
@ApplicationScoped
public class MailAddressCommands {
    private static final String ORG = "org";
    private static final String SITE = "site";
    private static final String FACILITATORS = "facilitators";
    /** Conservative local-part shape; covers every address this site actually uses. */
    private static final Pattern LOCAL_PART = Pattern.compile("[A-Za-z0-9._%+-]+");

    /** One configurable address slot: which setting, how it is used, and whether org mode applies. */
    @Value
    public static class Slot {
        SettingDef def;
        /** Display grouping on the Settings page; consecutive slots with the same group share a heading. */
        String group;
        /** "From", "To", or "Reply-To" -- the role this address plays in its emails. */
        String label;
        /** From slots compose display-name + local-part + verified domain; others take any address. */
        boolean from;
        boolean orgAllowed;

        public String getKey() {
            return def.getName();
        }
    }

    private static final List<Slot> SLOTS = List.of(
            new Slot(KnownSettings.REG_MAIL_FROM, "Registration email (to the registrant)", "From",
                    true, false),
            new Slot(KnownSettings.REG_MAIL_REPLY_TO, "Registration email (to the registrant)", "Reply-To",
                    false, true),
            new Slot(KnownSettings.REG_NOTIFY_EMAIL, "Registration notices (internal)", "To", false, true),
            new Slot(KnownSettings.REG_NOTIFY_FROM, "Registration notices (internal)", "From", true, false),
            new Slot(KnownSettings.ACCOUNT_NOTIFY_EMAIL, "New-account notices (internal)", "To",
                    false, false),
            new Slot(KnownSettings.ACCOUNT_NOTIFY_FROM, "New-account notices (internal)", "From",
                    true, false),
            new Slot(KnownSettings.TX_NOTIFY_EMAIL, "Transaction notices (internal)", "To", false, true),
            new Slot(KnownSettings.TX_NOTIFY_FROM, "Transaction notices (internal)", "From", true, false),
            new Slot(KnownSettings.LOGIN_MAIL_FROM, "Login codes", "From", true, false),
            new Slot(KnownSettings.LOGIN_MAIL_REPLY_TO, "Login codes", "Reply-To", false, false),
            new Slot(KnownSettings.CHAT_MAIL_FROM, "Chat email", "From", true, false),
            new Slot(KnownSettings.CHAT_MAIL_REPLY_TO, "Chat email", "Reply-To", false, true),
            new Slot(KnownSettings.SUPPORT_MAIL_FROM, "Support requests", "From", true, false),
            new Slot(KnownSettings.SUPPORT_MAIL_REPLY_TO, "Support requests", "Reply-To", false, false));

    private final Supplier<ConfigCommands> configSource;
    private final Supplier<List<String>> domainsSource;

    public MailAddressCommands() {
        // Lazy on purpose: resolution paths never touch SES; only the Settings page and applyEdits do.
        this(ConfigCommands::new,
                () -> org.paulsens.trip.api.Beans.get(MailCommands.class).verifiedSendingDomains());
    }

    /**
     * For senders that already hold a {@link ConfigCommands} (often a test's mock): resolution reads
     * settings through IT, so a mocked config keeps steering the addresses the way it did before this
     * class existed.
     */
    public MailAddressCommands(final ConfigCommands config) {
        this(() -> config,
                () -> org.paulsens.trip.api.Beans.get(MailCommands.class).verifiedSendingDomains());
    }

    /** Test seam: verified-domain lookup needs SES (or a CDI container) tests do not have. */
    MailAddressCommands(final Supplier<List<String>> domainsSource) {
        this(ConfigCommands::new, domainsSource);
    }

    MailAddressCommands(final Supplier<ConfigCommands> configSource,
            final Supplier<List<String>> domainsSource) {
        this.configSource = configSource;
        this.domainsSource = domainsSource;
    }

    // ------------------------------------------------------------------ resolution (senders call these)

    /** The From value for this slot: the Site email for {@code site} (or a stray {@code org}), else literal. */
    public String from(final SettingDef def) {
        final String value = raw(def);
        if (SITE.equalsIgnoreCase(value) || ORG.equalsIgnoreCase(value)) {
            return siteFrom();
        }
        return value;
    }

    /**
     * The recipient/Reply-To value for this slot. {@code facilitators} resolves to the trip's
     * facilitators' emails (comma-joined), falling back to {@code org}; {@code org} resolves to the
     * owning organization's contact email when {@code trip} has one, else the Site email (bare);
     * {@code site} is the Site email (bare); anything else is literal. {@code trip} may be null (no org
     * context) -- the trip-dependent sentinels then fall through their fallback chain to the Site email.
     */
    public String recipient(final SettingDef def, final Trip trip) {
        final String value = raw(def);
        if (FACILITATORS.equalsIgnoreCase(value)) {
            final String facEmails = facilitatorEmails(trip);
            if (facEmails != null) {
                return facEmails;
            }
        }
        return resolveRecipient(value, ownerOf(trip));
    }

    /** {@link #recipient} under its Reply-To name; the semantics are identical. */
    public String replyTo(final SettingDef def, final Trip trip) {
        return recipient(def, trip);
    }

    /**
     * {@link #recipient(SettingDef, Trip)} with the ORGANIZATION in hand and no trip -- an org invite, an
     * org-level notice. {@code org} resolves to that organization's contact email (Site email fallback);
     * {@code facilitators}, a trip-only notion, resolves the same way. Distinct name rather than an
     * overload: {@code recipient(def, null)} callers exist, and EL picks overloads by runtime type.
     */
    public String orgRecipient(final SettingDef def, final Organization org) {
        return resolveRecipient(raw(def), org);
    }

    /** {@link #orgRecipient} under its Reply-To name; the semantics are identical. */
    public String orgReplyTo(final SettingDef def, final Organization org) {
        return orgRecipient(def, org);
    }

    /** The sentinel rules shared by the trip and organization forms, once the facilitators case is settled. */
    private String resolveRecipient(final String value, final Organization org) {
        if (ORG.equalsIgnoreCase(value) || FACILITATORS.equalsIgnoreCase(value)) {
            final String orgEmail = contactEmailOf(org);
            return orgEmail != null ? orgEmail : siteBare();
        }
        if (SITE.equalsIgnoreCase(value)) {
            return siteBare();
        }
        return value;
    }

    // EL entry points -- distinct names (never overloads: EL picks overloads by runtime argument type).

    public String fromFor(final String key) {
        return from(requireDef(key));
    }

    public String recipientFor(final String key, final Trip trip) {
        return recipient(requireDef(key), trip);
    }

    public String replyToFor(final String key, final Trip trip) {
        return recipient(requireDef(key), trip);
    }

    /** The full Site email ("Name &lt;addr&gt;"), as configured or defaulted. */
    public String siteFrom() {
        return raw(KnownSettings.SITE_MAIL_EMAIL);
    }

    /** The bare Site email address. */
    public String siteBare() {
        return addressOf(siteFrom());
    }

    /**
     * The trip's facilitators' emails, comma-joined for {@link MailCommands#send}'s recipient split, or
     * null when the trip is null, has no facilitators, or none of them has a usable address (a
     * facilitator may be a person-modeled staff entry with no login/email).
     */
    public String facilitatorEmails(final Trip trip) {
        final List<Person.Id> ids = (trip == null) ? null : trip.getFacilitatorIds();
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        final String joined = ids.stream()
                .map(this::facilitatorEmail)
                .filter(EmailAddresses::isValid)
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private String facilitatorEmail(final Person.Id id) {
        return DAO.getInstance().getPerson(id, Cached.YES).map(Person::getEmail).orElse(null);
    }

    /** The owning org's contact email, or null when the trip is org-less or the address is unusable. */
    public String orgContactEmail(final Trip trip) {
        return contactEmailOf(ownerOf(trip));
    }

    /** An organization's contact email, or null when there is no org or the address is unusable. */
    public String contactEmailOf(final Organization org) {
        final String email = (org == null) ? null : org.getContactEmail();
        return EmailAddresses.isValid(email) ? email : null;
    }

    /** The trip's owning organization (cached read), or null for a null or org-less trip. */
    private static Organization ownerOf(final Trip trip) {
        final String orgId = (trip == null) ? null : trip.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getOrganization(Organization.Id.from(orgId.trim()), Cached.YES).orElse(null);
    }

    // ------------------------------------------------------------------ the Settings page

    public List<Slot> getSlots() {
        return SLOTS;
    }

    /** The SES-verified sending domains for the From composer; empty (with a log) when SES is unreachable. */
    public List<String> getSendingDomains() {
        try {
            return domainsSource.get();
        } catch (final RuntimeException ex) {
            log.error("Unable to list verified SES domains", ex);
            return List.of();
        }
    }

    /**
     * The mode menu's items for a slot, as {@code label}/{@code value} maps ({@code f:selectItem} has no
     * {@code rendered}, so the org option's presence is decided here). The Default item names what the
     * default resolves to, so "unset" is never a mystery.
     */
    public List<Map<String, String>> modeOptions(final Slot slot) {
        final List<Map<String, String>> options = new java.util.ArrayList<>();
        options.add(Map.of("label", "Default (" + defaultLabel(slot) + ")", "value", ""));
        if (slot.isOrgAllowed()) {
            options.add(Map.of("label",
                    "Trip facilitators (falls back to Organization email, then Site email)",
                    "value", FACILITATORS));
            options.add(Map.of("label", "Organization email (falls back to Site email)", "value", ORG));
        }
        options.add(Map.of("label", "Site email", "value", SITE));
        options.add(Map.of("label", "Custom", "value", "custom"));
        return options;
    }

    private static String defaultLabel(final Slot slot) {
        final String value = slot.getDef().getDefaultValue();
        if (FACILITATORS.equalsIgnoreCase(value)) {
            return "Trip facilitators";
        }
        if (ORG.equalsIgnoreCase(value)) {
            return "Organization email";
        }
        return SITE.equalsIgnoreCase(value) ? "Site email" : value;
    }

    /**
     * The flat edit model the section's inputs bind to: {@code site.name/.local/.domain} for the Site
     * email, and per slot {@code <key>.mode} ("" = default, else org/site/custom) plus the custom
     * subfields ({@code .name/.local/.domain} for From slots, {@code .addr} for the rest). Custom
     * subfields are pre-seeded -- from the stored custom value when that is the mode, else with the
     * house-style starting point (site display name, local part "no-reply") -- so switching a slot to
     * Custom never starts from an empty form.
     */
    public Map<String, String> editState() {
        final Map<String, String> stored = configSource.get().getKnownValues();
        final Map<String, String> edit = new HashMap<>();
        final String site = effective(stored, KnownSettings.SITE_MAIL_EMAIL);
        edit.put("site.name", displayNameOf(site));
        edit.put("site.local", localOf(site));
        edit.put("site.domain", domainOf(site));
        for (final Slot slot : SLOTS) {
            final String value = stored.getOrDefault(slot.getKey(), "").trim();
            edit.put(slot.getKey() + ".mode", modeOf(value));
            final String custom = modeOf(value).equals("custom") ? value : "";
            if (slot.isFrom()) {
                edit.put(slot.getKey() + ".name",
                        custom.isEmpty() ? displayNameOf(site) : displayNameOf(custom));
                edit.put(slot.getKey() + ".local", custom.isEmpty() ? "no-reply" : localOf(custom));
                edit.put(slot.getKey() + ".domain", custom.isEmpty() ? domainOf(site) : domainOf(custom));
            } else {
                edit.put(slot.getKey() + ".addr", custom.isEmpty() ? "" : addressOf(custom));
            }
        }
        return edit;
    }

    /**
     * Validates and composes the section's edits INTO {@code vals} (the page's known-values map), so the
     * page's one Save button persists everything through {@code ConfigCommands.saveKnown}. Returns false
     * -- with a growl naming the offending slot, and without touching {@code vals} -- on the first
     * invalid entry, so a bad address never half-saves. A composed value equal to the setting's default
     * collapses to "" (unset), preserving the page's blank-means-default convention.
     */
    public boolean applyEdits(final Map<String, String> edit, final Map<String, String> vals) {
        if (edit == null || vals == null) {
            return true;
        }
        final List<String> domains = getSendingDomains();
        final Map<String, String> staged = new HashMap<>();
        final String site = composeFrom(edit, "site", "Site email", domains);
        if (site == null) {
            return false;
        }
        staged.put(KnownSettings.SITE_MAIL_EMAIL.getName(),
                collapseDefault(KnownSettings.SITE_MAIL_EMAIL, site));
        for (final Slot slot : SLOTS) {
            final String value = composedSlotValue(slot, edit, domains);
            if (value == null) {
                return false;
            }
            staged.put(slot.getKey(), collapseDefault(slot.getDef(), value));
        }
        vals.putAll(staged);
        return true;
    }

    /** One slot's stored value from its edit fields, or null (growled) when invalid. */
    private String composedSlotValue(final Slot slot, final Map<String, String> edit,
            final List<String> domains) {
        final String mode = get(edit, slot.getKey() + ".mode");
        final String where = slot.getGroup() + " " + slot.getLabel();
        if (mode.isEmpty()) {
            return "";
        }
        if (FACILITATORS.equals(mode)) {
            // Facilitators need a trip in hand, which is exactly the org-allowed slots.
            return slot.isOrgAllowed() ? FACILITATORS : fail(where, "has no facilitators option.");
        }
        if (ORG.equals(mode)) {
            return slot.isOrgAllowed() ? ORG : fail(where, "has no organization option.");
        }
        if (SITE.equals(mode)) {
            return SITE;
        }
        if (slot.isFrom()) {
            return composeFrom(edit, slot.getKey(), where, domains);
        }
        final String addr = get(edit, slot.getKey() + ".addr");
        if (!EmailAddresses.isValid(addr)) {
            return fail(where, "needs a valid email address (got '" + addr + "').");
        }
        return addr;
    }

    /** "Name &lt;local@domain&gt;" from the trio of edit fields, or null (growled) when invalid. */
    private String composeFrom(final Map<String, String> edit, final String prefix, final String where,
            final List<String> domains) {
        return composeAddress(get(edit, prefix + ".name"), get(edit, prefix + ".local"),
                get(edit, prefix + ".domain"), domains, where);
    }

    // ------------------------------------------------------------------ the shared From composer
    //
    // Every page that edits a From address uses the same three inputs (display name, local part, and a
    // dropdown of allowed domains) rendered by /WEB-INF/mailFromComposer.xhtml. These are its seed and
    // validate halves: a From box that lets an admin type an unverified domain only produces a send that
    // SES silently refuses later, which is the confusion this replaces.

    /**
     * "Name &lt;local@domain&gt;" from a composer's three fields, or null (with a growl naming
     * {@code where}) when the local part is malformed, the domain is not in {@code allowed}, or the
     * display name carries angle brackets. {@code allowed} is the caller's domain list -- the site's
     * verified domains for site-wide slots, an organization's narrower allow-list for its own pages.
     */
    public String composeAddress(final String name, final String local, final String domain,
            final List<String> allowed, final String where) {
        final String cleanName = (name == null) ? "" : name.trim();
        final String cleanLocal = (local == null) ? "" : local.trim();
        final String cleanDomain = (domain == null) ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (!LOCAL_PART.matcher(cleanLocal).matches()) {
            return fail(where, "needs the part before the @ (letters, digits, . _ % + -).");
        }
        if (allowed == null || !allowed.contains(cleanDomain)) {
            return fail(where, (allowed == null || allowed.isEmpty())
                    ? "cannot be saved: no verified sending domain is available (check the SES verified "
                            + "domains, and the organization's allowed domains when this is an org page)."
                    : "needs one of the verified sending domains (" + String.join(", ", allowed) + ").");
        }
        if (cleanName.indexOf('<') >= 0 || cleanName.indexOf('>') >= 0) {
            return fail(where, "display name cannot contain '<' or '>'.");
        }
        final String address = cleanLocal + "@" + cleanDomain;
        return cleanName.isEmpty() ? address : cleanName + " <" + address + ">";
    }

    /** True when {@code value} is a usable From for {@code allowed} -- the seeders' "keep it" test. */
    public boolean isSendable(final String value, final List<String> allowed) {
        final String domain = domainOf(value);
        return !domain.isEmpty() && allowed != null && allowed.contains(domain)
                && LOCAL_PART.matcher(localOf(value)).matches();
    }

    /** A composer's display-name seed: the value's own name, else the Site email's. */
    public String composerName(final String value) {
        final String name = displayNameOf(value);
        return name.isEmpty() ? displayNameOf(siteFrom()) : name;
    }

    /** A composer's local-part seed: the value's own, else the house-style "no-reply". */
    public String composerLocal(final String value) {
        final String local = localOf(value);
        return local.isEmpty() ? "no-reply" : local;
    }

    /**
     * A composer's domain seed: the value's own domain when {@code allowed} still permits it, else
     * {@code preferred} (the org's default domain) when allowed, else the first allowed domain. Never
     * seeds a domain the dropdown does not offer -- a preselected-but-absent item silently posts back as
     * the first option, which is how a From address changes without anyone touching it.
     */
    public String composerDomain(final String value, final String preferred, final List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return "";
        }
        final String own = domainOf(value);
        if (allowed.contains(own)) {
            return own;
        }
        final String want = (preferred == null) ? "" : preferred.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(want) ? want : allowed.get(0);
    }

    private static String fail(final String where, final String problem) {
        // Growl detail is never rendered site-wide; the summary must carry the message on its own.
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                where + " " + problem + " Nothing was saved.", null);
        return null;
    }

    // ------------------------------------------------------------------ value plumbing

    /** The stored-or-default value for a def, trimmed. */
    private String raw(final SettingDef def) {
        final String value = configSource.get().getString(def);
        return value == null ? "" : value.trim();
    }

    private static String effective(final Map<String, String> stored, final SettingDef def) {
        final String value = stored.getOrDefault(def.getName(), "").trim();
        return value.isEmpty() ? def.getDefaultValue() : value;
    }

    private static String modeOf(final String value) {
        if (value.isEmpty()) {
            return "";
        }
        if (FACILITATORS.equalsIgnoreCase(value)) {
            return FACILITATORS;
        }
        if (ORG.equalsIgnoreCase(value)) {
            return ORG;
        }
        return SITE.equalsIgnoreCase(value) ? SITE : "custom";
    }

    private static String collapseDefault(final SettingDef def, final String value) {
        return value.equals(def.getDefaultValue()) ? "" : value;
    }

    private static String get(final Map<String, String> edit, final String key) {
        final String value = edit.get(key);
        return value == null ? "" : value.trim();
    }

    private static SettingDef requireDef(final String key) {
        return KnownSettings.find(key)
                .orElseThrow(() -> new IllegalArgumentException("Unknown mail-address setting: " + key));
    }

    /** The bare address inside "Name &lt;addr&gt;", or the value itself when there is no bracket pair. */
    public static String addressOf(final String value) {
        if (value == null) {
            return "";
        }
        final int open = value.lastIndexOf('<');
        final int close = value.lastIndexOf('>');
        if (open >= 0 && close > open) {
            return value.substring(open + 1, close).trim();
        }
        return value.trim();
    }

    /** The display name outside "Name &lt;addr&gt;", or "" for a bare address. */
    public static String displayNameOf(final String value) {
        if (value == null) {
            return "";
        }
        final int open = value.lastIndexOf('<');
        return open > 0 ? value.substring(0, open).trim() : "";
    }

    public static String localOf(final String value) {
        final String address = addressOf(value);
        final int at = address.indexOf('@');
        return at > 0 ? address.substring(0, at) : address;
    }

    public static String domainOf(final String value) {
        final String address = addressOf(value);
        final int at = address.indexOf('@');
        return at >= 0 ? address.substring(at + 1).toLowerCase(Locale.ROOT) : "";
    }
}
