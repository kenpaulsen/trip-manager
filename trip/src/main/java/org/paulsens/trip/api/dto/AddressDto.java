package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A postal address on the wire. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddressDto(String street, String city, String state, String zip) {
}
