package org.paulsens.trip.content;

import java.util.List;
import java.util.Optional;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;

/**
 * The registry of every {@link ProgrammaticContentTemplate} the application ships -- the KnownSettings
 * pattern: one static declaration drives the admin type picker, template validation, and fragment lookup.
 * The list is fixed at build time, which is what makes it safe to iterate with build-time Facelets tags in
 * the page include (unlike content lists, it can never change between a render and its postback).
 */
public final class ProgrammaticTypes {

    public static final List<ProgrammaticContentTemplate> ALL = List.of(
            new PilgrimagesType(), new PhotoAlbumsType(), new FileType());

    private ProgrammaticTypes() {
    }

    public static Optional<ProgrammaticContentTemplate> byId(final String typeId) {
        return ALL.stream()
                .filter(type -> type.getTypeId().equals(typeId))
                .findFirst();
    }

    /**
     * The placeholder list a template presents to the content dialog, validation, and value migration. For
     * a PROGRAMMATIC template that is its registered type's LIVE property list, never the copy the row
     * stored: that copy is a snapshot of the registry as it was when the row was written, so a property
     * declared later (the shared-site "Organizations shown" list, 2026-09) never reached the dialog of a
     * starter installed earlier, and a production re-install is not an acceptable fix for a code change.
     * The stored list is advisory at most (the manager still shows and refreshes it). Every other kind,
     * and a programmatic row whose type is no longer registered, answers the stored list.
     */
    public static List<Placeholder> placeholdersOf(final ContentTemplate template) {
        if (template == null) {
            return List.of();
        }
        if (template.getKind() != TemplateKind.PROGRAMMATIC) {
            return template.getPlaceholders();
        }
        return byId(template.getProgrammaticTypeId())
                .map(ProgrammaticContentTemplate::getProperties)
                .orElseGet(template::getPlaceholders);
    }
}
