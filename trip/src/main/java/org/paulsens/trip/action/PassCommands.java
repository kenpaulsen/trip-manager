package org.paulsens.trip.action;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Creds;

@Slf4j
@Named("pass")
@ApplicationScoped
public class PassCommands {
    public Creds getCreds(final String email, final String pass) {
        return DynamoUtils.getInstance().getCredsByEmailAndPass(email, pass)
                .exceptionally(ex -> {
                    log.error("Failed to get creds for: " + email, ex);
                    return null;
                }).join();
    }
}
