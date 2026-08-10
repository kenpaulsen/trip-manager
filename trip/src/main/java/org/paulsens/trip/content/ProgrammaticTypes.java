package org.paulsens.trip.content;

import java.util.List;
import java.util.Optional;

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
}
