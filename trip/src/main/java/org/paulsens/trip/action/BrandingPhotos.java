package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.extern.slf4j.Slf4j;

/**
 * The images that make up an organization's own look — its logo, favicon, link-preview picture and page
 * background — stored per organization and per role, exposed to pages as {@code #{brandingPhotos}}.
 *
 * <p><b>Key layout:</b> {@code org/{orgId}/branding/{role}-{version}.{ext}}, the role being one of
 * {@link BrandingRole}'s keys and the version an epoch-millisecond stamp. It is modeled on
 * {@code ProfilePhotos} rather than on the media library for two reasons. First, the same one that made
 * profile pictures versioned: these objects serve with a DAY-long cache through CloudFront and through
 * browsers, a browser cache cannot be invalidated at all, and "replace the logo" that takes effect tomorrow
 * is a broken feature — so a replacement is always a NEW key. Second, the media-library write path skips
 * entirely when no bucket is configured (local mode, and every webtest), which would leave the whole feature
 * unexercisable in a browser; the local in-memory store plus a GET servlet is what makes it testable.
 *
 * <p><b>Nothing is deleted on replace.</b> The previous versions stay, newest {@value #KEEP_VERSIONS} per
 * role, which is what makes the Appearance page's "Previous images" list — and the promise that a replaced
 * image can be recovered — true. Beyond that cap the oldest are removed so a site that re-uploads its logo
 * weekly cannot grow without bound. {@code listKeys} on the role's prefix is the whole inventory: there are
 * deliberately no media-table rows, exactly as for profile pictures.
 *
 * <p><b>Tenancy.</b> Both writing and listing require {@code OrgCommands.canManageOrg} for the organization
 * in question, checked here rather than trusted from the page: an object key is a string, and a bean that
 * takes one from a request is one typo away from writing into another tenant's namespace.
 */
@Slf4j
@Named("brandingPhotos")
@ApplicationScoped
public class BrandingPhotos {

    /** Every organization's own namespace; {@code MediaCommands} owns the prefix and refuses foreign ones. */
    static final String ORG_PREFIX = MediaCommands.ORG_KEY_PREFIX;

    /** The folder under an org's namespace that only this bean writes. */
    static final String FOLDER = "branding/";

    /** How many versions per role survive. Enough to undo a mistake, small enough to stay a listing. */
    static final int KEEP_VERSIONS = 5;

    /** Same day-long cache as profile pictures — safe because a replacement is always a NEW key. */
    static final long CACHE_SECONDS = 86_400L;

    /** Local-store budget; branding renditions are a few hundred KB at most. */
    static final long LOCAL_STORE_MAX_BYTES = 32L * 1024 * 1024;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** "{orgId}|{role}" -> version -> object key. Sorted, so the newest version is {@code lastKey()}. */
    private final Map<String, ConcurrentSkipListMap<Long, String>> byOrgRole = new ConcurrentHashMap<>();

    /** Orgs whose stored prefix has been listed once; the index is event-free, so this is the only seed. */
    private final Set<String> seeded = ConcurrentHashMap.newKeySet();

    /** Local-mode object store: key -> bytes. Guarded by its own monitor; access is rare and brief. */
    private final Map<String, byte[]> localObjects = new LinkedHashMap<>();
    private long localBytes;
    /** Instance rather than the constant so a test can exercise eviction without 32 MB of fixtures. */
    private long localStoreMaxBytes = LOCAL_STORE_MAX_BYTES;

    @Inject
    private MediaCommands media;

    @Inject
    private OrgCommands orgs;

    /** One stored version, shaped for the Appearance page's "Previous images" list. */
    public record Version(String key, String url, String role, long version, String when)
            implements Serializable {
    }

    /** A branding key, decomposed. Null from {@link #parse} means "not one of ours", never a guess. */
    record BrandingKey(String orgId, String role, long version, String extension) {
    }

    void localStoreMaxBytesForTest(final long maxBytes) {
        this.localStoreMaxBytes = maxBytes;
    }

    /** @return the object key a new image for this org and role is stored under. Derivable state only. */
    public static String keyFor(final String orgId, final String role, final long version,
            final String extension) {
        return prefixFor(orgId, role) + version + "." + extension;
    }

    /** The prefix every version of one org's one role shares — the listing that IS its history. */
    static String prefixFor(final String orgId, final String role) {
        return ORG_PREFIX + orgId + "/" + FOLDER + role + "-";
    }

    /** @return the decomposed key, or null when it is not a branding key at all. */
    static BrandingKey parse(final String key) {
        if (key == null || !key.startsWith(ORG_PREFIX)) {
            return null;
        }
        final String rest = key.substring(ORG_PREFIX.length());
        final int slash = rest.indexOf('/');
        if (slash <= 0 || !rest.startsWith(FOLDER, slash + 1)) {
            return null;
        }
        final String orgId = rest.substring(0, slash);
        final String file = rest.substring(slash + 1 + FOLDER.length());
        final int dash = file.indexOf('-');
        final int dot = file.lastIndexOf('.');
        if (dash <= 0 || dot <= dash + 1 || file.indexOf('/') >= 0) {
            return null;
        }
        final String role = file.substring(0, dash);
        if (BrandingRole.of(role).isEmpty()) {
            return null;
        }
        try {
            final long version = Long.parseLong(file.substring(dash + 1, dot));
            return version < 0 ? null
                    : new BrandingKey(orgId, role, version, file.substring(dot + 1).toLowerCase(Locale.ROOT));
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Stores a new version of one org's one role image, keeping the previous versions (see the class
     * comment) and pruning past {@value #KEEP_VERSIONS}.
     *
     * @return the new object key, or null when the caller may not manage this org, the role is unknown, or
     *         the backing store refused the bytes — in every case nothing was changed.
     */
    public String store(final String orgId, final String roleKey, final byte[] bytes,
            final String contentType, final String extension) {
        final Optional<BrandingRole> role = BrandingRole.of(roleKey);
        if (orgId == null || orgId.isBlank() || role.isEmpty() || bytes == null || bytes.length == 0
                || !mayManage(orgId)) {
            return null;
        }
        seed(orgId);
        final ConcurrentSkipListMap<Long, String> versions = versionsOf(orgId, role.get().getKey());
        // Strictly monotonic: two replacements in the same millisecond must not collide on a key.
        final long version = versions.isEmpty() ? System.currentTimeMillis()
                : Math.max(System.currentTimeMillis(), versions.lastKey() + 1);
        final String key = keyFor(orgId, role.get().getKey(), version, extension);
        if (!storeBytes(key, bytes, contentType)) {
            return null;
        }
        versions.put(version, key);
        prune(versions);
        return key;
    }

    /**
     * Every stored version of one org's one role, NEWEST FIRST — the recovery list. Empty when the caller
     * may not manage the org, so a page cannot enumerate another tenant's branding by guessing an id.
     */
    public List<Version> history(final String orgId, final String roleKey) {
        final Optional<BrandingRole> role = BrandingRole.of(roleKey);
        if (orgId == null || orgId.isBlank() || role.isEmpty() || !mayManage(orgId)) {
            return List.of();
        }
        seed(orgId);
        final List<Version> out = new ArrayList<>();
        for (final Map.Entry<Long, String> entry
                : versionsOf(orgId, role.get().getKey()).descendingMap().entrySet()) {
            out.add(new Version(entry.getValue(), urlFor(entry.getValue()), role.get().getKey(),
                    entry.getKey(), WHEN.format(Instant.ofEpochMilli(entry.getKey()))));
        }
        return out;
    }

    /**
     * @return what a branding key's bytes should be served as, or null when the key is not one of ours —
     *         which is the whole validation the local-mode servlet needs (only a well-formed key of a known
     *         role, with a known extension, can name anything at all).
     */
    public static String contentTypeOf(final String key) {
        final BrandingKey parsed = parse(key);
        if (parsed == null) {
            return null;
        }
        return switch (parsed.extension()) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            default -> null;
        };
    }

    /** The URL a browser loads this key from: the CDN when configured, this app's own GET locally. */
    public String urlFor(final String key) {
        return media.isUploadEnabled() ? media.publicUrl(key) : localBaseUrl() + "/branding-photos/" + key;
    }

    /**
     * Local mode's stand-in for the CDN's origin, and it has to be ABSOLUTE. These URLs are stored in
     * settings declared {@code withHttpUrl()}, so a context-relative path is refused on save
     * ({@code ContentRenderer.requireHttpUrl}) and dropped again before it could reach an attribute
     * ({@code BrandCommands.safeUrl}) — a relative one would look stored and show nothing. It is built from
     * the request in hand, which on every path that mints a URL is the organization's own admin page.
     */
    private static String localBaseUrl() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null) {
            return "";
        }
        final ExternalContext ext = ctx.getExternalContext();
        final String scheme = ext.getRequestScheme();
        final int port = ext.getRequestServerPort();
        final boolean standardPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + ext.getRequestServerName() + (standardPort ? "" : ":" + port)
                + ext.getRequestContextPath();
    }

    /** Local-mode read-back, for the branding-photo servlet's GET. Empty when remote or unknown. */
    public Optional<byte[]> localGet(final String key) {
        synchronized (localObjects) {
            return Optional.ofNullable(localObjects.get(key));
        }
    }

    /** Seam: the tenancy rule. Tests answer it directly rather than standing up a caller and a roster. */
    protected boolean mayManage(final String orgId) {
        return orgs != null && orgs.canManageOrg(orgId);
    }

    /**
     * Drops every version past the newest {@value #KEEP_VERSIONS}. Recovery is the point of keeping any at
     * all, so this is a cap on unbounded growth, not a retention policy: the versions that survive are the
     * ones somebody might plausibly want back.
     */
    private void prune(final ConcurrentSkipListMap<Long, String> versions) {
        while (versions.size() > KEEP_VERSIONS) {
            removeStored(versions.pollFirstEntry().getValue());
        }
    }

    private ConcurrentSkipListMap<Long, String> versionsOf(final String orgId, final String role) {
        return byOrgRole.computeIfAbsent(orgId + "|" + role, id -> new ConcurrentSkipListMap<>());
    }

    private boolean storeBytes(final String key, final byte[] bytes, final String contentType) {
        if (media.isUploadEnabled()) {
            return media.putObject(key, bytes, contentType, CACHE_SECONDS, false);
        }
        synchronized (localObjects) {
            localBytes += bytes.length;
            localObjects.put(key, bytes);
            final var iterator = localObjects.entrySet().iterator();
            while (localBytes > localStoreMaxBytes && iterator.hasNext()) {
                localBytes -= iterator.next().getValue().length;
                iterator.remove();
            }
        }
        return true;
    }

    /** Deletes a stored object and (tidiness, not correctness) invalidates its CDN path. */
    private void removeStored(final String key) {
        if (media.isUploadEnabled()) {
            media.deleteObject(key);
            media.invalidateCdn(List.of("/" + key));
        } else {
            synchronized (localObjects) {
                final byte[] removed = localObjects.remove(key);
                if (removed != null) {
                    localBytes -= removed.length;
                }
            }
        }
    }

    /**
     * Lists one organization's stored branding once, so a task that restarts still sees what earlier tasks
     * uploaded. Per organization rather than for the whole {@code org/} prefix, which holds every tenant's
     * media library too. A failed listing is NOT marked seeded: the next request retries rather than leaving
     * that org's history permanently empty.
     */
    private void seed(final String orgId) {
        if (seeded.contains(orgId)) {
            return;
        }
        try {
            for (final String key : media.listKeys(ORG_PREFIX + orgId + "/" + FOLDER)) {
                index(key);
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to list stored branding images for organization " + orgId, ex);
            return;
        }
        seeded.add(orgId);
    }

    private void index(final String key) {
        final BrandingKey parsed = parse(key);
        if (parsed != null) {
            versionsOf(parsed.orgId(), parsed.role()).put(parsed.version(), key);
        }
    }
}
