package org.paulsens.trip.action;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Answers "does this person have a profile photo?" without touching S3 on every call. Exposed to pages as
 * {@code #{profilePhotos}}.
 *
 * <p>The question is asked a lot: a trip contacts page renders 100+ people, each needing the answer to choose
 * between the photo and a gravatar fallback. It used to be answered with
 * {@code externalContext.getResource(...)}, a servlet-context lookup that only works while the files live
 * inside the web application -- the page even carried a FIXME asking for a cached alternative. Once the photos
 * moved to S3 that call could only ever return null, so every photo would silently have become a gravatar.
 *
 * <p>The index is seeded with ONE listing of the whole prefix rather than a HEAD per person: a hundred
 * sequential round trips on the first render after a restart would be a visible stall, while one listing
 * answers for everybody. Absence is cached as deliberately as presence -- people without photos are common,
 * and re-probing for each of them on every page would defeat the point.
 *
 * <p>Staleness is bounded by writes rather than by time. This class SUBSCRIBES to media changes under its
 * prefix ({@link MediaEvents}), so an uploaded photo appears immediately instead of after a restart -- without
 * that, uploading a photo would look to the admin like nothing happened. The media layer knows nothing about
 * profile photos; it simply announces what changed.
 *
 * <p>Another instance's upload is not seen until this one restarts. Accepted: photos change rarely and the
 * failure mode is a gravatar shown a while longer.
 */
@Slf4j
@Named("profilePhotos")
@ApplicationScoped
public class ProfilePhotos {

    static final String PREFIX = "profilePics/";
    static final String SUFFIX = ".jpg";

    private final Map<String, Boolean> present = new ConcurrentHashMap<>();
    private volatile boolean seeded;

    @Inject
    private MediaCommands media;

    @PostConstruct
    void subscribe() {
        MediaEvents.onPrefix(PREFIX, (change, key) -> {
            final String personId = personIdFromKey(key);
            if (personId != null) {
                present.put(personId, change == MediaEvents.Change.ADDED);
            }
        });
    }

    /** @return the object key for a person's photo. Derivable from the id, so it is never stored. */
    public static String keyFor(final String personId) {
        return PREFIX + personId + SUFFIX;
    }

    /** @return the person id a media key refers to, or null when the key is not a profile photo. */
    static String personIdFromKey(final String key) {
        if (key == null || !key.startsWith(PREFIX) || !key.endsWith(SUFFIX)) {
            return null;
        }
        final String id = key.substring(PREFIX.length(), key.length() - SUFFIX.length());
        return id.isEmpty() ? null : id;
    }

    /**
     * @return whether this person has a photo. Never throws: if the store cannot be reached the answer is
     *         "no", which renders a gravatar -- the same thing shown for anyone without a photo.
     */
    public boolean hasPhoto(final String personId) {
        if (personId == null) {
            return false;
        }
        seed();
        return Boolean.TRUE.equals(present.get(personId));
    }

    /** @return the public URL of a person's photo (meaningful only when {@link #hasPhoto} is true). */
    public String getUrl(final String personId) {
        return media.publicUrl(keyFor(personId));
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
                media.listKeys(PREFIX).forEach(key -> {
                    final String id = personIdFromKey(key);
                    if (id != null) {
                        present.put(id, Boolean.TRUE);
                    }
                });
                log.info("Profile photo index seeded: {} photo(s)", present.size());
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
