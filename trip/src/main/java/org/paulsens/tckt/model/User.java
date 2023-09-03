package org.paulsens.tckt.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.Value;
import org.paulsens.tckt.dao.FilesystemPersistence;
import org.paulsens.trip.util.RandomData;

@Data
public class User implements Serializable {
    @Setter(AccessLevel.NONE)
    Id id;
    String first;
    String last;
    String pass;
    Type type;

    @JsonCreator
    public User(
            @JsonProperty("id") final Id id,
            @JsonProperty("first") final String first,
            @JsonProperty("last") final String last,
            @JsonProperty("pass") final String pass,
            @JsonProperty("type") final Type type) {
        this.id = (id == null) ? Id.newId() : id;
        this.first = first;
        this.last = last;
        this.pass = (pass == null) ? RandomData.genAlpha(5).toLowerCase(Locale.US) : pass;
        this.type = (type == null) ? Type.STUDENT : type;
        if (first == null || first.isBlank() || last == null || last.isBlank()) {
            throw new IllegalArgumentException("You must provide first and last name!");
        }
    }

    public User(final String first, final String last, final Type type) {
        this(null, first, last, null, type);
    }

    @JsonIgnore
    public boolean isAdmin() {
        return Type.ADMIN == type;
    }

    public boolean isType(final Object reqRole) {
        return reqRole == null || reqRole instanceof Type ? reqRole == type : Type.valueOf(reqRole.toString()) == type;
    }

    @Value
    public static class Id implements org.paulsens.tckt.model.Id {
        @JsonValue
        String value;

        public static Id newId() {
            return new Id(UUID.randomUUID().toString());
        }

        public static User resolve(final FilesystemPersistence persistence, final String id) {
            return persistence.getUser(null, new User.Id(id));
        }

        @Override
        public BindingType getType() {
            return BindingType.USER;
        }
    }

    public enum Type {
        STUDENT, ADMIN;
    }
}
