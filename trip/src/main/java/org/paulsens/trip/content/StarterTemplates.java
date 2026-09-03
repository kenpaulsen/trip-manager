package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;

/**
 * The built-in content templates, shared by the "Install starter templates" bootstrap action and the
 * local-mode fake data. Each call returns fresh instances (templates are mutable) at version 0 -- the DAO
 * assigns the real version on save. The programmatic starters carry their type's property list themselves
 * (not only via {@code TemplateCommands}' copy-on-create) because FakeData saves them straight through the
 * DAO, and the content dialog builds its form from the stored placeholders.
 */
public final class StarterTemplates {

    public static final String YOUTUBE_VIDEO_ID = "youtube-video";
    public static final String IMAGE_ID = "image";
    public static final String TEXT_ONLY_ID = "text-only";
    public static final String CONTAINER_ID = "container";
    public static final String PILGRIMAGES_ID = "pilgrimages";
    public static final String PHOTO_ALBUMS_ID = "photo-albums";
    public static final String FILE_ID = "file";

    public static final String REGISTRATION_RECEIVED_ID = "registration-received";
    public static final String REGISTRATION_APPROVED_ID = "registration-approved";
    public static final String SUPPORT_REQUEST_ID = "support-request";
    public static final String PAYMENT_CONFIRMATION_ID = "payment-confirmation";
    public static final String ORG_INVITE_ID = "org-invite";

    // The BAND family (2026-09): full-bleed page sections for a marketing-style home page. STANDARD bands
    // take a `tone` (plain | tint | dark) rendered as a class; the container bands fix their own tone and
    // place their heading with {{container:title}}. Leaf templates come before the containers that admit
    // them: the installer puts rows in this order, and a container's allow-list must name installed ids.
    public static final String BAND_HERO_ID = "band-hero";
    public static final String BAND_SPLIT_ID = "band-split";
    public static final String BAND_CTA_ID = "band-cta";
    public static final String BAND_TESTIMONIAL_ID = "band-testimonial";
    public static final String BAND_TEXT_ID = "band-text";
    public static final String FEATURE_CARD_ID = "feature-card";
    public static final String STAT_ITEM_ID = "stat-item";
    public static final String BAND_FEATURES_ID = "band-features";
    public static final String BAND_STATS_ID = "band-stats";
    public static final String BAND_FAQ_ID = "band-faq";
    public static final String BAND_LOGOS_ID = "band-logos";

    /** The most stat items a {@link #BAND_STATS_ID} band lays out: four across is the widest row that reads. */
    public static final int STATS_MAX = 4;

    /** The values the band templates' {@code tone} prompt understands, each a stylesheet class suffix. */
    public static final List<String> TONES = List.of("plain", "tint", "dark");

    /** Ids never deleted by the cleanup script without {@code --include-starters}. */
    public static final List<String> IDS = List.of(YOUTUBE_VIDEO_ID, IMAGE_ID, TEXT_ONLY_ID,
            CONTAINER_ID, PILGRIMAGES_ID, PHOTO_ALBUMS_ID, FILE_ID,
            REGISTRATION_RECEIVED_ID, REGISTRATION_APPROVED_ID, SUPPORT_REQUEST_ID,
            PAYMENT_CONFIRMATION_ID, ORG_INVITE_ID,
            BAND_HERO_ID, BAND_SPLIT_ID, BAND_CTA_ID, BAND_TESTIMONIAL_ID, BAND_TEXT_ID,
            FEATURE_CARD_ID, STAT_ITEM_ID,
            BAND_FEATURES_ID, BAND_STATS_ID, BAND_FAQ_ID, BAND_LOGOS_ID);

    private StarterTemplates() {
    }

    public static List<ContentTemplate> all() {
        return List.of(youtubeVideo(), image(), textOnly(), container(), pilgrimages(), photoAlbums(),
                file(), registrationReceived(), registrationApproved(), supportRequest(),
                paymentConfirmation(), orgInvite(),
                bandHero(), bandSplit(), bandCta(), bandTestimonial(), bandText(), featureCard(), statItem(),
                bandFeatures(), bandStats(), bandFaq(), bandLogos());
    }

    // --- the band family ---

    private static final String TONE_HINT = "plain, tint or dark (blank = plain)";

    private static Placeholder tone() {
        return new Placeholder("tone", Placeholder.Type.TEXT, "Tone", TONE_HINT, false);
    }

    private static Placeholder text(final String name, final String label, final String hint,
            final boolean required) {
        return new Placeholder(name, Placeholder.Type.TEXT, label, hint, required);
    }

    private static Placeholder richText(final String name, final String label, final String hint,
            final boolean required) {
        return new Placeholder(name, Placeholder.Type.RICH_TEXT, label, hint, required);
    }

    private static Placeholder link(final String name, final String label, final String hint,
            final boolean required) {
        return new Placeholder(name, Placeholder.Type.URL, label, hint, required);
    }

    private static Placeholder image(final String name, final String label, final String hint) {
        return new Placeholder(name, Placeholder.Type.IMAGE_URL, label, hint, false);
    }

    private static ContentTemplate standard(final String id, final String name, final String description,
            final String body, final List<Placeholder> placeholders) {
        return new ContentTemplate(id, 0, name, description, body, placeholders, null, null);
    }

    private static ContentTemplate containerBand(final String id, final String name, final String description,
            final String body, final List<String> allowedChildren, final Integer maxChildren) {
        return new ContentTemplate(id, 0, name, description, body, List.of(), null, null,
                TemplateKind.CONTAINER, allowedChildren, maxChildren, null);
    }

    /**
     * The opening band: a headline, an optional line under it, up to two buttons and an optional picture.
     * Each button is ONE anchor whose whole content is its text, written tight (no whitespace inside), so a
     * blank text leaves an empty element that the stylesheet's {@code :empty} rule hides; the picture's
     * element likewise disappears on a blank URL ({@code img[src=""]}). No tone: the hero always wears the
     * deep gradient of the site's own colors.
     */
    private static ContentTemplate bandHero() {
        final String body = """
                <section class="band band-hero">
                    <div class="band-inner hero">
                        <div class="hero-copy">
                            <div class="eyebrow">{{eyebrow}}</div>
                            <h1 class="hero-headline">{{headline}}</h1>
                            <div class="hero-sub">{{subheadline}}</div>
                            <div class="cta-row"><a class="cta cta-primary" href="{{primaryUrl}}">{{primaryText}}</a>
                                <a class="cta cta-secondary" href="{{secondaryUrl}}">{{secondaryText}}</a></div>
                        </div>
                        <div class="hero-art"><img src="{{imageUrl}}" alt="{{imageAlt}}" loading="lazy" /></div>
                    </div>
                </section>
                """;
        return standard(BAND_HERO_ID, "Band: Hero",
                "The full-width opening section of a page: a short label, a headline, a line under it, one or "
                        + "two buttons and an optional picture beside the text.",
                body, List.of(
                        text("eyebrow", "Eyebrow", "A few words above the headline, e.g. the product or "
                                + "organization name; blank shows nothing", false),
                        text("headline", "Headline", "The one sentence the page is about", true),
                        text("subheadline", "Subheadline", "A line or two under the headline", false),
                        text("primaryText", "Primary button text", "Blank hides the button", false),
                        link("primaryUrl", "Primary button link", "A page on this site (/trip/...), "
                                + "an anchor (#section) or an https:// URL", false),
                        text("secondaryText", "Secondary button text", "Blank hides the button", false),
                        link("secondaryUrl", "Secondary button link", "As above", false),
                        image("imageUrl", "Picture URL", "Shown beside the text; blank lets the text span "
                                + "the band"),
                        text("imageAlt", "Picture description", "For screen readers, when there is a "
                                + "picture", false)));
    }

    /**
     * Text beside a picture. With no picture, an icon (a PrimeIcons class such as {@code pi-users}) stands
     * in its place, and with neither the copy spans the band; {@code side} puts the art left or right.
     */
    private static ContentTemplate bandSplit() {
        final String body = """
                <section class="band band-{{tone}}">
                    <div class="band-inner split split-{{side}}">
                        <div class="split-art"><img src="{{imageUrl}}" alt="{{imageAlt}}" loading="lazy" />
                            <i class="split-icon pi {{icon}}" data-icon="{{icon}}"></i></div>
                        <div class="split-copy">
                            <h2 class="band-heading">{{heading}}</h2>
                            <div class="band-body">{{body}}</div>
                            <a class="band-link" href="{{linkUrl}}">{{linkText}}</a>
                        </div>
                    </div>
                </section>
                """;
        return standard(BAND_SPLIT_ID, "Band: Text beside a picture",
                "A heading and rich text on one side, a picture (or a large icon) on the other; a full-width "
                        + "section in the page's chosen tone.",
                body, List.of(tone(),
                        text("side", "Picture side", "left or right (blank = left)", false),
                        image("imageUrl", "Picture URL", "Blank shows the icon instead, or nothing"),
                        text("imageAlt", "Picture description", "For screen readers", false),
                        text("icon", "Icon", "A PrimeIcons class such as pi-users, shown when there is no "
                                + "picture; blank shows nothing", false),
                        text("heading", "Heading", "The section's heading", true),
                        richText("body", "Text", "The section's text", true),
                        text("linkText", "Link text", "An optional link under the text; blank hides it",
                                false),
                        link("linkUrl", "Link", "A page on this site, an anchor or an https:// URL", false)));
    }

    /** A call to action: a heading, a line of text, one button and optional small print. */
    private static ContentTemplate bandCta() {
        final String body = """
                <section class="band band-{{tone}}">
                    <div class="band-inner cta-band">
                        <h2 class="band-heading">{{heading}}</h2>
                        <div class="band-body">{{text}}</div>
                        <div class="cta-row"><a class="cta cta-primary" href="{{buttonUrl}}">{{buttonText}}</a></div>
                        <div class="band-note">{{note}}</div>
                    </div>
                </section>
                """;
        return standard(BAND_CTA_ID, "Band: Call to action",
                "A centered heading, a line of text and one button; usually the last section of a page, "
                        + "in the dark tone.",
                body, List.of(tone(),
                        text("heading", "Heading", "What you are inviting the reader to do", true),
                        richText("text", "Text", "A sentence or two under the heading", false),
                        text("buttonText", "Button text", "The button's label", true),
                        link("buttonUrl", "Button link", "A page on this site (/account/...), an anchor or "
                                + "an https:// URL", true),
                        text("note", "Small print", "An optional line under the button", false)));
    }

    /** One quotation with who said it. The photo is decorative, so its alt text is deliberately empty. */
    private static ContentTemplate bandTestimonial() {
        final String body = """
                <section class="band band-{{tone}}">
                    <div class="band-inner testimonial">
                        <img class="testimonial-photo" src="{{photoUrl}}" alt="" loading="lazy" />
                        <blockquote class="testimonial-quote"><div class="band-body">{{quote}}</div></blockquote>
                        <div class="testimonial-name">{{name}}</div>
                        <div class="testimonial-role">{{role}}</div>
                    </div>
                </section>
                """;
        return standard(BAND_TESTIMONIAL_ID, "Band: Testimonial",
                "A centered quotation with the speaker's name, role and optional photo.",
                body, List.of(tone(),
                        richText("quote", "Quotation", "What they said", true),
                        text("name", "Name", "Who said it; blank shows nothing", false),
                        text("role", "Role", "Their role or organization; blank shows nothing", false),
                        image("photoUrl", "Photo URL", "A small round portrait; blank shows none")));
    }

    /** A heading over rich text, in a readable column. */
    private static ContentTemplate bandText() {
        final String body = """
                <section class="band band-{{tone}}">
                    <div class="band-inner band-prose">
                        <h2 class="band-heading">{{heading}}</h2>
                        <div class="band-body">{{body}}</div>
                    </div>
                </section>
                """;
        return standard(BAND_TEXT_ID, "Band: Text",
                "A full-width section holding a heading and rich text in a readable column.",
                body, List.of(tone(),
                        text("heading", "Heading", "Blank shows no heading", false),
                        richText("body", "Text", "The section's text", true)));
    }

    /**
     * One card of a Features band. Its HEADING is the instance's title, written by the band's row
     * ({@code {{child:title}}}); the card itself holds the icon and the text, so the stylesheet can set
     * the icon beside the heading.
     */
    private static ContentTemplate featureCard() {
        final String body = """
                <div class="feature-card">
                    <i class="feature-icon pi {{icon}}" data-icon="{{icon}}"></i>
                    <div class="feature-text">{{text}}</div>
                </div>
                """;
        return standard(FEATURE_CARD_ID, "Feature card",
                "One card in a Features band: the item's title is its heading; an icon and a short text go "
                        + "with it.",
                body, List.of(
                        text("icon", "Icon", "A PrimeIcons class such as pi-globe; blank shows no icon",
                                false),
                        richText("text", "Text", "A sentence or two", true)));
    }

    /** One figure of a Stats band: a big value over a small label. */
    private static ContentTemplate statItem() {
        final String body = """
                <div class="stat"><div class="stat-value">{{value}}</div><div class="stat-label">{{label}}</div></div>
                """;
        return standard(STAT_ITEM_ID, "Stat item",
                "One figure in a Stats band: a large value with a short label under it.",
                body, List.of(
                        text("value", "Value", "The big figure or phrase", true),
                        text("label", "Label", "What the value is", true)));
    }

    private static ContentTemplate bandFeatures() {
        final String body = """
                <section class="band band-plain">
                    <div class="band-inner">
                        <h2 class="band-heading band-center">{{container:title}}</h2>
                        <div class="featureGrid">
                        {{children:start}}
                            <div class="feature-cell"><h3 class="feature-title">{{child:title}}</h3>{{child}}</div>
                        {{children:end}}
                        </div>
                    </div>
                </section>
                """;
        return containerBand(BAND_FEATURES_ID, "Band: Features",
                "A full-width section laying its Feature cards out in a responsive grid under the section's "
                        + "title. Add only Feature cards to it.",
                body, List.of(FEATURE_CARD_ID), null);
    }

    private static ContentTemplate bandStats() {
        final String body = """
                <section class="band band-dark">
                    <div class="band-inner">
                        <h2 class="band-heading band-center">{{container:title}}</h2>
                        <div class="statGrid">
                        {{children:start}}
                            <div class="stat-cell">{{child}}</div>
                        {{children:end}}
                        </div>
                    </div>
                </section>
                """;
        return containerBand(BAND_STATS_ID, "Band: Stats",
                "A dark full-width section showing up to four Stat items in a row under the section's title.",
                body, List.of(STAT_ITEM_ID), STATS_MAX);
    }

    /** Each child is a question: the child's TITLE is the question and its text the answer. */
    private static ContentTemplate bandFaq() {
        final String body = """
                <section class="band band-tint">
                    <div class="band-inner band-prose">
                        <h2 class="band-heading band-center">{{container:title}}</h2>
                        <div class="faqList">
                        {{children:start}}
                            <details class="faqItem"><summary class="faqQuestion">{{child:title}}</summary>
                                <div class="faqAnswer">{{child}}</div></details>
                        {{children:end}}
                        </div>
                    </div>
                </section>
                """;
        return containerBand(BAND_FAQ_ID, "Band: Questions and answers",
                "A full-width section of questions that open to show their answers. Add Text Only items: "
                        + "each item's title is the question and its text the answer.",
                body, List.of(TEXT_ONLY_ID), null);
    }

    private static ContentTemplate bandLogos() {
        final String body = """
                <section class="band band-plain">
                    <div class="band-inner">
                        <h2 class="band-heading band-center">{{container:title}}</h2>
                        <div class="logoStrip">
                        {{children:start}}
                            <div class="logo-cell">{{child}}</div>
                        {{children:end}}
                        </div>
                    </div>
                </section>
                """;
        return containerBand(BAND_LOGOS_ID, "Band: Logo strip",
                "A full-width row of small logos (Image items) under the section's title, e.g. the "
                        + "organizations or partners a site wants to name.",
                body, List.of(IMAGE_ID), null);
    }

    /**
     * Sent when an org admin invites an email address that has no account yet ({@code OrgCommands}). The
     * site is a TOKEN, never a name in the copy: an org with its own subdomain invites people to ITS site
     * ({@code siteName} = the org's name, {@code siteHost} = {@code acme.unitetrip.com}), and every other org
     * to the shared site it is listed on. Installed rows are runtime-editable and are NOT rewritten by a
     * later install, so a deployment that installed the older, host-naming copy must re-install (or edit)
     * this template to get the tokens.
     *
     * <p>{@code createAccountUrl} (the name is kept for installed rows) is the site's LOGIN page with the
     * invitee's address pre-filled, not the create-account page: the login step checks whether an account
     * exists by now and either signs the person in or carries the address on to Create Account.
     */
    private static ContentTemplate orgInvite() {
        final String body = """
                <p><b>{{orgName}}</b> has invited you to join them on {{siteName}}.</p>
                <p><a href="{{createAccountUrl}}">Sign in or create your account</a> to get started.</p>
                <p>Once you have an account, {{orgName}}'s administrator will be able to add you as a
                member, and you will see your organization's trips and information when you sign in.</p>
                <p>Questions? Just reply to this email.</p>
                """;
        return mail(ORG_INVITE_ID, "{{orgName}} invites you to {{siteHost}}",
                "Sent when an organization admin invites an email address that has no account yet. "
                        + "Tokens: orgName, siteName (the site's name: the organization's own for a "
                        + "subdomain site, else the shared site's), siteHost (its hostname), "
                        + "createAccountUrl (the site's login page with the address pre-filled).",
                body);
    }

    /** Sent to the registering account owner the moment a registration (or family party) is submitted. */
    private static ContentTemplate registrationReceived() {
        final String body = """
                <p>Thank you for registering for <b>{{tripTitle}}</b>!</p>
                <p>We received your application for:</p>
                {{travelersBlock}}
                <p>The next step is approval: we review every registration, and you will hear from us once
                yours is confirmed.</p>
                <p><a href="{{tripUrl}}">See the trip details</a></p>
                <p>Questions? Just reach out&mdash;reply to this email and we will get back to you.</p>
                """;
        return mail(REGISTRATION_RECEIVED_ID, "Registration received - {{tripTitle}}",
                "Sent to the registrant (the account owner for a family) when a registration is submitted. "
                        + "Tokens: tripTitle, tripUrl, travelersBlock (the submitted travelers as HTML).",
                body);
    }

    /** Sent when an administrator approves one registration. */
    private static ContentTemplate registrationApproved() {
        final String body = """
                <p>Welcome to <b>{{tripTitle}}</b>, {{firstName}}!</p>
                <p>Your registration has been approved&mdash;we are so glad you are coming.</p>
                <p><a href="{{itineraryUrl}}">See your trip</a></p>
                <p>Please make sure <a href="{{profileUrl}}">your profile</a> is complete (passport,
                birthdate, emergency contact): we need it for travel arrangements.</p>
                <p>Questions? Just reach out&mdash;reply to this email and we will get back to you.</p>
                """;
        return mail(REGISTRATION_APPROVED_ID, "You're confirmed - {{tripTitle}}",
                "Sent to a person (or, if they have no email, their family's managers) when their "
                        + "registration is approved. Tokens: tripTitle, firstName, itineraryUrl, profileUrl.",
                body);
    }

    /** Sent to every support-channel admin when a member files a support request. */
    private static ContentTemplate supportRequest() {
        final String body = """
                <p>{{requestBlock}}</p>
                <p style="font-size:0.85em;color:#666;">You are receiving this because you are a support
                channel admin (Settings page). The requester cannot see the channel; follow up with them
                directly.</p>
                """;
        return mail(SUPPORT_REQUEST_ID, "{{subject}}",
                "Sent to support-channel admins when a member files a request. Tokens: subject, "
                        + "requestBlock (the request as HTML, including the admin action link).",
                body);
    }

    // A mail starter: the NAME is the subject-line template, the body is HTML with {{tokens}}.

    /**
     * Sent to the payer when a payment completes. Tokens (filled by PaymentMailer, never authors):
     * payerName, tripTitle, totalPaid, feeNote, donationAmount, donationNote, captureId, processorName,
     * paymentDate, orgName, amountsBlock (Raw HTML table of person -> amount).
     */
    private static ContentTemplate paymentConfirmation() {
        final String body = """
                <p>Dear {{payerName}},</p>
                <p>Thank you! Your payment of <b>{{totalPaid}}</b> for <b>{{tripTitle}}</b> was received
                by {{orgName}}.</p>
                {{amountsBlock}}
                <p>{{feeNote}}</p>
                <p>{{donationNote}}</p>
                <p style="font-size:0.85em;color:#666;">Processor: {{processorName}} &middot;
                transaction id {{captureId}} &middot; {{paymentDate}}</p>
                """;
        return mail(PAYMENT_CONFIRMATION_ID, "Payment received - {{tripTitle}}",
                "Payment confirmation email; the name doubles as the subject line.", body);
    }

    private static ContentTemplate mail(final String id, final String subject, final String description,
            final String body) {
        return new ContentTemplate(id, 0, subject, description, body,
                List.of(), null, null, TemplateKind.MAIL, null, null, null);
    }

    private static ContentTemplate container() {
        return new ContentTemplate(CONTAINER_ID, 0, "Container",
                "Holds an ordered list of other content items, with an optional heading. The body is the "
                        + "row wrapped around EACH child: {{child}} marks where the item renders, and "
                        + "{{child:title}} / {{child:id}} / {{child:index}} read that item's properties.",
                ContentRenderer.DEFAULT_CHILD_ROW,
                List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
    }

    private static ContentTemplate pilgrimages() {
        return programmatic(PILGRIMAGES_ID, "Trip Listings",
                "The public trip accordions for one language, straight from the trip data.");
    }

    private static ContentTemplate photoAlbums() {
        return programmatic(PHOTO_ALBUMS_ID, "Trip Photo Albums",
                "A photo gallery per recent trip, from the publicly-visible chat photos.");
    }

    private static ContentTemplate file() {
        return programmatic(FILE_ID, "File (from media library)",
                "A link to one media-library document; its name and size stay in sync with the library.");
    }

    /** A programmatic starter is a thin row over its registered type: same id, the type's properties. */
    private static ContentTemplate programmatic(final String typeId, final String name,
            final String description) {
        final ProgrammaticContentTemplate type = ProgrammaticTypes.byId(typeId)
                .orElseThrow(() -> new IllegalStateException("No programmatic type: " + typeId));
        return new ContentTemplate(typeId, 0, name, description, "",
                type.getProperties(), null, null, TemplateKind.PROGRAMMATIC, null, null, typeId);
    }

    private static ContentTemplate youtubeVideo() {
        final String body = """
                <div class="contentItem contentVideo">
                    <div style="position:relative;width:100%;max-width:840px;margin:0 auto;aspect-ratio:16/9;">
                        <iframe style="position:absolute;top:0;left:0;width:100%;height:100%;"
                                src="{{videoUrl}}" title="{{caption}}" frameborder="0"
                                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope;
                                picture-in-picture; web-share" allowfullscreen="allowfullscreen"></iframe>
                    </div>
                    <div style="text-align:center;font-size:1.05em;margin-top:6px;">{{caption}}</div>
                </div>
                """;
        return new ContentTemplate(YOUTUBE_VIDEO_ID, 0, "YouTube Video",
                "A responsively-sized YouTube video with a caption underneath.", body,
                List.of(new Placeholder("videoUrl", Placeholder.Type.VIDEO_URL, "Video URL",
                                "Paste any YouTube link (watch, share, or shorts form)", true),
                        new Placeholder("caption", Placeholder.Type.TEXT, "Caption",
                                "Short text shown under the video", false)),
                null, null);
    }

    private static ContentTemplate image() {
        // A blank width renders width="" -- an invalid value the browser IGNORES, so leaving the prompt
        // empty shows the image at its natural size (capped by the max-width) rather than at width zero.
        final String body = """
                <div class="contentItem contentImage" style="text-align:center;">
                    <a href="{{linkUrl}}" target="_blank" rel="noopener">
                        <img src="{{imageUrl}}" alt="{{altText}}" width="{{width}}"
                                style="max-width:100%;height:auto;" loading="lazy" />
                    </a>
                    <div style="font-size:1.05em;margin-top:6px;">{{caption}}</div>
                </div>
                """;
        return new ContentTemplate(IMAGE_ID, 0, "Image",
                "A centered image, optionally linked, with a rich-text caption.", body,
                List.of(new Placeholder("imageUrl", Placeholder.Type.IMAGE_URL, "Image URL",
                                "Where the image lives (e.g. the files CDN)", true),
                        new Placeholder("width", Placeholder.Type.TEXT, "Width (pixels)",
                                "Displayed width in pixels, e.g. 212; blank = natural size", false),
                        new Placeholder("linkUrl", Placeholder.Type.URL, "Link URL",
                                "Optional page to open when the image is clicked", false),
                        new Placeholder("altText", Placeholder.Type.TEXT, "Alt text",
                                "Short description for screen readers", true),
                        new Placeholder("caption", Placeholder.Type.RICH_TEXT, "Caption",
                                "Optional rich-text caption under the image", false)),
                null, null);
    }

    private static ContentTemplate textOnly() {
        final String body = """
                <div class="contentItem contentText">{{body}}</div>
                """;
        return new ContentTemplate(TEXT_ONLY_ID, 0, "Text Only",
                "A free-form rich-text block.", body,
                List.of(new Placeholder("body", Placeholder.Type.RICH_TEXT, "Body",
                        "The text to show", true)),
                null, null);
    }
}
