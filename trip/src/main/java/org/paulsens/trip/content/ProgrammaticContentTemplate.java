package org.paulsens.trip.content;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.action.Caller;
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

    /**
     * What an item of this type looks like, as STATIC markup in the fragment's own CSS classes with made-up
     * data: the Add dialog's thumbnail for the type, and its preview (labeled "Sample data") where the site has
     * nothing real to show ({@link #previewValues}). Never a rendered fragment: a card must not run a live
     * listing. Trusted markup written here, with no token and no literal color; "" shows the type's icon.
     */
    default String getSampleHtml() {
        return "";
    }

    /**
     * What the Add dialog's preview knows about where the item would go: the trip whose page it is (null on
     * every other page) and the signed-in person (null for none).
     */
    record PreviewContext(String tripId, String personId) {
    }

    /**
     * The property values that make a PREVIEW of this type show the site's REAL data (its next trip, its first
     * document, the signed-in person), or null when the site has nothing to show, in which case the preview is
     * {@link #getSampleHtml}. An empty map means the type's defaults already show something. Read-only, and
     * only ever data this site lists publicly or the caller's own.
     */
    default Map<String, String> previewValues(final PreviewContext context) {
        return null;
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

    /**
     * Whether this type belongs on a TRIP's own page and nowhere else: the trip page's pictures and facts read the
     * trip the page is about, so on any other page they would have nothing to show. False for every other type.
     */
    default boolean onlyTripPage() {
        return false;
    }

    /**
     * The words a template LAYOUT body of this type may use ({@link TripLayout}; docs/content-templates.md, "Live
     * sections with a layout"), or null for a type with no layout of its own: its fragment is all it draws, and the
     * template editor offers no body. A type that answers one draws its template's body when the body is not blank,
     * and its built-in fragment when it is.
     */
    default TripLayout.Vocabulary layoutVocabulary() {
        return null;
    }

    /**
     * Whether the Add dialog offers this type to {@code caller}. True for every type but one whose content only some
     * callers can read (the metrics graph, docs/metrics.md).
     */
    default boolean offeredTo(final Caller caller) {
        return true;
    }

    /** One dropdown option. Render-time only -- never parked in viewScope. */
    record Choice(String value, String label) {
    }
}
