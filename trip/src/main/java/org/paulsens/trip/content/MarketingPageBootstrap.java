package org.paulsens.trip.content;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.paulsens.trip.model.ContentInstance;

/**
 * The product's own home page ({@link OrgPageBootstrap#MARKETING_PAGE_KEY}, the {@code www} host): the
 * band sections {@code bootstrap-marketing-page.sh} conditionally installs in production and
 * {@code FakeData} seeds locally. The SOURCE OF TRUTH for that script's payloads -- regenerate the script
 * rather than hand-editing its JSON. The page is edited in place from then on, like every other page.
 *
 * <p>Ids are deterministic ({@link UUID#nameUUIDFromBytes} over a slot name), so the script's conditional
 * puts, a local seed and a re-run all agree on which row is which; the hero's second button targets the
 * Features band by that id. Every claim in the copy names a shipped feature; nothing here is a customer,
 * a logo or a number, which is why no Testimonial or Logo band is seeded and the Stats band holds phrases
 * rather than figures. Rows are unsaved (version 0; the save path assigns v1) and pinned to the CURRENT
 * version of their band template as {@code templateVersion} reports it.
 */
public final class MarketingPageBootstrap {

    static final String SEED_AUTHOR = "marketing-bootstrap";
    private static final String ID_NAMESPACE = "unitetrip-home:";
    private static final String PAGE = OrgPageBootstrap.MARKETING_PAGE_KEY;

    /** The slot names behind the deterministic ids; a slot is one row and never renamed. */
    public static final String HERO = "hero";
    public static final String FEATURES = "features";
    public static final String SPLIT_MANAGE = "split-manage";
    public static final String SPLIT_TRAVELERS = "split-travelers";
    public static final String SPLIT_PAYMENTS = "split-payments";
    public static final String STATS = "stats";
    public static final String FAQ = "faq";
    public static final String CTA = "cta";

    private MarketingPageBootstrap() {
    }

    /** The stable id of one slot's row: the same UUID on every machine and every run. */
    public static String idOf(final String slot) {
        return UUID.nameUUIDFromBytes((ID_NAMESPACE + slot).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The page's rows, parents before their children, positions ascending within each section, each
     * pinned to the template version {@code templateVersion} answers for its template id.
     */
    public static List<ContentInstance> rows(final ToIntFunction<String> templateVersion) {
        final List<ContentInstance> rows = new ArrayList<>();
        rows.add(hero(templateVersion));
        rows.add(container(FEATURES, PAGE, "Everything a trip needs, in one place", StarterTemplates.BAND_FEATURES_ID,
                templateVersion, 1));
        rows.addAll(featureCards(templateVersion));
        rows.add(split(SPLIT_MANAGE, 2, "tint", "left", "pi-th-large", "Manage everything from one dashboard",
                "<p>Every trip, traveler, registration and payment is on one dashboard for your organization. "
                        + "Hand out exactly the access each helper needs (trip managers, registration admins, "
                        + "content editors) and keep the rest to yourself.</p>"
                        + "<p>An audit trail records who changed what, and when.</p>", templateVersion));
        rows.add(split(SPLIT_TRAVELERS, 3, "plain", "right", "pi-users", "One sign-in for your travelers",
                "<p>Travelers sign in once, with a password, a one-time email code or a passkey, and get their "
                        + "trips, itinerary, chat and payment history in one place.</p>"
                        + "<p>A family account lets one person register and manage a whole household.</p>",
                templateVersion));
        rows.add(split(SPLIT_PAYMENTS, 4, "tint", "left", "pi-wallet", "Payments you can reconcile",
                "<p>Connect your own PayPal account, and every payment lands in a ledger against the trip and "
                        + "the traveler it was for, fees and donations included.</p>"
                        + "<p>A confirmation email goes out on its own, and the reconciliation view shows what "
                        + "has been paid, what is owed and what the processor settled.</p>", templateVersion));
        rows.add(container(STATS, PAGE, "At a glance", StarterTemplates.BAND_STATS_ID, templateVersion, 5));
        rows.addAll(stats(templateVersion));
        rows.add(container(FAQ, PAGE, "Questions", StarterTemplates.BAND_FAQ_ID, templateVersion, 6));
        rows.addAll(questions(templateVersion));
        rows.add(cta(templateVersion));
        return rows;
    }

    private static ContentInstance hero(final ToIntFunction<String> templateVersion) {
        final Map<String, String> values = new HashMap<>();
        values.put("eyebrow", "UniteTrip");
        values.put("headline", "Run your trips from one site that is yours");
        values.put("subheadline", "A branded website, trip listings, online registration, payments, group chat "
                + "and email for the organizations that take people places.");
        values.put("primaryText", "Create an account");
        values.put("primaryUrl", "/account/createAccount.jsf");
        values.put("secondaryText", "See what's included");
        values.put("secondaryUrl", "#" + idOf(FEATURES));
        return row(HERO, PAGE, "Hero", StarterTemplates.BAND_HERO_ID, templateVersion, 0, values);
    }

    private static List<ContentInstance> featureCards(final ToIntFunction<String> templateVersion) {
        final String parent = idOf(FEATURES);
        return List.of(
                card(parent, 0, "Your own branded site", "pi-globe",
                        "A site at your-name.unitetrip.com with your logo, colors, background and footer, all "
                                + "chosen from a settings page.", templateVersion),
                card(parent, 1, "Trip listings and itineraries", "pi-map",
                        "Publish upcoming trips with dates, prices and flyers, and give each traveler a "
                                + "day-by-day itinerary and a printable badge.", templateVersion),
                card(parent, 2, "Online registration", "pi-user-plus",
                        "Travelers register themselves, choose admission options, apply discount codes and add "
                                + "their family in one form. You approve with one click.", templateVersion),
                card(parent, 3, "Payments and ledgers", "pi-credit-card",
                        "Take PayPal payments online, see every payment in a ledger per trip and per traveler, "
                                + "and reconcile against what the processor paid out.", templateVersion),
                card(parent, 4, "Trip chat with photos", "pi-comments",
                        "A group chat for every trip, with photos, reactions and a daily digest email for the "
                                + "people who missed the conversation.", templateVersion),
                card(parent, 5, "Templated email", "pi-envelope",
                        "Registration, approval and payment emails go out automatically from your own address, "
                                + "and mail merge reaches a whole roster at once.", templateVersion));
    }

    private static List<ContentInstance> stats(final ToIntFunction<String> templateVersion) {
        final String parent = idOf(STATS);
        return List.of(
                stat(parent, 0, "Your brand", "on every page and every email", templateVersion),
                stat(parent, 1, "One sign-in", "across all of your trips", templateVersion),
                stat(parent, 2, "Nothing to install", "hosted, backed up and updated for you", templateVersion),
                stat(parent, 3, "Open source", "the application code is public", templateVersion));
    }

    private static List<ContentInstance> questions(final ToIntFunction<String> templateVersion) {
        final String parent = idOf(FAQ);
        return List.of(
                question(parent, 0, "Who is UniteTrip for?",
                        "<p>Organizations that take groups on trips: retreats, tours, mission trips, school travel "
                                + "and religious travel. If you keep a roster, collect payments and answer the "
                                + "same questions by email, it is for you.</p>", templateVersion),
                question(parent, 1, "Do we need our own domain?",
                        "<p>No. Your site lives at your-name.unitetrip.com and is ready the moment it is set "
                                + "up.</p>", templateVersion),
                question(parent, 2, "Can travelers register and pay online?",
                        "<p>Yes. Registration is a form on your site, with the admission options and discount "
                                + "codes you define, and payment goes through your own PayPal account. You see "
                                + "and approve every registration.</p>", templateVersion),
                question(parent, 3, "Who can edit our pages?",
                        "<p>You decide. Editing is a privilege you grant per person, and it applies only to "
                                + "your own site. Pages are edited in place, on the page, with no code.</p>",
                        templateVersion),
                question(parent, 4, "Is our data kept separate from other organizations?",
                        "<p>Yes. Every organization's trips, people and payments are its own, and nothing is "
                                + "shared between organizations.</p>", templateVersion));
    }

    private static ContentInstance cta(final ToIntFunction<String> templateVersion) {
        final Map<String, String> values = new HashMap<>();
        values.put("tone", "dark");
        values.put("heading", "Ready to give your trips a home?");
        values.put("text", "<p>Create an account and we will set up your organization's site with you.</p>");
        values.put("buttonText", "Create an account");
        values.put("buttonUrl", "/account/createAccount.jsf");
        values.put("note", "Already have an account? Sign in from the top of the page.");
        return row(CTA, PAGE, "Call to action", StarterTemplates.BAND_CTA_ID, templateVersion, 7, values);
    }

    private static ContentInstance split(final String slot, final int position, final String tone,
            final String side, final String icon, final String heading, final String body,
            final ToIntFunction<String> templateVersion) {
        final Map<String, String> values = new HashMap<>();
        values.put("tone", tone);
        values.put("side", side);
        values.put("icon", icon);
        values.put("heading", heading);
        values.put("body", body);
        return row(slot, PAGE, heading, StarterTemplates.BAND_SPLIT_ID, templateVersion, position, values);
    }

    private static ContentInstance card(final String parent, final int position, final String title,
            final String icon, final String text, final ToIntFunction<String> templateVersion) {
        return row(FEATURES + "-" + position, parent, title, StarterTemplates.FEATURE_CARD_ID, templateVersion,
                position, Map.of("icon", icon, "text", "<p>" + text + "</p>"));
    }

    private static ContentInstance stat(final String parent, final int position, final String value,
            final String label, final ToIntFunction<String> templateVersion) {
        return row(STATS + "-" + position, parent, value, StarterTemplates.STAT_ITEM_ID, templateVersion,
                position, Map.of("value", value, "label", label));
    }

    private static ContentInstance question(final String parent, final int position, final String question,
            final String answer, final ToIntFunction<String> templateVersion) {
        return row(FAQ + "-" + position, parent, question, StarterTemplates.TEXT_ONLY_ID, templateVersion,
                position, Map.of("body", answer));
    }

    private static ContentInstance container(final String slot, final String section, final String title,
            final String templateId, final ToIntFunction<String> templateVersion, final int position) {
        return row(slot, section, title, templateId, templateVersion, position, Map.of());
    }

    private static ContentInstance row(final String slot, final String section, final String title,
            final String templateId, final ToIntFunction<String> templateVersion, final int position,
            final Map<String, String> values) {
        return new ContentInstance(idOf(slot), section, title, templateId,
                templateVersion.applyAsInt(templateId), new HashMap<>(values), null, position, 0, null,
                SEED_AUTHOR);
    }
}
