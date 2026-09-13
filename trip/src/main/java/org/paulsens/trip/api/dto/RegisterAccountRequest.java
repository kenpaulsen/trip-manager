package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/**
 * The native app's sign-up form: the same fields as {@code account/createAccount.xhtml}, plus the token
 * scope and label the app would otherwise send to {@code POST /api/auth/token} right afterwards.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterAccountRequest(
        String email,
        String nickname,
        String first,
        String middle,
        String last,
        String sex,
        String cell,
        LocalDate birthdate,
        String password,
        String scope,
        String label) {
}
