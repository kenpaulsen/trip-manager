package org.paulsens.tckt.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Value;
import lombok.With;
import org.paulsens.tckt.dao.FilesystemPersistence;

@Value
public class Ticket {
    Id id;
    User.Id owner;
    @With
    LocalDate date;     // In case you want to assign a date to the Ticket
    @With
    String title;

    @JsonCreator
    public Ticket(
            @JsonProperty("id") final Id id,
            @JsonProperty("owner") final User.Id owner,
            @JsonProperty("date") final LocalDate date,
            @JsonProperty("title") final String title) {
        this.id = id == null ? Id.newId() : id;
        this.owner = owner;
        this.date = date == null ? LocalDate.now() : date;
        this.title = title;
    }

    public Ticket(final User.Id owner, final LocalDate date, final String title) {
        this(null, owner, date, title);
    }

    @Value
    public static class Id implements org.paulsens.tckt.model.Id {
        @JsonValue
        String value;

        public static Id newId() {
            return new Id(UUID.randomUUID().toString());
        }

        public static Ticket resolve(final FilesystemPersistence persistence, final String id) {
            return persistence.getTicket(null, new Ticket.Id(id));
        }

        @Override
        public BindingType getType() {
            return BindingType.TICKET;
        }
    }
}
