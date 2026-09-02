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

    /** Ids never deleted by the cleanup script without {@code --include-starters}. */
    public static final List<String> IDS = List.of(YOUTUBE_VIDEO_ID, IMAGE_ID, TEXT_ONLY_ID,
            CONTAINER_ID, PILGRIMAGES_ID, PHOTO_ALBUMS_ID, FILE_ID,
            REGISTRATION_RECEIVED_ID, REGISTRATION_APPROVED_ID, SUPPORT_REQUEST_ID,
            PAYMENT_CONFIRMATION_ID, ORG_INVITE_ID);

    private StarterTemplates() {
    }

    public static List<ContentTemplate> all() {
        return List.of(youtubeVideo(), image(), textOnly(), container(), pilgrimages(), photoAlbums(),
                file(), registrationReceived(), registrationApproved(), supportRequest(),
                paymentConfirmation(), orgInvite());
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
                member, and you will see your organization's pilgrimages and information when you sign in.</p>
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
        return programmatic(PILGRIMAGES_ID, "Pilgrimage Listings",
                "The public pilgrimage accordions for one language, straight from the trip data.");
    }

    private static ContentTemplate photoAlbums() {
        return programmatic(PHOTO_ALBUMS_ID, "Pilgrimage Photo Albums",
                "A photo gallery per recent pilgrimage, from the publicly-visible chat photos.");
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
