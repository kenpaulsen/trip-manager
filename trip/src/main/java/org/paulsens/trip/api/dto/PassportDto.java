package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/**
 * Passport details on the wire.
 *
 * <p>Only ever present on a {@link PersonDto} whose {@code AccessLevel} allows travel documents; the whole object
 * is dropped otherwise, rather than sent with blanked fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PassportDto(
        String number, String country, LocalDate expires, LocalDate issued, String placeOfBirth) {
}
