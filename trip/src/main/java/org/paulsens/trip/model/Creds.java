package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import lombok.Data;

@Data
public final class Creds implements Serializable {
    private String email;
    private Person.Id userId;
    private String priv;
    private String pass;
    private Long   lastLogin;

    @JsonCreator
    public Creds(
            @JsonProperty("email") String email,
            @JsonProperty("userId") Person.Id userId,
            @JsonProperty("priv") String priv,
            @JsonProperty("pass") String pass,
            @JsonProperty("lastLogin") Long lastLogin) {
        this.email = email;
        this.userId = userId;
        this.priv = priv;
        this.pass = pass;
        this.lastLogin = lastLogin;
    }
}