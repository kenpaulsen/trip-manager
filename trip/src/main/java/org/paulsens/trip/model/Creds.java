package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.Locale;
import lombok.Data;

@Data
public final class Creds implements Serializable {
    public static final String USER_PRIV = "user";
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
        this.email = email.toLowerCase(Locale.getDefault());
        this.userId = userId;
        this.priv = priv;
        this.pass = pass;
        this.lastLogin = lastLogin;
    }

    public Creds(final String email, final Person.Id id, final String pass) {
        this(email.toLowerCase(Locale.getDefault()), id, USER_PRIV, pass, null);
    }
}