package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.CssClassScan;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.LocalObjectStore;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Trip;
import org.primefaces.extensions.event.CompleteEvent;

/**
 * A trip page's own stylesheets (docs/conference.md, "Look and feel"): the live editor behind the Trip Page
 * dialog's New and Edit buttons, exposed to pages as {@code #{tripStylesheets}}.
 *
 * <p><b>One media item per stylesheet.</b> A stylesheet is an ordinary library row of the trip's organization
 * ({@code text/css}, so {@code MediaCommands.libraryItems} lists it in the dialog's menu and every trip of the org
 * may pick it), stored under the org's own namespace at {@code org/{orgId}/stylesheets/{slug}.css}. A save
 * overwrites the SAME key: the trips that link it keep their stored URL, the row's {@code rev} is bumped so the
 * rendered {@code <link>} carries {@code ?r=N} (browsers drop their day-old copy), and the CDN path is invalidated
 * (CloudFront's cache key ignores query strings). Renaming changes the title alone; the address never moves.
 *
 * <p><b>Who edits.</b> Site admins, the organization's admins and its {@code mediaAdmin} holders
 * ({@code OrgCommands.canManageOrg} or {@code canViewOrgMedia}); a trip manager only picks. A stylesheet
 * restyles every page of the org that links it, so it is the org's call, like the library.
 *
 * <p><b>Local mode.</b> The library's own write path needs a bucket; here the bytes fall back to an in-memory
 * {@link LocalObjectStore} served by {@code web/StylesheetServlet} at {@code /trip-stylesheets/{key}}, which is
 * what {@code MediaCommands.getUrl} answers for a stylesheet key when no media host is configured.
 */
@Slf4j
@Named("tripStylesheets")
@ApplicationScoped
public class TripStylesheets {

    /** The folder under an org's namespace that only this bean writes. */
    public static final String FOLDER = "stylesheets/";
    /**
     * A limit that keeps a pasted framework out of the session (the editor's text lives in the view while it is
     * open). 256 KB since 2026-11-07: the conference import's own page stylesheets reach 120 KB.
     */
    public static final int MAX_BYTES = 256 * 1024;
    static final long CACHE_SECONDS = 86_400L;
    static final long LOCAL_STORE_MAX_BYTES = 8L * 1024 * 1024;
    static final String STARTER_PATH = "/resources/css/trip-page-starter.css";
    private static final int SLUG_MAX = 40;
    private static final int COMPLETIONS_MAX = 20;
    private static final Pattern KEY = Pattern.compile(
            MediaCommands.ORG_KEY_PREFIX + "[A-Za-z0-9-]+/" + FOLDER + "[a-z0-9][a-z0-9-]*\\.css");
    private static final Pattern SLUG_UNSAFE = Pattern.compile("[^a-z0-9]+");
    private static final String STARTER_FALLBACK = "/* Your page's styles. Rules belong under .contentItem. */\n";

    /** The starter stylesheet's text, read once from the webapp. */
    private static final AtomicReference<String> STARTER = new AtomicReference<>();

    @Inject
    private MediaCommands media;

    @Inject
    private OrgCommands orgs;

    private final LocalObjectStore localStore = new LocalObjectStore(LOCAL_STORE_MAX_BYTES);

    /** One class the page references, for the editor's list: {@code custom}, {@code hook} or {@code styled}. */
    public record ClassRow(String name, String group, String severity, String rule) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.
    }

    // ------------------------------------------------------------------ keys and gates

    /** Whether {@code key} is one of ours: an org's stylesheet folder, a slug, {@code .css}, nothing else. */
    public static boolean isStylesheetKey(final String key) {
        return key != null && !key.contains("..") && KEY.matcher(key).matches();
    }

    /** Whether CSS text holds {@code </style} in any case: it would close an inline style block. */
    public static boolean closesStyleBlock(final String css) {
        return css != null && css.toLowerCase(Locale.ROOT).contains("</style");
    }

    /**
     * Whether a library item is one of its organization's stylesheets the editor may open and save in place: a
     * {@code text/css} item stored in that organization's OWN folder as a {@code .css} file. Not only this bean's
     * {@code stylesheets/} folder: the conference import put each event's page stylesheet under
     * {@code org/{id}/conferences/...}, and a trip linking one opened the editor on "New stylesheet" and refused
     * to read it (2026-11-07). A save rewrites the item's own key, so the folder check is what keeps a write inside
     * the organization.
     */
    static boolean isOrgStylesheet(final MediaItem item) {
        if (item == null || !MediaCommands.isStylesheet(item) || item.getOrgId() == null) {
            return false;
        }
        final String key = item.getS3Key();
        return key != null && !key.contains("..") && key.endsWith(".css")
                && key.startsWith(MediaCommands.ORG_KEY_PREFIX + item.getOrgId() + "/");
    }

    /** A title's slug: lower-case letters and digits with single dashes, never blank. */
    public static String slugOf(final String title) {
        final String slug = SLUG_UNSAFE.matcher(title == null ? "" : title.toLowerCase(Locale.ROOT))
                .replaceAll("-").replaceAll("^-+|-+$", "");
        final String cut = slug.length() > SLUG_MAX ? slug.substring(0, SLUG_MAX).replaceAll("-+$", "") : slug;
        return cut.isBlank() ? "styles" : cut;
    }

    static String keyFor(final String orgId, final String slug) {
        return MediaCommands.ORG_KEY_PREFIX + orgId + "/" + FOLDER + slug + ".css";
    }

    /** Whether the caller may create, edit or rename this org's stylesheets (see the class comment). */
    public boolean canEdit(final String orgId) {
        return orgId != null && !orgId.isBlank() && mayEdit(orgId.trim());
    }

    /** Seam for tests. */
    protected boolean mayEdit(final String orgId) {
        return orgs.canManageOrg(orgId) || orgs.canViewOrgMedia(orgId);
    }

    /**
     * What is wrong with a stylesheet's text, or null when it may be stored: a size cap, no control characters but
     * tab and newline, and no {@code </style}, the one sequence that would end the style block the embed inlines
     * the sheet into. A lone {@code <} is legal CSS (an inline SVG in a data URL, a comment), so it is allowed: the
     * first version refused every {@code <} and with it the imported conference stylesheets.
     */
    public static String check(final String css) {
        if (css == null) {
            return "The stylesheet is empty.";
        }
        if (css.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "The stylesheet is larger than " + (MAX_BYTES / 1024) + " KB.";
        }
        if (closesStyleBlock(css)) {
            return "The stylesheet holds '</style', which would end the page's style block; CSS only, no markup.";
        }
        for (int i = 0; i < css.length(); i++) {
            final char c = css.charAt(i);
            if (c < ' ' && c != '\n' && c != '\r' && c != '\t') {
                return "The stylesheet holds a control character.";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the starter

    /** The commented starter stylesheet a New stylesheet begins as ({@value #STARTER_PATH}). */
    public String starterCss() {
        final String cached = STARTER.get();
        if (cached != null) {
            return cached;
        }
        final String loaded = loadStarter();
        STARTER.compareAndSet(null, loaded);
        return loaded;
    }

    private static String loadStarter() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null) {
            return STARTER_FALLBACK;
        }
        try (InputStream in = ctx.getExternalContext().getResourceAsStream(STARTER_PATH)) {
            return in == null ? STARTER_FALLBACK : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            log.warn("Unable to read the starter stylesheet " + STARTER_PATH, ex);
            return STARTER_FALLBACK;
        }
    }

    // ------------------------------------------------------------------ create, read, save, rename

    /**
     * Stores a new stylesheet for the org and records its library row.
     *
     * @return the new item, or null (with a growl) when the caller may not edit, the text is refused, or the
     *         store refused the bytes; nothing is changed then
     */
    public MediaItem create(final String orgId, final String title, final String css) {
        if (!canEdit(orgId)) {
            PageFeedback.warn("Not allowed", "Only this organization's admins and media admins edit its stylesheets.");
            return null;
        }
        final String refusal = check(css);
        if (refusal != null) {
            PageFeedback.warn("Not saved", refusal);
            return null;
        }
        final String owner = orgId.trim();
        final String cleanTitle = (title == null || title.isBlank()) ? "Stylesheet" : title.trim();
        final String key = freeKey(owner, slugOf(cleanTitle));
        final byte[] bytes = css.getBytes(StandardCharsets.UTF_8);
        if (!storeBytes(key, bytes)) {
            PageFeedback.failure("Not saved: the stylesheet could not be stored.");
            return null;
        }
        final MediaItem item = new MediaItem(UUID.randomUUID().toString(), key, cleanTitle,
                "Trip page stylesheet", "text/css", bytes.length, null, 0, LocalDateTime.now(),
                actorEmail(), null, null, owner);
        if (!record(item, "Not saved: the stylesheet was stored but could not be listed.")) {
            return null;
        }
        MediaEvents.fire(MediaEvents.Change.ADDED, key);
        audit(key, "Created stylesheet " + key + " (" + bytes.length + " bytes)");
        return item;
    }

    /** The first unused key for this slug: {@code name.css}, then {@code name-2.css}, and so on. */
    private String freeKey(final String orgId, final String slug) {
        String key = keyFor(orgId, slug);
        for (int n = 2; media.getByKey(key) != null; n++) {
            key = keyFor(orgId, slug + "-" + n);
        }
        return key;
    }

    /**
     * A stylesheet's text for the editor, or null when the item is not one of the org's stylesheets or the caller
     * may not edit it. A local row whose bytes were never stored (the seeded sample) reads as the starter.
     */
    public String read(final String itemId) {
        final MediaItem item = editable(itemId);
        if (item == null) {
            return null;
        }
        final Optional<byte[]> bytes = media.isUploadEnabled() ? media.getObject(item.getS3Key())
                : localBytes(item.getS3Key());
        return bytes.map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
    }

    /**
     * Overwrites a stylesheet's text at its SAME key and bumps the row's {@code rev} (the class comment explains
     * why the key never changes). Invalidates the CDN path once per save.
     *
     * @return whether the new text is stored and listed
     */
    public boolean save(final String itemId, final String css) {
        final MediaItem item = editable(itemId);
        if (item == null) {
            PageFeedback.warn("Not allowed", "Only this organization's admins and media admins edit its stylesheets.");
            return false;
        }
        final String refusal = check(css);
        if (refusal != null) {
            PageFeedback.warn("Not saved", refusal);
            return false;
        }
        final byte[] bytes = css.getBytes(StandardCharsets.UTF_8);
        if (!storeBytes(item.getS3Key(), bytes)) {
            PageFeedback.failure("Not saved: the stylesheet could not be stored.");
            return false;
        }
        final MediaItem rewritten = item.withRewritten(bytes.length);
        if (!record(rewritten, "Saved, but the stylesheet's listing could not be updated.")) {
            return false;
        }
        if (media.isUploadEnabled()) {
            media.invalidateCdn(List.of("/" + item.getS3Key()));
        }
        audit(item.getS3Key(), "Saved stylesheet " + item.getS3Key() + " rev " + rewritten.getRev()
                + " (" + bytes.length + " bytes)");
        return true;
    }

    /** Changes a stylesheet's title in the menu; its key, and so every stored URL, stays. */
    public boolean rename(final String itemId, final String title) {
        final MediaItem item = editable(itemId);
        if (item == null || title == null || title.isBlank()) {
            return false;
        }
        if (title.trim().equals(item.getTitle())) {
            return true;
        }
        if (!record(item.withTitle(title.trim()), "Not renamed: the stylesheet's listing could not be updated.")) {
            return false;
        }
        audit(item.getS3Key(), "Renamed stylesheet " + item.getS3Key() + " to '" + title.trim() + "'");
        return true;
    }

    /** Saves a library row, growling {@code failure} (and logging a throw) when it does not land. */
    private static boolean record(final MediaItem item, final String failure) {
        try {
            if (DAO.getInstance().saveMedia(item)) {
                return true;
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to save the media row of " + item.getS3Key(), ex);
        }
        PageFeedback.failure(failure);
        return false;
    }

    /**
     * The live editor's first open on a trip's details page ({@code ?styles=new} or {@code ?styles=true}): fills
     * the editor's view state ({@code cssItemId}, {@code cssTitle}, {@code cssText}) with a NEW stylesheet from
     * the starter, else with the stylesheet the trip's stored page options link; a trip linking none leaves the
     * editor empty, with the menu of the organization's stylesheets to pick from.
     *
     * @return whether something was loaded
     */
    public boolean prepare(final Trip trip, final String mode) {
        if (trip == null || !canEdit(trip.getOrgId())) {
            return false;
        }
        if ("new".equalsIgnoreCase(mode)) {
            stashAll(null, "", starterCss());
            return true;
        }
        final MediaItem linked = linkedItem(trip);
        return linked != null && openItem(linked.getId());
    }

    /**
     * The page toolbar's Styles button. The first open in a view loads the stylesheet the trip links, or the starter
     * when it links none; a later open keeps what the designer already has in the editor. One call, because a
     * JSFT command script cannot hold this logic: an {@code if} body with a comparison inside a method argument
     * fails EL parsing at run time (the 2026-11-07 "Something went wrong" on every click).
     *
     * @return whether the editor holds something
     */
    public boolean openEditor(final Trip trip) {
        if (viewValue("cssText") != null) {
            return true;
        }
        return prepare(trip, linkedItem(trip) == null ? "new" : "true");
    }

    /**
     * The editor's menu pick: the "New stylesheet" entry (blank) loads the starter, any other entry that
     * stylesheet. One call for the same reason as {@link #openEditor}: the menu's old if-body script never ran.
     *
     * @return whether the pick loaded
     */
    public boolean pick(final String itemId) {
        if (itemId == null || itemId.isBlank()) {
            stashAll(null, "", starterCss());
            return true;
        }
        return openItem(itemId.trim());
    }

    /** The stylesheet the trip's STORED page options link, or null. */
    public MediaItem linkedItem(final Trip trip) {
        return trip == null || trip.pageOrNull() == null ? null
                : itemFor(trip.getOrgId(), trip.pageOrNull().getStylesheetUrl());
    }

    /** The live editor's menu: the organization's stylesheets, as the Trip Page dialog lists them. */
    public List<MediaItem> choices(final String orgId) {
        return media.libraryItems(orgId, "text/css");
    }

    /**
     * The live editor's menu pick: loads one of the organization's stylesheets into the editor's view state.
     *
     * @return whether it loaded (false, with a growl, for a foreign or unreadable item)
     */
    public boolean openItem(final String itemId) {
        final MediaItem item = editable(itemId);
        final String text = item == null ? null : read(item.getId());
        if (text == null) {
            PageFeedback.warn("Not opened", "That stylesheet could not be read.");
            return false;
        }
        stashAll(item.getId(), item.getTitle(), text);
        return true;
    }

    /**
     * The live editor's Save: creates the stylesheet when {@code itemId} is null (and keeps editing the new item),
     * else saves its text and applies a title change. Stores the stylesheet ONLY: linking it to a trip is the trip
     * editor's Trip Page dialog, so a designer may work on a sheet without touching the trip. Publishes the outcome
     * as the {@code cssOk} callback param so the dialog stays open on a refusal, and {@code cssUrl} for the page's
     * note on what to link.
     *
     * @return whether the stylesheet is stored
     */
    public boolean saveOnly(final String itemId, final String title, final String css) {
        boolean ok = false;
        String url = null;
        if (title == null || title.isBlank()) {
            PageFeedback.warn("Not saved", "Title is required.");
        } else if (itemId == null || itemId.isBlank()) {
            final String org = orgInView();
            final MediaItem created = org == null ? null : create(org, title, css);
            if (created != null) {
                stashAll(created.getId(), created.getTitle(), css);
                url = urlOf(created);
                ok = true;
            }
        } else {
            ok = save(itemId, css) && rename(itemId, title);
            final MediaItem item = ok ? DAO.getInstance().getMedia(itemId.trim(), Cached.NO).orElse(null) : null;
            if (item != null) {
                stashAll(item.getId(), item.getTitle(), css);
                url = urlOf(item);
            }
        }
        PageFeedback.callbackParam("cssOk", ok);
        PageFeedback.callbackParam("cssUrl", url == null ? "" : url);
        return ok;
    }

    private void stashAll(final String itemId, final String title, final String css) {
        stash("cssItemId", itemId);
        stash("cssTitle", title);
        stash("cssText", css);
    }

    /** The organization whose stylesheets the editor page works on: the trip pinned in the view. Seam for tests. */
    protected String orgInView() {
        final String tripId = tripIdInView();
        final Trip trip = tripId == null ? null : DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        return trip == null ? null : trip.getOrgId();
    }

    /** The org's stylesheet a stored URL names (the dialog's menu value), or null for anything else. */
    public MediaItem itemFor(final String orgId, final String url) {
        final MediaItem item = media.libraryItemFor(orgId, url);
        return isOrgStylesheet(item) ? item : null;
    }

    /** The URL a trip stores for this stylesheet (bare: the render appends the cache buster). */
    public String urlOf(final MediaItem item) {
        return item == null ? null : media.getUrl(item);
    }

    /** {@link #urlOf} for the editor's picked item id; empty for a new sheet or an unknown id. */
    public String urlOfId(final String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        final String url = urlOf(DAO.getInstance().getMedia(itemId.trim(), Cached.YES).orElse(null));
        return url == null ? "" : url;
    }

    /**
     * Local mode's bytes for the servlet: what the editor stored this run, else the starter text for a row that
     * exists in the listing (the seeded sample has a row and no bytes), else nothing.
     */
    public Optional<byte[]> localGet(final String key) {
        if (!isStylesheetKey(key)) {
            return Optional.empty();
        }
        final Optional<byte[]> stored = localStore.get(key);
        if (stored.isPresent()) {
            return stored;
        }
        return media.getByKey(key) == null ? Optional.empty()
                : Optional.of(starterCss().getBytes(StandardCharsets.UTF_8));
    }

    /** Local mode: the stored bytes of any org stylesheet key, else the starter for a row that has none. */
    private Optional<byte[]> localBytes(final String key) {
        final Optional<byte[]> stored = localStore.get(key);
        return stored.isPresent() ? stored : Optional.of(starterCss().getBytes(StandardCharsets.UTF_8));
    }

    private MediaItem editable(final String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        final MediaItem item = DAO.getInstance().getMedia(itemId.trim(), Cached.NO).orElse(null);
        if (!isOrgStylesheet(item) || !canEdit(item.getOrgId())) {
            return null;
        }
        return item;
    }

    private boolean storeBytes(final String key, final byte[] bytes) {
        if (media.isUploadEnabled()) {
            return media.putObject(key, bytes, "text/css", CACHE_SECONDS, false);
        }
        localStore.put(key, bytes, "text/css");
        return true;
    }

    private static String actorEmail() {
        final AuditActor actor = AuditActor.current();
        return actor == null || actor.email() == null ? "unknown" : actor.email();
    }

    private static void audit(final String key, final String message) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(AuditActor.current())
                .target(AuditEventBuilder.TARGET_MEDIA, key)
                .message(message)
                .log();
    }

    // ------------------------------------------------------------------ classes on the page

    /**
     * The class names the trip's page references, for the editor's "Classes on this page" list: every
     * {@code class} attribute in its rendered sections and in the rich-text values its editors typed (so a class
     * authored in the HTML tab counts), each tagged {@code custom} (defined neither by site.css nor a framework),
     * {@code hook} (a site.css class) or {@code styled} (already given a rule by {@code css}). Custom first.
     */
    public List<ClassRow> classReport(final String tripId, final String css) {
        final Set<String> styled = CssClassScan.selectorsIn(css);
        final Set<String> hooks = siteHooks();
        final List<ClassRow> custom = new ArrayList<>();
        final List<ClassRow> known = new ArrayList<>();
        for (final String name : classesOnPage(tripId)) {
            if (CssClassScan.isFrameworkClass(name)) {
                continue;
            }
            final String rule = ".contentItem ." + name + " {\n  \n}\n";
            if (styled.contains(name)) {
                known.add(new ClassRow(name, "styled", "success", rule));
            } else if (hooks.contains(name)) {
                known.add(new ClassRow(name, "hook", "info", rule));
            } else {
                custom.add(new ClassRow(name, "custom", "warning", rule));
            }
        }
        custom.addAll(known);
        return custom;
    }

    /** {@code pe:codeMirror}'s completion: the page's classes and the site's hooks that start with the token. */
    public List<String> complete(final CompleteEvent event) {
        final String raw = event == null || event.getToken() == null ? "" : event.getToken().trim();
        final String token = raw.startsWith(".") ? raw.substring(1) : raw;
        final Set<String> names = new LinkedHashSet<>();
        for (final ClassRow row : classReport(tripIdInView(), null)) {
            names.add(row.name());
        }
        names.addAll(siteHooks());
        final List<String> out = new ArrayList<>();
        for (final String name : names) {
            if (name.startsWith(token) && out.size() < COMPLETIONS_MAX) {
                out.add(name);
            }
        }
        return out;
    }

    /** Writes one of the editor's view-state scalars (the dialog's bindings). Seam for tests. */
    protected void stash(final String key, final Object value) {
        FacesContext.getCurrentInstance().getViewRoot().getViewMap().put(key, value);
    }

    /** Reads one of the editor's view-state scalars. Seam for tests. */
    protected Object viewValue(final String key) {
        return org.paulsens.trip.util.ScopeUtil.getInstance().getViewMap(key);
    }

    /** The trip the editor page pins in the view (the dialog lives on trip/edit.xhtml), or null off-page. */
    protected String tripIdInView() {
        final Object id = org.paulsens.trip.util.ScopeUtil.getInstance().getViewMap("theTripId");
        return id == null ? null : id.toString();
    }

    private static Set<String> classesOnPage(final String tripId) {
        final Set<String> names = new LinkedHashSet<>();
        if (tripId == null || !OrgPageBootstrap.isTripId(tripId.trim())) {
            return names;
        }
        try {
            final ContentCommands content = new ContentCommands();
            final List<ContentInstance> sections =
                    DAO.getInstance().getContentForSection(OrgPageBootstrap.tripPageKey(tripId.trim()), Cached.YES);
            for (final ContentInstance section : sections) {
                collect(content, section, names);
                for (final ContentInstance child : DAO.getInstance().getContentForSection(section.getId(),
                        Cached.YES)) {
                    collect(content, child, names);
                }
            }
            final Trip trip = DAO.getInstance().getTrip(tripId.trim(), Cached.YES).orElse(null);
            if (trip != null) {
                names.addAll(CssClassScan.classesIn(trip.getDescription()));
            }
        } catch (final RuntimeException ex) {
            log.warn("Unable to scan the classes of trip " + tripId, ex);
        }
        return names;
    }

    private static void collect(final ContentCommands content, final ContentInstance row, final Set<String> into) {
        into.addAll(CssClassScan.classesIn(content.render(row)));
        for (final Placeholder ph : content.placeholdersOf(row)) {
            if (ph.getType() == Placeholder.Type.RICH_TEXT) {
                into.addAll(CssClassScan.classesIn(row.getValues().get(ph.getName())));
            }
        }
    }

    private static Set<String> siteHooks() {
        return CssClassScan.selectorsIn(TripPageCommands.siteResource("/resources/css/site.css"));
    }
}
