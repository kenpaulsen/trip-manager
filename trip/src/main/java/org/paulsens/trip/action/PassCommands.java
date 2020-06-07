package org.paulsens.trip.action;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;

@Slf4j
@Named("pass")
@ApplicationScoped
public class PassCommands {
    public Creds login(final String email, final String pass) {
        final Creds creds = getCreds(email, pass);
        if (creds != null) {
            // login successful
            final String prevUpdateTime = Audit.formatEpochSeconds(DynamoUtils.getInstance().updateLastLogin(creds));
            Audit.log(email, "LOGIN", "User " + email + " logged in, previous login was: " + prevUpdateTime);
        } else {
            Audit.log(email, "LOGIN", "Login Failed!");
        }
        return creds;
    }

    public Creds getCreds(final String email, final String pass) {
        return DynamoUtils.getInstance().getCredsByEmailAndPass(email, pass)
                .exceptionally(ex -> {
                    log.error("Failed to get creds for: " + email, ex);
                    return null;
                }).join();
    }

    /**
     * Tests to see if {@code userId} has access to {@code reqId}.
     * @param person    The user whom is requesting access.
     * @param reqId     The id to test for access.
     * @return  {@code true} if {@code userId} can access {@code reqId}.
     */
    public boolean canAccessUserId(final Person person, final String reqId) {
        return person.getId().equals(reqId) || person.getManagedUsers().contains(reqId);
    }
}
