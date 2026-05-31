package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.util.StdConverter;
import com.sun.jsft.util.Util;
import jakarta.faces.context.FacesContext;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.paulsens.trip.dynamo.DAO;

@Data
@Builder
@AllArgsConstructor
public final class Trip implements Serializable {
    @JsonProperty("id")
    private String id;   // Trip ID
    @JsonProperty("title")
    private String title;                               // Title of trip
    @JsonProperty("openToPublic")
    private Boolean openToPublic;                       // True if people can register themselves
    @JsonProperty("description")
    private String description;                         // Describes the trip
    @JsonProperty("startDate")
    private LocalDateTime startDate;                    // Start of trip
    @JsonProperty("endDate")
    private LocalDateTime endDate;                      // End of trip
    @JsonProperty("people")
    private List<Person.Id> people;                     // UserIds
    @JsonProperty("regLimit")
    private Integer regLimit;                           // Number of people allowed on the trip (soft limit)
    @JsonProperty("provider")
    private String provider;                            // Who is offering the pilgrimage (i.e. "CFPW")
    @JsonProperty("lang")
    private Language language;                          // Language of the pilgrimage
    @JsonProperty("estPrice")
    private String estimatedPrice;                      // Estimated price (non-binding)
    @JsonProperty("director")
    private String director;                            // The leader of the trip (i.e. Spiritual Director)
    @JsonProperty("guide")
    private String localGuide;                          // The local guide of the trip
    @JsonProperty("facilitators")
    private String facilitators;                        // The facilitators or "organizers" of the trip;
    @JsonProperty("nonHostedTripUrl")
    private String nonHostedTripUrl;                    // For non-hosted trips, a URL for more info
    @JsonProperty("nonHostedRegNumber")
    private Integer nonHostedRegNumber;                 // For non-hosted trips, the number of people enrolled
    @JsonProperty("tripEvents")
    @JsonSerialize(converter = TripEventsSerializer.class)
    @JsonDeserialize(converter = TripEventsDeserializer.class)
    private List<TripEvent> tripEvents;                 // The stuff needed to book, airfare, hotel, etc.
    @JsonProperty("regOptions")
    private List<RegistrationOption> regOptions;        // Registration page questions
    @JsonProperty("admissionOptions")
    private List<AdmissionOption> admissionOptions;     // Priced conference admission choices (optional)
    @JsonProperty("childPriceCap")
    private BigDecimal childPriceCap;                   // Max charged for a child; null = no child cap
    @JsonProperty("discountCodes")
    private List<DiscountCode> discountCodes;           // Discount codes a registrant may apply (optional)

    private Trip() {
    }

    public List<Person.Id> getPeople() {
        if (people == null) {
            people = new ArrayList<>();
        }
        return people;
    }

    public void setPeople(final List<Person.Id> people) {
        this.people = new ArrayList<>(people);
    }

    public List<TripEvent> getTripEvents() {
        if (tripEvents == null) {
            tripEvents = new ArrayList<>();
        }
        return tripEvents;
    }

    public void setTripEvents(final List<TripEvent> events) {
        this.tripEvents = new ArrayList<>(events);
    }

    public List<RegistrationOption> getRegOptions() {
        if (regOptions == null) {
            regOptions = new ArrayList<>();
        }
        return regOptions;
    }

    public void setRegOptions(final List<RegistrationOption> options) {
        this.regOptions = new ArrayList<>(options);
    }

    public List<AdmissionOption> getAdmissionOptions() {
        if (admissionOptions == null) {
            admissionOptions = new ArrayList<>();
        }
        return admissionOptions;
    }

    public void setAdmissionOptions(final List<AdmissionOption> options) {
        this.admissionOptions = (options == null) ? new ArrayList<>() : new ArrayList<>(options);
    }

    /** @return the admission option with the given id, or {@code null} if none / id is null. */
    @JsonIgnore
    public AdmissionOption getAdmissionOption(final String optId) {
        if (optId == null) {
            return null;
        }
        return getAdmissionOptions().stream().filter(o -> optId.equals(o.getId())).findAny().orElse(null);
    }

    public List<DiscountCode> getDiscountCodes() {
        if (discountCodes == null) {
            discountCodes = new ArrayList<>();
        }
        return discountCodes;
    }

    public void setDiscountCodes(final List<DiscountCode> codes) {
        this.discountCodes = (codes == null) ? new ArrayList<>() : new ArrayList<>(codes);
    }

    /** @return the discount code with the given id, or {@code null} if none / id is null. */
    @JsonIgnore
    public DiscountCode getDiscountCode(final String codeId) {
        if (codeId == null) {
            return null;
        }
        return getDiscountCodes().stream().filter(c -> codeId.equals(c.getId())).findAny().orElse(null);
    }

    /**
     * Returns {@code true} if the the given person can join this Trip. This requires the Person to not already be on
     * the trip, and for the trip to not yet be started.
     */
    public boolean canJoin(final Person.Id userId) {
        return !people.contains(userId) && startDate.isAfter(LocalDateTime.now());
    }

    public String addTripEvent(
            final TripEvent.Type type,
            final String title,
            final String notes,
            final LocalDateTime start,
            final LocalDateTime end) {
        final List<TripEvent> events = getTripEvents();
        // Very simple validation check...
        events.stream().filter(te -> matchingTE(te, title, start)).findAny().ifPresent(te -> {
            throw new IllegalStateException(
                    "Trip Event with title (" + title + ") and date (" + start + ") already exists!");
        });
        // Add it
        final String id = UUID.randomUUID().toString();
        events.add(new TripEvent(id, type, title, notes, start, end, null, null));
        events.sort(Comparator.comparing(TripEvent::getStart));
        return id;
    }

    @JsonIgnore
    public TripEvent getTripEvent(final String teId) {
        return getTripEvents().stream().filter(e -> e.getId().equals(teId)).findAny().orElse(null);
    }

    @JsonIgnore
    public List<TripEvent> getTripEventsForUser(final Person.Id userId) {
        return getTripEvents().stream().filter(te -> te.getParticipants().contains(userId)).toList();
    }

    @JsonIgnore
    public String getTripDateRange() {
        final Locale locale = Util.getLocale(FacesContext.getCurrentInstance());
        final String startMonth = startDate.getMonth().getDisplayName(TextStyle.SHORT, locale) + ' ';
        final String endMonth = (startDate.getMonth() == endDate.getMonth()) ? "" :
                endDate.getMonth().getDisplayName(TextStyle.SHORT, locale) + ' ';
        return startMonth + startDate.getDayOfMonth() + " - "
                + endMonth + endDate.getDayOfMonth() + ", " + endDate.getYear();
    }

    @JsonIgnore
    public void deleteTripEvent(final TripEvent te) {
        getTripEvents().remove(te);
    }

    public void editTripEvent(final TripEvent newTE) {
        final List<TripEvent> events = getTripEvents();
        // Ensure we have the TripEvent to edit
        final TripEvent oldTE = events.stream().filter(e -> e.getId().equals(newTE.getId())).findAny()
                .orElseThrow(() -> new IllegalArgumentException("TripEvent id (" + newTE.getId() + ") not found!"));
        events.remove(oldTE);
        events.add(newTE);
        events.sort(Comparator.comparing(TripEvent::getStart));
    }

    public void addTripOption() {
        getRegOptions().add(
                new RegistrationOption(getRegOptions().size(), "New Option", "New Option Description", false));
    }

    public void addAdmissionOption() {
        getAdmissionOptions().add(new AdmissionOption(
                "opt" + getAdmissionOptions().size(), "New Admission Option", BigDecimal.ZERO,
                new ArrayList<>(), false, false));
    }

    public void addDiscountCode() {
        getDiscountCodes().add(new DiscountCode(
                "code" + getDiscountCodes().size(), "NEWCODE", "New Discount Code", null,
                DiscountCode.DiscountType.DISCOUNT_BY, BigDecimal.ZERO, new ArrayList<>(), false));
    }

    private boolean matchingTE(final TripEvent te, final String title, final LocalDateTime date) {
        return title.equals(te.getTitle()) && date.equals(te.getStart());
    }

    public static class TripBuilder {
        // Set TripBuilder values here to provide a default values
        private String id = UUID.randomUUID().toString();   // Trip ID
        private Boolean openToPublic = Boolean.TRUE;        // True if people can register themselves
        private LocalDateTime startDate = LocalDateTime.now().plusDays(90);     // Start of trip
        private LocalDateTime endDate = LocalDateTime.now().plusDays(100);      // Start of trip
        private List<Person.Id> people = new ArrayList<>();
        private List<TripEvent> tripEvents = new ArrayList<>();
        private List<RegistrationOption> regOptions = new ArrayList<>();
        private List<AdmissionOption> admissionOptions = new ArrayList<>();
        private List<DiscountCode> discountCodes = new ArrayList<>();

        public TripBuilder id(final String id) {
            this.id = (id == null) ? UUID.randomUUID().toString() : id;
            return this;
        }
        public TripBuilder openToPublic(final Boolean isOpen) {
            this.openToPublic = (isOpen == null) ? Boolean.FALSE : isOpen;
            return this;
        }
        public TripBuilder startDate(final LocalDateTime date) {
            this.startDate = (date == null) ? LocalDateTime.now().plusDays(90) : date;
            return this;
        }
        public TripBuilder endDate(final LocalDateTime date) {
            this.endDate = (date == null) ? LocalDateTime.now().plusDays(100) : date;
            return this;
        }
        public TripBuilder people(final List<Person.Id> people) {
            this.people = (people == null) ? new ArrayList<>() : new ArrayList<>(people);
            return this;
        }
        public TripBuilder tripEvents(final List<TripEvent> tripEvents) {
            this.tripEvents = (tripEvents == null) ? new ArrayList<>() : new ArrayList<>(tripEvents);
            return this;
        }
        public TripBuilder regOptions(final List<RegistrationOption> regOptions) {
            this.regOptions = (regOptions == null) ? new ArrayList<>() : new ArrayList<>(regOptions);
            return this;
        }
        public TripBuilder admissionOptions(final List<AdmissionOption> admissionOptions) {
            this.admissionOptions =
                    (admissionOptions == null) ? new ArrayList<>() : new ArrayList<>(admissionOptions);
            return this;
        }
        public TripBuilder discountCodes(final List<DiscountCode> discountCodes) {
            this.discountCodes =
                    (discountCodes == null) ? new ArrayList<>() : new ArrayList<>(discountCodes);
            return this;
        }
    }

    static class TripEventsSerializer extends StdConverter<List<TripEvent>, List<String>> {
        @Override
        public List<String> convert(final List<TripEvent> events) {
            if (events == null) {
                return Collections.emptyList();
            }
            return events.stream().map(TripEvent::getId).collect(Collectors.toList());
        }
    }

    static class TripEventsDeserializer extends StdConverter<List<String>, List<TripEvent>> {
        @Override
        public List<TripEvent> convert(final List<String> ids) {
            if (ids == null) {
                return Collections.emptyList();
            }
            final CompletableFuture<?>[] tes = ids.stream()
                    .map(DAO.getInstance()::getTripEvent)
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(tes).thenApply(v -> Arrays.stream(tes)
                    .map(fut -> (TripEvent) fut.join())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()))
                    .join();
        }
    }
}