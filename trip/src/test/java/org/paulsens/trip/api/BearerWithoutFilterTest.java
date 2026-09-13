package org.paulsens.trip.api;

import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.TokenPrincipal;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * A resource that the name-bound auth filter never touches ({@code PhotoChatResource}) still knows a bearer
 * caller: the native client comments on photos with a token, not a cookie, and the first build answered
 * 401 to it because {@code personId()} only looked at the filter's attribute and the session.
 */
public class BearerWithoutFilterTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("bearer-no-filter");

    /** The smallest resource there is: identity only. */
    private static final class Probe extends BaseResource {
        @Override
        protected String versionedType() {
            return ApiMediaTypes.PHOTO_CHAT_V1;
        }

        Person.Id whoAmI() {
            return personId();
        }
    }

    @Test
    public void aBearerPrincipalIsTheCallerEvenWithoutTheFilter() {
        final TokenPrincipal principal = new TokenPrincipal(ME, "user2@example.com", "user",
                org.paulsens.trip.model.AuthToken.Scope.MEMBER, "sel");
        bearerWithoutTheFilter(principal);
        final Probe probe = resource(new Probe());
        Assert.assertEquals(probe.whoAmI(), ME);
        Assert.assertFalse(probe.csrfMissing(null), "a bearer caller never needs the CSRF sentinel");
    }

    @Test
    public void nobodyIsStillNobody() {
        anonymous();
        final Probe probe = resource(new Probe());
        Assert.assertThrows(jakarta.ws.rs.NotAuthorizedException.class, probe::whoAmI);
    }
}
