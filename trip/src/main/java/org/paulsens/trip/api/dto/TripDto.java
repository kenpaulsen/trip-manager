package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A trip.
 *
 * <p>The roster ({@code people}) is ids only. Resolving them to names here would make every trip fetch a bulk
 * disclosure of the roster, redacted by nothing; a client that needs names asks {@code /api/people/{id}}, which
 * redacts per viewer.
 *
 * <p>{@code tripEvents} is absent unless the caller asked for the itinerary, so the common "list my trips" call
 * does not drag every event of every trip across the wire.
 *
 * <p>{@code paymentConfig} (2026-11) is the trip's own payment settings: writable by a trip manager on
 * {@code POST}/{@code PUT}, read back on a single-trip {@code GET} by a trip manager only, absent from lists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripDto(
        String id,
        String title,
        String description,
        Boolean openToPublic,
        boolean chatEnabled,
        LocalDateTime startDate,
        LocalDateTime endDate,
        Integer regLimit,
        String provider,
        String orgId,
        String language,
        String estimatedPrice,
        String director,
        String localGuide,
        String facilitators,
        String flyerUrl,
        String nonHostedTripUrl,
        Integer nonHostedRegNumber,
        List<RegOptionDto> regOptions,
        List<String> people,
        List<TripEventDto> tripEvents,
        String kind,
        TripPaymentConfigDto paymentConfig) {

    /**
     * The shape before {@code kind} existed (2026-11): kept so a client and the tests that build a body without
     * a kind still compile; a null kind on a write leaves the trip's kind alone.
     */
    public TripDto(final String id, final String title, final String description, final Boolean openToPublic,
            final boolean chatEnabled, final LocalDateTime startDate, final LocalDateTime endDate,
            final Integer regLimit, final String provider, final String orgId, final String language,
            final String estimatedPrice, final String director, final String localGuide, final String facilitators,
            final String flyerUrl, final String nonHostedTripUrl, final Integer nonHostedRegNumber,
            final List<RegOptionDto> regOptions, final List<String> people, final List<TripEventDto> tripEvents) {
        this(id, title, description, openToPublic, chatEnabled, startDate, endDate, regLimit, provider, orgId,
                language, estimatedPrice, director, localGuide, facilitators, flyerUrl, nonHostedTripUrl,
                nonHostedRegNumber, regOptions, people, tripEvents, null, null);
    }

    /** The shape with a kind but no payment settings (2026-11), for the same reason. */
    public TripDto(final String id, final String title, final String description, final Boolean openToPublic,
            final boolean chatEnabled, final LocalDateTime startDate, final LocalDateTime endDate,
            final Integer regLimit, final String provider, final String orgId, final String language,
            final String estimatedPrice, final String director, final String localGuide, final String facilitators,
            final String flyerUrl, final String nonHostedTripUrl, final Integer nonHostedRegNumber,
            final List<RegOptionDto> regOptions, final List<String> people, final List<TripEventDto> tripEvents,
            final String kind) {
        this(id, title, description, openToPublic, chatEnabled, startDate, endDate, regLimit, provider, orgId,
                language, estimatedPrice, director, localGuide, facilitators, flyerUrl, nonHostedTripUrl,
                nonHostedRegNumber, regOptions, people, tripEvents, kind, null);
    }

    /** The same trip without its itinerary or payment settings, for list responses. */
    public TripDto withoutEvents() {
        return new TripDto(id, title, description, openToPublic, chatEnabled, startDate, endDate, regLimit,
                provider, orgId, language, estimatedPrice, director, localGuide, facilitators, flyerUrl,
                nonHostedTripUrl, nonHostedRegNumber, regOptions, people, null, kind, null);
    }

    /** The same trip without its payment settings, for a reader who is not one of its managers. */
    public TripDto withoutPaymentConfig() {
        return new TripDto(id, title, description, openToPublic, chatEnabled, startDate, endDate, regLimit,
                provider, orgId, language, estimatedPrice, director, localGuide, facilitators, flyerUrl,
                nonHostedTripUrl, nonHostedRegNumber, regOptions, people, tripEvents, kind, null);
    }
}
