package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An arbitrary value stored against a person, keyed by data id.
 *
 * <p>{@code content} is deliberately {@code Object}: the model stores whatever the todo or form that owns the
 * data id put there, and its shape is that owner's business. Typing it here would mean this record needs
 * changing every time somebody adds a question to a form.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonDataValueDto(String userId, String dataId, String type, Object content) {
}
