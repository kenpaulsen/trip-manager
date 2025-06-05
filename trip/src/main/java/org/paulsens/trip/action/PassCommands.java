package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.annotation.FacesConfig;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;

import static org.paulsens.trip.action.TripUtilCommands.addMessage;
import static org.paulsens.trip.dynamo.CredentialsDAO.IS_ADMIN;

@Slf4j
@Named("pass")
@FacesConfig
@ApplicationScoped
public class PassCommands {
    public boolean userExistsWithEmail(final String email) {
        return DAO.getInstance().getPersonByEmail(email).join() != null;
    }

    public Creds login(final String email, final String pass) {
        final Creds creds = getCreds(email, pass);
        if (creds != null) {
            // login successful
            final String prevUpdateTime = Audit.formatEpochSeconds(DAO.getInstance().updateLastLogin(creds));
            Audit.log(email, "LOGIN", "User " + email + " logged in, previous login was: " + prevUpdateTime);
        } else {
            Audit.log(email, "LOGIN", "Login Failed!");
        }
        return creds;
    }

    public Creds getCreds(final String email, final String pass) {
        return DAO.getInstance().getCredsByEmailAndPass(email, pass)
                .exceptionally(ex -> {
                    log.error("Failed to get creds for: " + email, ex);
                    return null;
                }).join();
    }

    public Creds adminGetCreds(final String email) {
        return DAO.getInstance().adminGetCredsByEmail(email)
                .orTimeout(3_000, TimeUnit.MILLISECONDS)
                .join();
    }

    public Boolean adminSetPass(final String email, final String pass) {
        final FacesContext facesContext = FacesContext.getCurrentInstance();
        if (facesContext == null) {
            return false;
        }
        final Map<String, Object> viewMap = facesContext.getViewRoot().getViewMap(false);
        if (viewMap == null || !Boolean.parseBoolean(viewMap.getOrDefault(IS_ADMIN, false).toString())) {
            return false;
        }
        return setPass(email, pass);
    }

    public Creds getCredsByAdmin(final String email, final Person.Id id) {
        return DAO.getInstance().getCredsByEmailAdminOnly(email, id)
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
        final DAO dao = DAO.getInstance();
        final Creds creds = dao.createCreds(email).orElse(null);
        if (creds != null) {
            creds.setPass(newPass);
            dao.saveCreds(creds);
            Audit.log(email, "CREATE_CREDS", "Created credentials.");
        } else {
            Audit.log(email, "CREATE_CREDS", "Failed to create credentials!");
        }
        return creds;
    }

    /**
     * This method allows the user to change their password.
     * @param email     The email address (login) to receive a new password.
     * @param currPass  The current password.
     * @param pass      The new password.
     * @param pass2     The new password again (in case these 2 values come from a set password form).
     * @return  True if the password was set, False otherwise.
     */
    public Boolean setPass(final String email, final String currPass, final String pass, final String pass2) {
        if (!pass.equals(pass2)) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Passwords do not match!", "");
            return false;
        }
        final DAO dao = DAO.getInstance();
        final Person person = dao.getPersonByEmail(email).join();
        final Creds currCreds = getCreds(email, currPass);
        if ((currCreds == null) || (person == null)) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Person or credentials missing!", "");
            return false;
        }
        if (!currCreds.getUserId().equals(person.getId())) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "You do not have permission to change this person's password!", "");
            return false;
        }
        return setPass(email, pass);
    }

    public Boolean deleteCreds(final String email) {
        final Boolean result = DAO.getInstance().removeCreds(email).join();
        if (!result) {
            log.warn("Unable to remove Creds for ({}). Perhaps no Creds exist or this user is an admin?", email);
        }
        return result;
    }

    public Boolean setEmail(final Person person, final String oldEmail, final String newEmail) {
        if (newEmail == null || newEmail.equals(oldEmail)) {
            return false;
        }
        final Boolean result;
        // Update their old Creds to use their new email
        final Creds newCreds = adminGetCreds(newEmail);
        if (newCreds != null && !newCreds.getUserId().equals(person.getId())) {
            // Trying to use an email address that is not theirs and already in use!
            final String msg = person.getEmail() + " is already in use! Reverting.";
            addMessage(null, new FacesMessage(FacesMessage.SEVERITY_WARN, msg, ""));
            person.setEmail(oldEmail);
            try {
                if (!DAO.getInstance().savePerson(person)
                        .orTimeout(3_000, TimeUnit.MILLISECONDS)
                        .join()) {
                    throw new IOException("Failed to revert email!");
                }
            } catch (final IOException ex) {
                addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to revert email!", ""));
            }
            result = false;
        } else {
            final Creds oldCreds = adminGetCreds(oldEmail);
            if (oldCreds == null) {
                result = false;
            } else {
                oldCreds.setEmail(newEmail);
                result = DAO.getInstance().saveCreds(oldCreds).join();
            }
        }
        return result;
    }

    /**
     * Warning this is a dangerous command that allows a password to be directly set. Do not allow the user to
     * set the email parameter directly. Do not call this method directly from the UI (private for a reason). See
     * the setPass() which requires the old password, or the resetPass instead.
     * @param email     The email address (login) to receive a new password.
     * @param pass      The new password.
     * @return  True if the password was set, False otherwise.
     */
    private Boolean setPass(final String email, final String pass) {
        final DAO dao = DAO.getInstance();
        final Person person = dao.getPersonByEmail(email).join();
        if (person == null) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Check email address, and make sure you have registered.", "");
            return false;
        }
        final Creds creds = new Creds(email, person.getId(), pass);
        return dao.saveCreds(creds).join();
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
        final Person person = DAO.getInstance().getPersonByEmail(email).join();
        final String result;
        if ((person == null) || !person.getLast().equalsIgnoreCase(lastName)) {
            // If not exist, error
            result = null;
        } else {
            // Exists, send email w/ new password
            final String newPass = genNewPass();
            // Save the new password
            if (setPass(email, newPass)) {
                // Return email content
                result = newPassEmail(emailTitle, newPass);
            } else {
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                        "Error changing password! Please tell Ken.", "");
                result = null;
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
                + req.getContextPath() + "/account/login.jsf";
        return title + "\n\n A request was made to reset your password. Your new password is: \n\n\t\t"
                + pass + "\n\nYou may now login at: " + url + " with your email address and this new password.\n\n";
    }

    private String genNewPass() {
        return RandomData.genPassChars(8);
    }
}
