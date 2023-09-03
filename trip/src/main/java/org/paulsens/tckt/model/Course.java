package org.paulsens.tckt.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.Year;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.Value;
import lombok.experimental.NonFinal;
import org.paulsens.tckt.dao.FilesystemPersistence;

@Value
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Course implements Serializable {
    @EqualsAndHashCode.Include
    Id id;
    @NonFinal @Setter
    String name;
    User.Id teacherId;
    @NonFinal @Setter
    Year year;

    @JsonCreator
    public Course(
            @JsonProperty("id") final Id id,
            @JsonProperty("name") final String name,
            @JsonProperty("teacherId") final User.Id teacherId,
            @JsonProperty("year") final Year year) {
        this.id = id == null ? Id.newId() : id;
        this.name = name;
        this.teacherId = teacherId;
        this.year = year == null ? Year.now() : year;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("You must provide a name!");
        }
    }

    public Course(final String name, final User.Id teacherId, final Year year) {
        this(null, name, teacherId, year);
    }

    @Value
    public static class Id implements org.paulsens.tckt.model.Id {
        @JsonValue
        String value;

        public static Id newId() {
            return new Id(UUID.randomUUID().toString());
        }

        public static Course resolve(final FilesystemPersistence persistence, final String id) {
            return persistence.getCourse(null, new Course.Id(id));
        }

        @Override
        public BindingType getType() {
            return BindingType.COURSE;
        }
    }
}
