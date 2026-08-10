package org.paulsens.trip.model;

/**
 * What a {@link ContentTemplate} IS, which decides how its instances render and edit:
 *
 * <ul>
 * <li>{@code STANDARD} -- an HTML body with {@code {{token}}} placeholders, rendered by substitution
 *     (the only kind that existed before v2).</li>
 * <li>{@code CONTAINER} -- no body at all: an instance is an ordered list of CHILD instances (stored under
 *     the container instance's id as their section key) plus an optional title. The container itself knows
 *     nothing about its children beyond the template's allow-list and count limit.</li>
 * <li>{@code PROGRAMMATIC} -- data-driven content: the template names a registered
 *     {@code ProgrammaticContentTemplate} type, and instances render through that type's Facelets fragment
 *     with admin-provided property values.</li>
 * </ul>
 *
 * A template's kind is immutable after creation -- changing it would orphan children or strand instances.
 * Rows written before v2 carry no kind and read as {@code STANDARD}.
 */
public enum TemplateKind {
    STANDARD,
    CONTAINER,
    PROGRAMMATIC
}
