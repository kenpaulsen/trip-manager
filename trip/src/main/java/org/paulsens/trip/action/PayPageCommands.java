package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.site.SiteContext;

/**
 * The org-aware "How to pay" page ({@code #{payPage}}, {@code trip/pay.xhtml}; docs/org-admin.md, "The pay page").
 * Somebody who owes money pays ONE organization, so the page is always some organization's: its editable content
 * lives under {@code page:org:{orgId}:pay} and is edited like any other content page (the existing content rule,
 * {@code ContentCommands.canEditPage}: site admin, global {@code contentAdmin}, {@code contentAdmin} of that
 * organization, which the key itself names). Nothing here grants or checks an edit.
 *
 * <p><b>One decision, asked by both pages.</b> A trip is paid either in the app ({@code trip/payment.xhtml}) or as
 * its organization's page says. {@link #destination} is the ONLY place that decides, and both pages redirect on
 * it: the payment page leaves when it answers {@link Destination#HOW_TO}, the pay page leaves when it answers
 * {@link Destination#IN_APP}. Two spelled-out tests would sooner or later disagree and bounce a traveler between
 * the two pages forever.
 *
 * <p>Everything runs on the render path of a signed-out visitor: cached reads only, no session, no view scope. The
 * one write is the starter text of an organization's page, ONCE per organization ({@link #ensurePage}). The
 * platform's own organization has no pay page: nobody owes it for a trip.
 */
@Slf4j
@Named("payPage")
@ApplicationScoped
public class PayPageCommands {

    /** Where somebody pays for a trip. */
    public enum Destination {
        /** The trip takes payments in the app: {@code trip/payment.xhtml}. */
        IN_APP,
        /** It does not (or there is no such trip here): the organization's "How to pay" page. */
        HOW_TO
    }

    static final String PAYMENT_URL = "/trip/payment.jsf?trip=";
    static final String HOW_TO_URL = "/trip/pay.jsf";
    static final String BALANCE_URL = "/trip/transactions.jsf";

    /**
     * How long a seed that could not be recorded is left alone before the next render tries again. The page is
     * rendered for signed-out visitors, so without this every hit on an organization whose seed cannot succeed
     * yet (its starter template is not installed, its row will not save) cost a strongly consistent read and a
     * log line, with no limit.
     */
    static final Duration SEED_RETRY_AFTER = Duration.ofMinutes(5);

    private final OrgCommands orgs;
    private final TripCommands trips;
    /** When each organization's seed may next be tried, for those whose last try recorded nothing. */
    private final Map<String, Instant> seedRetryAt = new ConcurrentHashMap<>();

    public PayPageCommands() {
        this(Caller::current);
    }

    /** Test seam: {@code Caller.current()} needs a FacesContext. */
    public PayPageCommands(final Supplier<Caller> callerSource) {
        this.orgs = new OrgCommands(callerSource);
        this.trips = new TripCommands(() -> orgs, callerSource);
    }

    // ------------------------------------------------------------------ the decision

    /**
     * Where this trip is paid: {@link Destination#IN_APP} when the trip, as THIS site serves it, resolves to a
     * payment configuration that can take a payment ({@code OrgCommands.effectivePaymentConfig(trip).payable},
     * the ladder trip, organization, site); {@link Destination#HOW_TO} for every other trip, an unknown one, one
     * this site does not serve, and no trip at all.
     */
    public Destination destination(final String tripId) {
        final Trip trip = knownTrip(tripId);
        return trip != null && orgs.effectivePaymentConfig(trip).isPayable() ? Destination.IN_APP
                : Destination.HOW_TO;
    }

    /** {@link #destination} as the boolean a page script branches on: whether the trip is paid in the app. */
    public boolean inApp(final String tripId) {
        return destination(tripId) == Destination.IN_APP;
    }

    /** The address {@link #destination} names for this trip: the payment page, or the How to pay page. */
    public String urlFor(final String tripId) {
        return inApp(tripId) ? PAYMENT_URL + tripId.trim() : howToUrl(tripId);
    }

    /**
     * The How to pay page for a trip; the trip rides along so the page can tell whose instructions to show. A
     * blank id's parameter is left out, never sent empty.
     */
    public String howToUrl(final String tripId) {
        return isBlank(tripId) ? HOW_TO_URL : HOW_TO_URL + "?trip=" + tripId.trim();
    }

    /** The How to pay page of one organization (the Balance page's "How to pay {org}" link). */
    public String howToUrlForOrg(final String orgId) {
        return isBlank(orgId) ? HOW_TO_URL : HOW_TO_URL + "?org=" + orgId.trim();
    }

    /**
     * Where the payment page's "see your balance" links lead: the Balance page's statement of the TRIP'S
     * organization, for the payer themself, or for their whole family when they manage one. Never the Balance
     * page's {@code ?trip=} form: that one is about the signed-in person ON that trip and sends anyone who is
     * not on its roster home, which is where a family manager landed right after paying for a member who alone
     * is on the trip. A trip this site does not serve (or one with no organization) answers the plain page.
     *
     * @param familyView whether the payer manages a family, whose statement then shows whoever was paid for
     */
    public String balanceUrl(final String tripId, final boolean familyView) {
        final Trip trip = knownTrip(tripId);
        if (trip == null || isBlank(trip.getOrgId())) {
            return BALANCE_URL;
        }
        return BALANCE_URL + "?" + (familyView ? "scope=family&" : "") + "org=" + trip.getOrgId().trim();
    }

    // ------------------------------------------------------------------ whose page

    /**
     * The organization whose How to pay page the request is for, or null when there is none to show: the
     * organization of {@code tripId} (the page's {@code ?trip=}) when this site serves that trip; else the one
     * {@code orgRef} names (the page's {@code ?org=}: an organization's id or its site's slug); else, with
     * neither, the organization whose own site this is. Always one this site reaches ({@link #payPageKey}), so
     * the answer is the same as "no such organization" for one it does not.
     */
    public String orgIdFor(final String tripId, final String orgRef) {
        final Trip trip = knownTrip(tripId);
        if (trip != null && payPageKey(trip.getOrgId()) != null) {
            return trip.getOrgId();
        }
        final Organization named = isBlank(orgRef) ? null : orgNamed(orgRef.trim());
        if (named != null) {
            return named.getId().getValue();
        }
        final SiteContext site = SiteContext.current();
        final String own = site.isOrg() ? site.orgId().getValue() : null;
        return payPageKey(own) == null ? null : own;
    }

    /**
     * The content section key of an organization's How to pay page ({@code page:org:{orgId}:pay}), or null when
     * there is no such page to show here: no such organization, or one this site does not reach
     * ({@link ListingScope#reachable}; {@code SiteCommands.tripPageKey} draws the same line for a trip's page).
     * Computed per request from the request's host, never stored anywhere a later request could read it back.
     */
    public String payPageKey(final String orgId) {
        final Organization org = reachableOrg(orgId);
        return org == null ? null : OrgPageBootstrap.payPageKey(org.getId());
    }

    /** The organization's name for the page's title ("How to pay {name}"); blank when it has no page here. */
    public String orgName(final String orgId) {
        final Organization org = reachableOrg(orgId);
        return org == null || org.getName() == null ? "" : org.getName();
    }

    // ------------------------------------------------------------------ the starter text

    /**
     * {@link #payPageKey}, after giving the organization's page its starter text
     * ({@code OrgPageBootstrap.payRows}: "How to pay {name}", contact the organization at its contact address)
     * the FIRST time the page is asked for. What the page host calls before it renders the sections.
     *
     * <p>Once per organization, recorded on its row ({@code Organization.payPageSeededAt}, the
     * {@code homePageSeededAt} pattern): a page its editors emptied stays empty, and every later render,
     * a signed-out visitor's included, writes nothing. It used to seed whenever the page was empty, so an
     * anonymous GET refilled a page someone had just cleared. A page authored before its first render is adopted
     * as it is. The starter row's id is fixed per organization, so two first visits at once write one row. Asked
     * of the cached organization first: a render reads the store only while the page still looks unseeded.
     */
    public String ensurePage(final String orgId) {
        final Organization org = reachableOrg(orgId);
        if (org == null) {
            return null;
        }
        final String key = OrgPageBootstrap.payPageKey(org.getId());
        if (org.getPayPageSeededAt() == null && seedDue(org.getId().getValue())) {
            boolean settled = false;
            try {
                settled = seedOnce(org.getId(), key);
            } catch (final IOException | RuntimeException ex) {
                // The page still renders (its fallback text, or what the cache holds): a failed seed is retried.
                log.error("Unable to seed the How to pay page of organization {}", org.getId().getValue(), ex);
            }
            noteSeedAttempt(org.getId().getValue(), settled);
        }
        return key;
    }

    /** Whether this organization's seed may be tried now: always, unless a recent try recorded nothing. */
    private boolean seedDue(final String orgId) {
        final Instant retryAt = seedRetryAt.get(orgId);
        return retryAt == null || !now().isBefore(retryAt);
    }

    private void noteSeedAttempt(final String orgId, final boolean settled) {
        if (settled) {
            seedRetryAt.remove(orgId);
        } else {
            seedRetryAt.put(orgId, now().plus(SEED_RETRY_AFTER));
        }
    }

    /** The clock the retry bound reads. Seam for tests. */
    protected Instant now() {
        return Instant.now();
    }

    /**
     * The starter text of an organization's page as HTML ({@code OrgPageBootstrap.payBody}, which escapes the
     * name and the address): what {@code trip/pay.xhtml} shows while the page has no section yet, so the
     * fallback and the seeded row are one text and cannot drift. Blank for an organization with no page here.
     */
    public String starterBody(final String orgId) {
        final Organization org = reachableOrg(orgId);
        return org == null ? ""
                : OrgPageBootstrap.payBody(org.getName(), new MailAddressCommands().contactEmailOf(org));
    }

    /**
     * Read fresh: the cached organization can lag the visit that seeded the page a moment ago. Answers whether
     * the seed is settled (recorded now, or found recorded); false means nothing was recorded and it is worth
     * another try later ({@link #SEED_RETRY_AFTER}).
     */
    private boolean seedOnce(final Organization.Id orgId, final String key) throws IOException {
        final Organization org = DAO.getInstance().getOrganization(orgId, Cached.NO).orElse(null);
        if (org == null || org.getPayPageSeededAt() != null) {
            return true;
        }
        final String missing = missingStarter(this::starterInstalled);
        if (missing != null) {
            // Production before install-starter-templates ran: a row pinned to a missing template renders as
            // nothing. Not recorded as seeded, so the first visit after the install seeds the page.
            log.warn("How to pay page {} not seeded: starter template '{}' is not installed", key, missing);
            return false;
        }
        final List<ContentInstance> rows = OrgPageBootstrap.payRows(org,
                new MailAddressCommands().contactEmailOf(org), OrgCommands::currentTemplateVersion);
        if (!OrgCommands.seedIfEmpty(key, rows)) {
            log.error("The starter text of How to pay page {} could not be saved", key);
            return false;
        }
        org.setPayPageSeededAt(LocalDateTime.now());
        if (!saveOrg(org)) {
            // Not audited: the trail says "seeded" once, when the organization row records it. Until then the
            // seed is tried again (the starter row's id is fixed, so a retry rewrites the same row).
            log.error("Unable to record that How to pay page {} was seeded", key);
            return false;
        }
        // The page and the organization row both changed, on whoever's render came first (a signed-out
        // visitor's included): the organization's trail says when its page appeared and that the system, not a
        // person, wrote it, as the home page's seed does (OrgCommands.seedHomePage).
        new AuditCommands().payPageSeeded(org, key);
        return true;
    }

    /** Records the seeding on the organization's row. Seam for tests (the local store always saves). */
    protected boolean saveOrg(final Organization org) throws IOException {
        return Boolean.TRUE.equals(DAO.getInstance().saveOrganization(org));
    }

    /** The first starter template the pay page's rows need that {@code installed} says is missing, or null. */
    static String missingStarter(final Predicate<String> installed) {
        return OrgPageBootstrap.payRowTemplateIds().stream().filter(installed.negate()).findFirst().orElse(null);
    }

    /** Whether a starter template is installed. Seam for tests (the local store always has them all). */
    protected boolean starterInstalled(final String templateId) {
        return DAO.getInstance().getTemplate(templateId, Cached.YES).isPresent();
    }

    // ------------------------------------------------------------------ lookups

    /** The trip as this site serves it, or null: the money gate's own test, so the two cannot disagree. */
    private Trip knownTrip(final String tripId) {
        return TripFinanceGate.knownTrip(trips, tripId);
    }

    /**
     * The organization with this id when this site reaches it, else null. Never the platform's own organization
     * ({@code Organization.isPlatform}): it runs no trips, so it has no pay page to show, name or seed.
     */
    private Organization reachableOrg(final String orgId) {
        final Organization org = orgs.findOrganization(orgId);
        return org == null || org.isPlatform() || !ListingScope.reachable(org.getId().getValue()) ? null : org;
    }

    /** The reachable organization an id or a site slug names, or null. */
    private Organization orgNamed(final String ref) {
        final Organization byId = reachableOrg(ref);
        if (byId != null) {
            return byId;
        }
        return orgs.getOrganizations().stream()
                .filter(org -> ref.equalsIgnoreCase(org.getSlug()))
                .findFirst()
                .map(org -> reachableOrg(org.getId().getValue()))
                .orElse(null);
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
