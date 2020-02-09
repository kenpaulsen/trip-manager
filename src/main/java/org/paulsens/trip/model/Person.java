package org.paulsens.trip.model;

import java.time.LocalDate;
import lombok.Value;

@Value
public class Person {
    String first;
    String middle;
    String last;
    LocalDate birthdate;

    String cell;
    String email;
    String tsa;

    Address address;
    Passport passport;

    String notes;
}
