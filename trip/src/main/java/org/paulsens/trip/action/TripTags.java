package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Trip;

/**
 * The platform's fixed list of trip tags ({@link KnownSettings#TRIP_TAGS}) and the rules for what a trip may store.
 * THE reader of that setting, so a change to how the list is read is made once.
 *
 * <p>A trip stores tag IDS, never labels: the id is the label's {@link #idOf slug} ({@code "Bible Study"} is
 * {@code bible-study}). That keeps a capitalization fix in the setting from orphaning every trip that used the tag,
 * while a real rename does take it off them, which the setting's help text says.
 */
public final class TripTags {

    /** One offered tag: the id a trip stores and the label people see. */
    public record Tag(String id, String label) {
    }

    private TripTags() {
    }

    /** The tags the site offers right now, in the setting's order, duplicates dropped. */
    public static List<Tag> current() {
        return parse(new ConfigCommands().getString(KnownSettings.TRIP_TAGS));
    }

    /** The comma-separated setting value as tags, in order; a blank entry or a repeated id is skipped. */
    public static List<Tag> parse(final String raw) {
        final Map<String, Tag> byId = new LinkedHashMap<>();
        if (raw != null) {
            for (final String part : raw.split(",")) {
                final String label = part.trim();
                final String id = idOf(label);
                if (!id.isEmpty()) {
                    byId.putIfAbsent(id, new Tag(id, label));
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** A label's id: lowercase, every run of other characters one hyphen, no hyphen at either end. */
    public static String idOf(final String label) {
        if (label == null) {
            return "";
        }
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /**
     * The labels of a trip's tags that are still on the offered list, in the list's order; a stored id the list no
     * longer offers is not shown.
     */
    public static List<String> labelsOf(final Trip trip, final List<Tag> offered) {
        if (trip == null) {
            return List.of();
        }
        return offered.stream().filter(tag -> trip.getTags().contains(tag.id())).map(Tag::label).toList();
    }

    /**
     * What a write may store: the requested ids that are on the offered list, each once, in the list's order. An
     * unknown id is dropped rather than refused: the list is the authority, and a stale client must not be able to
     * plant a tag nobody can see or remove.
     */
    public static List<String> sanitize(final List<String> requested, final List<Tag> offered) {
        if (requested == null) {
            return List.of();
        }
        final List<String> wanted = requested.stream().filter(Objects::nonNull).map(TripTags::idOf).toList();
        return offered.stream().map(Tag::id).filter(wanted::contains).toList();
    }
}
