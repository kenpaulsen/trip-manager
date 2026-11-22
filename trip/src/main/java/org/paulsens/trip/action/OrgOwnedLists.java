package org.paulsens.trip.action;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Organization;

/**
 * What an organization's own named lists share (its custom palettes, its brand colors), so the two cannot accept
 * different names or guard their writes differently on the same Appearance tab: the write guard, and the naming rule
 * (whitespace collapsed, a length cap, unique within the org whatever the case).
 */
final class OrgOwnedLists {

    private OrgOwnedLists() {
    }

    /**
     * The guard every write starts with: a fresh, uncached copy of the org when the caller may manage it, else null
     * after growling why. {@code refusal} is the whole sentence a caller who may not manage the org is told, so it
     * reads right in every language ({@code docs/i18n.md}: never a noun glued into a sentence).
     */
    static Organization editable(final OrgCommands orgs, final String orgId, final String refusal) {
        if (!orgs.canManageOrg(orgId)) {
            PageFeedback.refuse(Text.get("feedback.shared.notAllowed"), refusal);
            return null;
        }
        final Organization fresh = orgs.freshOrg(orgId);
        if (fresh == null) {
            PageFeedback.refuse(Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.orgCommands.unknownOrg"));
        }
        return fresh;
    }

    /** {@code name} trimmed with inner whitespace collapsed, or null when it is blank or longer than {@code max}. */
    static String clean(final String name, final int max) {
        final String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        return clean.isEmpty() || clean.length() > max ? null : clean;
    }

    /** Whether no item but {@code exceptId} (null: none) already has {@code name}, compared without case. */
    static <T> boolean free(final List<T> items, final Function<T, String> idOf, final Function<T, String> nameOf,
            final String name, final String exceptId) {
        final String wanted = name.toLowerCase(Locale.ROOT);
        return items.stream().noneMatch(item -> !idOf.apply(item).equals(exceptId) && nameOf.apply(item) != null
                && nameOf.apply(item).toLowerCase(Locale.ROOT).equals(wanted));
    }
}
