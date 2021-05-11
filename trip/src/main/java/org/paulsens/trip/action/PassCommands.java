package org.paulsens.trip.action;

import javax.enterprise.context.ApplicationScoped;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;

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
     * This should only be called for a Person that exists, but does NOT have creds in the db yet. See
     * createAccount.xhtml.
     *
     * @param email     The user's unique email address.
     * @param newPass   The new password to force-set.
     * @return The newly created Creds (which are also persisted to the db) or null if it fails.
     */
    public Creds createCreds(final String email, final String newPass) {
        final DynamoUtils dynamo = DynamoUtils.getInstance();
        final Creds creds = dynamo.createCreds(email).orElse(null);
        if (creds != null) {
            creds.setPass(newPass);
            dynamo.saveCreds(creds);
        }
        return creds;
    }

    /**
     * Warning this is a dangerous command that allows a password to be directly set. Do not allow the user to
     * set this email address directly.
     * @param email     The email address (login) to receive a new password.
     * @param pass      The new password.
     * @param pass2     The new password again (in case these 2 values come from a set password form).
     * @return  True if the password was set, False otherwise.
     */
    public Boolean setPass(final String email, final String pass, final String pass2) {
        if (!pass.equals(pass2)) {
            throw new IllegalArgumentException("Passwords do not match!");
        }
        final DynamoUtils dynamo = DynamoUtils.getInstance();
        final Person person = dynamo.getPersonByEmail(email).join();
        if (person == null) {
            throw new IllegalArgumentException("Check email address, and make sure you have registered.");
        }
        final Creds creds = new Creds(email, person.getId(), pass);
        return dynamo.saveCreds(creds).join();
    }

    /**
     * Tests to see if {@code userId} has access to {@code reqId}.
     * @param person    The user whom is requesting access.
     * @param reqId     The id to test for access.
     * @return  {@code true} if {@code userId} can access {@code reqId}.
     */
    public boolean canAccessUserId(final Person person, final Person.Id reqId) {
        return person.getId().equals(reqId) || person.getManagedUsers().contains(reqId);
    }

    /**
     * If the given {@code email} and {@code lastName} match a valid user, an email will be sent to the given email
     * with a new random password. The password database will be immediately changed to match the password in the
     * email.
     * @param email         The email to reset.
     * @param lastName      The last name associated with the given email, it must match to reset the password.
     * @param emailTitle    A title string to add as the first line of the email message.
     * @return  A text-formatted email message.
     */
    public String resetPass(final String email, final String lastName, final String emailTitle) {
        // Get the person by email
        final Person person = DynamoUtils.getInstance().getPersonByEmail(email).join();
        final String result;
        if ((person == null) || !person.getLast().equalsIgnoreCase(lastName)) {
            // If not exist, error
            result = null;
        } else {
            // Exists, send email w/ new password
            final String newPass = genNewPass();
            // Save the new password
            if (setPass(email, newPass, newPass)) {
                // Return email content
                result = newPassEmail(emailTitle, newPass);
            } else {
                throw new IllegalStateException("Error changing password! Please tell Ken.");
            }
        }
        return result;
    }

    private String newPassEmail(final String title, final String pass) {
        final HttpServletRequest req = (HttpServletRequest) FacesContext.getCurrentInstance()
                .getExternalContext().getRequest();
        final int port = req.getServerPort();
        final String portStr = ((port == 80) || (port == 443)) ? "" : (":" + port);
        final String url = req.getScheme() + "://" + req.getServerName() + portStr
                + req.getContextPath() + "/login.jsf";
        return title + "\n\n A request was made to reset your password. Your new password is: \n\n\t\t"
                + pass + "\n\nYou may now login at: " + url + " with your email address and this new password.\n\n"
                + "If you have any problems, reach out to Ken Paulsen (kenapaulsen@gmail.com).\n\n";
    }

    private String genNewPass() {
        return RandomData.genPassChars(8);
    }
}
