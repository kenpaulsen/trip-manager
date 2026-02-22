package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.util.Util;

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
    public void log(final String userEmail, final String auditType, final String msg) {
        Audit.log(
                Util.orDefault(userEmail, ""),
                Util.orDefault(auditType, ""),
                Util.orDefault(msg, "[no message]"));
    }
}
