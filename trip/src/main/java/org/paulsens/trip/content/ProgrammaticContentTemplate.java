package org.paulsens.trip.content;

import java.util.List;
import java.util.Set;
import org.paulsens.trip.model.Placeholder;

/**
 * A registered data-driven content type: the Java half of a {@code PROGRAMMATIC}
 * {@link org.paulsens.trip.model.ContentTemplate}. Where a STANDARD template is an HTML body rendered by
 * token substitution, a programmatic one renders through a Facelets fragment ({@link #fragmentPath()}) so
 * the output keeps full JSF/PrimeFaces behavior (accordions, galleria, ajax) -- the fragment reads the
 * hosting {@code ContentInstance} as {@code #{instance}} and pulls its data from the normal cached beans.
 *
 * <p>{@link #properties()} is the NVP prompt list the content editor shows an admin when filling an
 * instance -- it reuses {@link Placeholder} so the dialog's form generation and version pinning work
 * unchanged. A property of type {@link Placeholder.Type#CHOICE} gets its dropdown options from
 * {@link #choicesFor(String)} at render time.
 *
 * <p>Implementations are stateless singletons registered in {@link ProgrammaticTypes}; the registry is the
 * source of truth at template-creation time (the type's properties are copied into the template row) and at
 * render time (fragment path, choices).
 */
public interface ProgrammaticContentTemplate {

    /** Stable identifier stored in the template row, e.g. {@code pilgrimages}. Never rename. */
    String getTypeId();

    /** Shown in the admin's type picker. */
    String getDisplayName();

    /** One line under the picker explaining what this type renders. */
    String getDescription();

    /** The properties an admin fills when creating content of this type (copied into the template). */
    List<Placeholder> getProperties();

    /** The Facelets fragment that renders an instance, e.g. {@code /WEB-INF/ptypes/pilgrimages.xhtml}. */
    String getFragmentPath();

    /**
     * The PrimeIcons class the template picker's card shows for this type (a live type renders no thumbnail:
     * its fragment needs the page). Types override it with something that says what they list.
     */
    default String getIcon() {
        return "pi-cog";
    }

    /** Dropdown options for a {@link Placeholder.Type#CHOICE} property; empty for non-choice names. */
    default List<Choice> choicesFor(final String propertyName) {
        return List.of();
    }

    /**
     * The properties that only mean something on a SHARED site -- curation among several organizations
     * ("CFPW only", "Organizations shown"). An organization's own site lists its own content and nothing
     * else, so the dialog does not show these there.
     */
    default Set<String> sharedSiteOnlyProperties() {
        return Set.of();
    }

    /**
     * Whether an editor may add this type to a TRIP's page (a conference page, docs/conference.md). False for the
     * types that list other trips or their albums: a conference page is about one event.
     */
    default boolean fitsTripPage() {
        return true;
    }

    /** One dropdown option. Render-time only -- never parked in viewScope. */
    record Choice(String value, String label) {
    }
}
