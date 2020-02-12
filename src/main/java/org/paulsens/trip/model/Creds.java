package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import lombok.Data;

@Data
public class Creds implements Serializable {
    String email;
    String userId;
    String priv;
    String pass;

    @JsonCreator
    public Creds(
            @JsonProperty("email") String email,
            @JsonProperty("userId") String userId,
            @JsonProperty("priv") String priv,
            @JsonProperty("pass") String pass) {
        this.email = email;
        this.userId = userId;
        this.priv = priv;
        this.pass = pass;
    }
}
