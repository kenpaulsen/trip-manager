package org.paulsens.trip.api.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.paulsens.trip.action.BrandCommands;
import org.paulsens.trip.action.BrandingPhotos;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.IsoCountries;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.action.TripPictureCommands;
import org.paulsens.trip.action.TripTags;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.api.dto.TripPictureDto;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.paulsens.trip.site.SiteUrls;

/**
 * {@code Trip} to its PUBLIC wire shape ({@link DiscoverTripDto}), written by hand and on purpose not a MapStruct
 * mapper, and never by way of {@code TripMapper} or {@code TripDto}.
 *
 * <p>Discover answers a caller with no account, across organizations, so what it may say is an allow-list, not a
 * redaction: {@link #toDto} names each value it copies, and a field it does not name cannot leave. A generated
 * mapper copies every property whose name matches, which is the opposite rule; a field added to {@code Trip} and
 * to a DTO would cross organizations without anyone deciding it should. {@code DiscoverTripDtoAllowListTest}
 * holds a trip with a sentinel in every private field against this class.
 *
 * <p>An instance carries one rebuild's lookups (the offered tags, the beans that spell picture and logo URLs),
 * read once so a listing of many trips does not repeat them; {@link #toDto} is the pure half.
 */
public final class DiscoverTripMapper {

    private final List<TripTags.Tag> offeredTags;
    private final TripPictureCommands pictures;
    private final BrandCommands brand;
    private final BrandingPhotos brandingPhotos;
    private final TripPageCommands pages;
    private final ConfigCommands config;
    private final boolean local;
    /** An organization's logo is asked once per rebuild: a listing repeats the same few organizations. */
    private final Map<String, Optional<String>> logoByOrg = new HashMap<>();

    DiscoverTripMapper(final List<TripTags.Tag> offeredTags, final TripPictureCommands pictures,
            final BrandCommands brand, final BrandingPhotos brandingPhotos, final TripPageCommands pages,
            final ConfigCommands config, final boolean local) {
        this.offeredTags = offeredTags;
        this.pictures = pictures;
        this.brand = brand;
        this.brandingPhotos = brandingPhotos;
        this.pages = pages;
        this.config = config;
        this.local = local;
    }

    /** A mapper on the running application's beans and settings, for one rebuild of the Discover index. */
    public static DiscoverTripMapper live() {
        return new DiscoverTripMapper(TripTags.current(), Beans.get(TripPictureCommands.class),
                Beans.get(BrandCommands.class), Beans.get(BrandingPhotos.class), new TripPageCommands(),
                new ConfigCommands(), LocalMode.isLocal());
    }

    /**
     * The trip as anyone may see it. URLs come out as the beans spell them: absolute in production (the CDN, the
     * organization's site), site-relative in local mode, where {@link #absolute} finishes them per request.
     */
    public DiscoverTripDto map(final Trip trip, final Organization org) {
        final TripPageCommands.RegistrationInfo registration = pages.registrationInfo(trip);
        return toDto(trip, org, TripTags.shownOn(trip, offeredTags), pictureDtos(trip), logoOf(org),
                detailsUrl(trip), registration.phase(), registration.opensOn());
    }

    /**
     * The allow-list itself: each component is one named, public value. Nothing here reads the roster, the staff,
     * the registration questions, the payment settings, the limits, the flyer or the external URL; the one fact
     * taken from the external URL is that there IS one ({@code registersOnWeb}), and from the seat count only the
     * phase it produces, and from the registration window only the day a not-yet-open one opens.
     */
    public static DiscoverTripDto toDto(final Trip trip, final Organization org, final List<TripTags.Tag> shownTags,
            final List<TripPictureDto> pictures, final String orgLogoUrl, final String detailsUrl,
            final TripPageCommands.Phase phase, final LocalDate opensOn) {
        return new DiscoverTripDto(
                trip.getId(),
                trip.getShortTitle(),
                dateOf(trip.getStartDate()),
                dateOf(trip.getEndDate()),
                blankToNull(trip.getDestination()),
                IsoCountries.normalizeAll(trip.getCountries()),
                shownTags.stream().map(TripTags.Tag::id).toList(),
                shownTags.stream().map(TripTags.Tag::label).toList(),
                trip.getKind().name(),
                TripCommands.languageOf(trip).name(),
                blankToNull(trip.getEstimatedPrice()),
                List.copyOf(pictures),
                org.getId().getValue(),
                org.getName(),
                blankToNull(org.getAbbreviation()),
                blankToNull(orgLogoUrl),
                detailsUrl,
                phase.wireName(),
                phase == TripPageCommands.Phase.NOT_OPEN ? opensOn : null,
                trip.isExternal());
    }

    /**
     * The same trip with every URL absolute for this request ({@code BaseResource.absoluteUrl}): a no-op in
     * production, where they already are, and what makes a local-mode answer usable by a phone, which cannot
     * resolve a site-relative path.
     */
    public static DiscoverTripDto absolute(final DiscoverTripDto dto, final UnaryOperator<String> absoluteUrl) {
        return new DiscoverTripDto(dto.id(), dto.title(), dto.startDate(), dto.endDate(), dto.destination(),
                dto.countries(), dto.tags(), dto.tagLabels(), dto.kind(), dto.language(), dto.priceText(),
                dto.pictures().stream().map(picture -> absolute(picture, absoluteUrl)).toList(),
                dto.orgId(), dto.orgName(), dto.orgAbbreviation(), absoluteUrl.apply(dto.orgLogoUrl()),
                absoluteUrl.apply(dto.detailsUrl()), dto.registration(), dto.registrationOpens(), dto.registersOnWeb());
    }

    private static TripPictureDto absolute(final TripPictureDto picture, final UnaryOperator<String> absoluteUrl) {
        return new TripPictureDto(picture.key(), absoluteUrl.apply(picture.url()),
                absoluteUrl.apply(picture.url480()), absoluteUrl.apply(picture.url960()), picture.alt(),
                picture.width(), picture.height(), picture.placeholder());
    }

    private List<TripPictureDto> pictureDtos(final Trip trip) {
        return trip.getPictures().stream().map(this::pictureDto).toList();
    }

    private TripPictureDto pictureDto(final TripPicture picture) {
        return TripPictureDto.of(picture, width -> pictures.url(picture, width));
    }

    /**
     * The organization's logo, only when it is a file the organization uploaded here
     * ({@link BrandingPhotos#isUploadOf}). The logo setting also takes a typed URL, and Discover is drawn for
     * people who never chose this organization: a typed one would make every such phone call a host only that
     * organization picked (the trip cards' rule, {@code TripCardCommands.brandImageOf}).
     */
    private String logoOf(final Organization org) {
        return logoByOrg.computeIfAbsent(org.getId().getValue(), id -> Optional.ofNullable(uploadedLogoOf(org)))
                .orElse(null);
    }

    private String uploadedLogoOf(final Organization org) {
        return brandingPhotos.firstUploadOf(org.getId().getValue(), brand.lookOf(org).logoUrl());
    }

    /**
     * The trip's page on its ORGANIZATION's own site: {@link SiteUrls#tripPageUrl}, the rule the signed-in trips
     * API follows. Local mode keeps the site-relative path (there is no request here to make it absolute on);
     * {@link #absolute} finishes it per request.
     */
    private String detailsUrl(final Trip trip) {
        return SiteUrls.tripPageUrl(trip, local, UnaryOperator.identity(), config);
    }

    private static LocalDate dateOf(final LocalDateTime when) {
        return when == null ? null : when.toLocalDate();
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
