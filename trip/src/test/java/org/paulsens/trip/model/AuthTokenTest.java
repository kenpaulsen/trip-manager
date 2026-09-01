package org.paulsens.trip.model;

import org.testng.Assert;
import org.testng.annotations.Test;

public class AuthTokenTest {

    /** The repo-wide builder rule: builder defaults must equal the no-arg constructor's defaults. */
    @Test
    public void builderDefaultsMatchTheNoArgConstructor() {
        Assert.assertEquals(AuthToken.builder().build(), new AuthToken());
    }

    @Test
    public void kindsAndScopesRoundTripByName() {
        for (final AuthToken.Kind kind : AuthToken.Kind.values()) {
            Assert.assertEquals(AuthToken.Kind.valueOf(kind.name()), kind);
        }
        for (final AuthToken.Scope scope : AuthToken.Scope.values()) {
            Assert.assertEquals(AuthToken.Scope.valueOf(scope.name()), scope);
        }
    }
}
