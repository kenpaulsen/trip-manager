package org.paulsens.trip.action;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;

/**
 * A person's profile pictures — up to {@link #MAX_SLOTS}, one of them THE picture — answered without touching
 * S3 on every call. Exposed to pages as {@code #{profilePhotos}}.
 *
 * <p>The question is asked a lot: a trip contacts page renders 100+ people, each needing the answer to choose
 * between the photo and a gravatar fallback. The index is seeded with ONE listing of the whole prefix rather
 * than a HEAD per person, then kept fresh by subscribing to media changes under the prefix
 * ({@link MediaEvents}) — every write path in this class announces itself through those events, so the index
 * updates itself the same way whether this instance or a peer bean did the writing.
 *
 * <p><b>Key layout — versioned, derived, never stored per-photo:</b>
 * {@code profilePics/{personId}/{slot}-{version}.jpg}, slots 1-{@value #MAX_SLOTS}. A replace mints a NEW key
 * and deletes the old one rather than overwriting: these objects serve with a day-long cache through
 * CloudFront AND browsers, invalidation cannot reach a browser cache, and a "replace photo" feature whose
 * result shows up tomorrow is broken. The legacy single-photo form {@code profilePics/{personId}.jpg} parses
 * as slot 1 so pre-migration photos keep working (see the migration runbook in setup-instructions.txt).
 * Which slot is THE picture lives on {@link Person#getProfilePhotoSlot()}; no slot chosen (or a chosen slot
 * since deleted) falls back to the lowest occupied slot, the deterministic default.
 *
 * <p>Storage is S3 when configured, else a capped in-memory map served by the profile-photo servlet's GET —
 * the same two-store split as chat photos, and for the same reason: local runs and webtests must exercise the
 * whole flow with no AWS. There are deliberately NO media-table rows for profile pictures: the S3 listing is
 * the whole inventory, and the one pointer that cannot be derived (the chosen slot) lives on the Person.
 *
 * <p>Another instance's upload is not seen until this one restarts. Accepted: photos change rarely and the
 * failure mode is a stale/absent photo for a while (and there is deliberately one task in this deployment).
 */
@Slf4j
@Named("profilePhotos")
@ApplicationScoped
public class ProfilePhotos {

    static final String PREFIX = "profilePics/";
    static final String SUFFIX = ".jpg";

    /** The feature spec's cap: a person curates at most four pictures. */
    public static final int MAX_SLOTS = 4;

    /** Same day-long cache as curated media — safe here because replaced photos change URL, not content. */
    static final long CACHE_SECONDS = 86_400L;

    /** Local-store budget; profile renditions are ~100 KB, so this is hundreds of photos. */
    static final long LOCAL_STORE_MAX_BYTES = 32L * 1024 * 1024;

    /** personId -> slot -> current key. Sorted by slot so "lowest occupied" is {@code firstKey()}. */
    private final Map<String, ConcurrentSkipListMap<Integer, Entry>> byPerson = new ConcurrentHashMap<>();
    private volatile boolean seeded;

    /** Local-mode object store: key -> jpeg bytes. Guarded by its own monitor; access is rare and brief. */
    private final Map<String, byte[]> localObjects = new LinkedHashMap<>();
    private long localBytes;

    @Inject
    private MediaCommands media;

    @Inject
    private PersonCommands people;

    /** One occupied slot, shaped for the profile page's {@code ui:repeat}. */
    public record Slot(int number, String key, String url) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    }

    /** A key under the prefix, decomposed. Legacy keys carry version -1 so any versioned key beats them. */
    record SlotKey(String personId, int slot, long version) {
    }

    private record Entry(String key, long version) {
    }

    @PostConstruct
    void subscribe() {
        MediaEvents.onPrefix(PREFIX, this::onMediaChange);
    }

    /** @return the object key for a new photo in a slot. Derivable state only — never stored anywhere. */
    public static String keyFor(final String personId, final int slot, final long version) {
        return PREFIX + personId + "/" + slot + "-" + version + SUFFIX;
    }

    /** The pre-slots key form, still parsed (slot 1) until the migration script has moved everything. */
    static String legacyKeyFor(final String personId) {
        return PREFIX + personId + SUFFIX;
    }

    /** @return the decomposed key, or null when it is not a profile-photo key of either form. */
    static SlotKey parse(final String key) {
        if (key == null || !key.startsWith(PREFIX) || !key.endsWith(SUFFIX)) {
            return null;
        }
        final String rest = key.substring(PREFIX.length(), key.length() - SUFFIX.length());
        final int slash = rest.indexOf('/');
        if (slash < 0) {
            return rest.isEmpty() ? null : new SlotKey(rest, 1, -1L);
        }
        final String personId = rest.substring(0, slash);
        final String file = rest.substring(slash + 1);
        final int dash = file.indexOf('-');
        if (personId.isEmpty() || dash <= 0 || file.indexOf('/') >= 0) {
            return null;
        }
        try {
            final int slot = Integer.parseInt(file.substring(0, dash));
            final long version = Long.parseLong(file.substring(dash + 1));
            return (slot >= 1 && slot <= MAX_SLOTS && version >= 0)
                    ? new SlotKey(personId, slot, version) : null;
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    /**
     * @return whether this person has any profile photo. Never throws: if the store cannot be reached the
     *         answer is "no", which renders a gravatar -- the same thing shown for anyone without a photo.
     */
    public boolean hasPhoto(final String personId) {
        if (personId == null) {
            return false;
        }
        seed();
        return !slotsOf(personId).isEmpty();
    }

    /**
     * @return the URL of THE profile picture — the chosen slot when it still exists, else the lowest occupied
     *         slot — or null when the person has no photos ({@link #hasPhoto} is the guard every caller uses).
     */
    public String getUrl(final String personId) {
        if (personId == null) {
            return null;
        }
        seed();
        final ConcurrentSkipListMap<Integer, Entry> slots = slotsOf(personId);
        if (slots.isEmpty()) {
            return null;
        }
        final Entry entry = slots.get(selectedSlotIn(slots, personId));
        return entry == null ? null : urlFor(entry.key());
    }

    /** Every occupied slot in slot order, URLs resolved — the profile page's grid model. */
    public List<Slot> getSlots(final String personId) {
        seed();
        final List<Slot> out = new ArrayList<>();
        for (final Map.Entry<Integer, Entry> slot : slotsOf(personId).entrySet()) {
            out.add(new Slot(slot.getKey(), slot.getValue().key(), urlFor(slot.getValue().key())));
        }
        return out;
    }

    /** @return the slot {@link #getUrl} would serve, or 0 when the person has no photos. */
    public int getSelectedSlot(final String personId) {
        seed();
        final ConcurrentSkipListMap<Integer, Entry> slots = slotsOf(personId);
        return slots.isEmpty() ? 0 : selectedSlotIn(slots, personId);
    }

    /** @return the lowest unoccupied slot (1-{@value #MAX_SLOTS}), or 0 when all are taken. */
    public int nextFreeSlot(final String personId) {
        seed();
        final ConcurrentSkipListMap<Integer, Entry> slots = slotsOf(personId);
        for (int slot = 1; slot <= MAX_SLOTS; slot++) {
            if (!slots.containsKey(slot)) {
                return slot;
            }
        }
        return 0;
    }

    /**
     * Stores a new photo in a slot, replacing (and deleting) whatever the slot held. The new object is
     * written and announced BEFORE the old one is removed, so there is no moment where the slot answers
     * empty; the version is strictly monotonic per slot so two replacements in the same millisecond cannot
     * collide on a key.
     *
     * @return false when the backing store refused the bytes (S3 failure); nothing was changed.
     */
    public boolean store(final String personId, final int slot, final byte[] jpeg) {
        if (slot < 1 || slot > MAX_SLOTS) {
            throw new IllegalArgumentException("slot " + slot);
        }
        seed();
        final Entry old = slotsOf(personId).get(slot);
        final long version = Math.max(System.currentTimeMillis(), old == null ? 0 : old.version() + 1);
        final String key = keyFor(personId, slot, version);
        if (!storeBytes(key, jpeg)) {
            return false;
        }
        MediaEvents.fire(MediaEvents.Change.ADDED, key);
        if (old != null) {
            removeStored(old.key());
        }
        return true;
    }

    /**
     * Deletes one slot's photo everywhere it exists (store, index via the REMOVED event, best-effort CDN).
     *
     * @return false when the slot was already empty.
     */
    public boolean deleteSlot(final String personId, final int slot) {
        seed();
        final Entry current = slotsOf(personId).get(slot);
        if (current == null) {
            return false;
        }
        removeStored(current.key());
        return true;
    }

    /** The current bytes of one slot's photo (background removal edits these). Empty when unavailable. */
    public Optional<byte[]> currentBytes(final String personId, final int slot) {
        seed();
        final Entry current = slotsOf(personId).get(slot);
        if (current == null) {
            return Optional.empty();
        }
        if (media.isUploadEnabled()) {
            return media.getObject(current.key());
        }
        synchronized (localObjects) {
            return Optional.ofNullable(localObjects.get(current.key()));
        }
    }

    /** Local-mode read-back, for the profile-photo servlet's GET. Empty when remote or unknown. */
    public Optional<byte[]> localGet(final String key) {
        synchronized (localObjects) {
            return Optional.ofNullable(localObjects.get(key));
        }
    }

    /**
     * The chosen slot when valid, else the lowest occupied — the ONE definition of "the" picture, shared by
     * every read path so they cannot disagree. Person lookups ride the point cache; failures (or a not-yet
     * injected bean in a unit test) simply mean "no explicit choice".
     */
    private int selectedSlotIn(final ConcurrentSkipListMap<Integer, Entry> slots, final String personId) {
        final Integer chosen = chosenSlot(personId);
        return (chosen != null && slots.containsKey(chosen)) ? chosen : slots.firstKey();
    }

    private Integer chosenSlot(final String personId) {
        if (people == null) {
            return null;
        }
        try {
            return people.getPerson(Person.Id.from(personId)).getProfilePhotoSlot();
        } catch (final RuntimeException ex) {
            log.warn("Could not read the chosen photo slot for {}; using the lowest", personId, ex);
            return null;
        }
    }

    /** The URL a browser loads this key from: the CDN when configured, the servlet's GET locally. */
    private String urlFor(final String key) {
        if (media.isUploadEnabled()) {
            return media.publicUrl(key);
        }
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final String contextPath = ctx == null ? "" : ctx.getExternalContext().getRequestContextPath();
        return contextPath + "/profile-photos/" + key;
    }

    private ConcurrentSkipListMap<Integer, Entry> slotsOf(final String personId) {
        return byPerson.computeIfAbsent(personId, id -> new ConcurrentSkipListMap<>());
    }

    /** Media-change listener: ADDED keeps the newest version per slot; REMOVED only evicts the current key. */
    private void onMediaChange(final MediaEvents.Change change, final String key) {
        final SlotKey parsed = parse(key);
        if (parsed == null) {
            return;
        }
        if (change == MediaEvents.Change.ADDED) {
            slotsOf(parsed.personId()).merge(parsed.slot(), new Entry(key, parsed.version()),
                    ProfilePhotos::newer);
        } else {
            // A REMOVED for a superseded key must not evict its replacement (replace fires ADDED first).
            slotsOf(parsed.personId()).computeIfPresent(parsed.slot(),
                    (slot, current) -> current.key().equals(key) ? null : current);
        }
    }

    private static Entry newer(final Entry current, final Entry candidate) {
        return candidate.version() >= current.version() ? candidate : current;
    }

    private boolean storeBytes(final String key, final byte[] jpeg) {
        if (media.isUploadEnabled()) {
            return media.putObject(key, jpeg, "image/jpeg", CACHE_SECONDS, false);
        }
        synchronized (localObjects) {
            localBytes += jpeg.length;
            localObjects.put(key, jpeg);
            final var iterator = localObjects.entrySet().iterator();
            while (localBytes > LOCAL_STORE_MAX_BYTES && iterator.hasNext()) {
                localBytes -= iterator.next().getValue().length;
                iterator.remove();
            }
        }
        return true;
    }

    /** Deletes a stored object, announces it, and (privacy, not correctness) invalidates its CDN path. */
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
        MediaEvents.fire(MediaEvents.Change.REMOVED, key);
    }

    private void seed() {
        if (seeded) {
            return;
        }
        synchronized (this) {
            if (seeded) {
                return;
            }
            try {
                for (final String key : media.listKeys(PREFIX)) {
                    onMediaChange(MediaEvents.Change.ADDED, key);
                }
                log.info("Profile photo index seeded: {} person(s)", byPerson.size());
            } catch (final RuntimeException ex) {
                // Deliberately not marked seeded: a transient failure should be retried on the next request,
                // not leave every person permanently photo-less for the life of the task.
                log.error("Unable to seed the profile photo index; photos fall back to gravatar", ex);
                return;
            }
            seeded = true;
        }
    }
}
