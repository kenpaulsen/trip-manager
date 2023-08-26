package org.paulsens.tckt.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;
import lombok.Value;
import org.paulsens.tckt.dao.FilesystemPersistence;
import org.paulsens.trip.util.RandomData;

@Value
public class User {
    Id id;
    String name;
    String pass;
    Type type;

    @JsonCreator
    public User(
            @JsonProperty("id") final Id id,
            @JsonProperty("name") final String name,
            @JsonProperty("pass") final String pass,
            @JsonProperty("type") final Type type) {
        this.id = (id == null) ? Id.newId() : id;
        this.name = name;
        this.pass = (pass == null) ? RandomData.genAlpha(6) : pass;
        this.type = (type == null) ? type : Type.STUDENT;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("You must provide a name!");
        }
    }

    public User(final String name, final Type type) {
        this(null, name, null, type);
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
