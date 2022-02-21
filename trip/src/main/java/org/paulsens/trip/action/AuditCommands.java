package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;

@Slf4j
@Named("audit")
@ApplicationScoped
public class AuditCommands {
    /**
     * This writes an audit message to the audit file.
     *
     * @param userEmail The user (email) this message pertains to.
     * @param auditType The TYPE of message (i.e. LOGIN, CREATE_CREDS, etc). This can be any String to classify msg.
     * @param msg       The "message" to write to the audit log.
     */
    public void log(final String userEmail, final String auditType, String msg) {
        Audit.log(userEmail, auditType, msg);
    }
}
