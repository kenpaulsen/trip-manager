package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.List;
import lombok.Value;

@Value
public class Privilege implements Serializable {
    String name;
    String description;
    List<Person.Id> people;

    @JsonCreator
    public Privilege(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("people") List<Person.Id> people) {
        this.name = name;
        this.description = description;
        this.people = (people == null) ? List.of() : people;
    }
}