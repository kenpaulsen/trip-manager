package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * A trip as ANYONE may see it: the one shape Discover sends (medjugorje#155), to a caller with no account, across
 * organizations. Every component here is something the trip's organization already shows an anonymous visitor
 * on its own site; {@code docs/discover-tenancy-review.md} says where, field by field.
 *
 * <p><b>This record is the redaction.</b> It is built by {@code DiscoverTripMapper} from values handed to it one
 * by one, never from {@code TripDto} or {@code TripMapper}, so a field added to {@code Trip} cannot reach an
 * anonymous caller by being mapped along. Adding a component here is a tenancy decision:
 * {@code DiscoverTripDtoAllowListTest} pins the component names and fails until its list, and the tenancy review,
 * say the new one is public.
 *
 * <p>{@code title} is the trip's short title (no sort prefix). {@code startDate} and {@code endDate} are plain
 * dates. {@code countries} are the countries the trip visits, ISO 3166-1 alpha-2 codes in the trip's own order,
 * empty for a trip that names none; there is no single {@code country} here (that field exists on {@code TripDto}
 * only for clients older than this shape). {@code tags} and {@code tagLabels} run side by side and hold only the
 * tags the platform list still offers. {@code kind} and {@code language} are enum names
 * ({@code PILGRIMAGE}, {@code CONFERENCE}; {@code English}, {@code Spanish}). {@code priceText} is the
 * organizer's free text, never a number to compute with. {@code pictures} has the cover first and is empty,
 * never null, for a trip with none. {@code orgAbbreviation} is the organization's short label, trimmed, and
 * absent when it set none. {@code orgLogoUrl} is present only for a logo the organization uploaded here.
 * {@code detailsUrl} is the trip's page on its organization's own site. {@code registration} is {@code open},
 * {@code full}, {@code closed} or {@code notOpen}: the phase alone, never a count of seats or people.
 * {@code registrationOpens} is the day a {@code notOpen} trip's registration opens, and absent otherwise.
 * {@code registersOnWeb} is true for a trip whose registration happens on another website, so a client hands
 * the person to {@code detailsUrl} rather than to registration in the app. {@code photoCount} is how many photos
 * the trip's chat album shows the public (visible rows only; {@code GET /api/discover/trips/{id}/photos} lists
 * them), 0 for none.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoverTripDto(
        String id,
        String title,
        LocalDate startDate,
        LocalDate endDate,
        String destination,
        List<String> countries,
        List<String> tags,
        List<String> tagLabels,
        String kind,
        String language,
        String priceText,
        List<TripPictureDto> pictures,
        String orgId,
        String orgName,
        String orgAbbreviation,
        String orgLogoUrl,
        String detailsUrl,
        String registration,
        LocalDate registrationOpens,
        boolean registersOnWeb,
        int photoCount) {

    /** The same trip with its public album's size: counted by the index, after the mapper's allow-list. */
    public DiscoverTripDto withPhotoCount(final int count) {
        return new DiscoverTripDto(id, title, startDate, endDate, destination, countries, tags, tagLabels, kind,
                language, priceText, pictures, orgId, orgName, orgAbbreviation, orgLogoUrl, detailsUrl, registration,
                registrationOpens, registersOnWeb, count);
    }
}
