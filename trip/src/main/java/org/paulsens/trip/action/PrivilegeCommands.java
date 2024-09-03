package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;

@Slf4j
@Named("priv")
@ApplicationScoped
public class PrivilegeCommands {
    private static final long TIMEOUT = 5_000;
    private final DAO dao = DAO.getInstance();

    public Privilege createPrivilege(final String name, final String description, final List<Person.Id> people) {
        return new Privilege(name, description, people);
    }

    public List<Privilege> getPrivileges() {
        return dao.getPrivileges()
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, List.of()))
                .join();
    }

    public Privilege getTripPriv(final String tripId, final String privName) {
        return getPrivilege(privName + tripId);
    }

    public Privilege getPrivilege(final String privName) {
        return getPrivilegeMaybe(privName).orElse(null);
    }

    public Privilege getOrCreate(final String privName, final String description) {
        return getPrivilegeMaybe(privName)
                .orElseGet(() -> new Privilege(privName, description, List.of()));
    }

    public boolean savePrivilege(final Privilege privilege) {
        if ((privilege == null) || (privilege.getName() == null) || privilege.getName().isBlank()) {
            throw new IllegalStateException("Cannot save a privilege without a name!");
        }
        return dao.savePrivilege(privilege)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, false))
                .join();
    }

    public boolean checkTripPriv(final String tripId, final String tripPrivName, final Person.Id personId) {
        return check(tripPrivName + tripId, personId);
    }

    public boolean check(final String privName, final Person.Id personId) {
        if (personId == null) {
            return false;
        }
        return getPrivilegeMaybe(privName)
                .map(priv -> priv.getPeople().contains(personId))
                .orElse(false);
    }

    public boolean add(final String privName, final Person.Id personId) {
        if (check(privName, personId)) {
            return false;
        }
        return getPrivilegeMaybe(privName)
                .map(p -> new Privilege(privName, p.getDescription(), addToList(p.getPeople(), personId)))
                .map(this::savePrivilege)
                .orElse(false);
    }

    public boolean remove(final String privName, final Person.Id personId) {
        return getPrivilegeMaybe(privName)
                .map(p -> new Privilege(privName, p.getDescription(), removeFromList(p.getPeople(), personId)))
                .map(this::savePrivilege)
                .orElse(false);
    }

    /**
     * Rules for supporting role / user / priv according to this method:
     * <ol><li>If role is supplied and the active user has that role, they are authorized.</li>
     *     <li>If user is supplied and the active user is that user, or can access that user, they are authorized.</li>
     *     <li>If priv is supplied and the active user matches, they are authorized.</li>
     *     <li>If role, user, and priv are all null, they are authorized.</li>
     *     <li>Otherwise, they are <em>not</em> authorized.</li>
     * </ol>
     * @param role          The role... this can be a blank string b/c of how EL evaluates, we will treat this as null.
     * @param requiredUser  The user ID.
     * @param priv          The priv... this can be a blank string b/c of how EL evaluates, we will treat this as null.
     * @return  True if the user is authorized.
     */
    public boolean isAuthorized(final String role, final Person.Id requiredUser, final String priv) {
        final PersonCommands personCommands = PersonCommands.getPersonCommands();
        final Person currUser = personCommands.getCurrentPerson();
        final boolean result;
        final String requiredRole = role == null || role.isBlank() ? null : role;
        final String requiredPriv = priv == null || priv.isBlank() ? null : priv;
        if (personCommands.hasRole(requiredRole)) {
            result = true;
        } else if (personCommands.canAccessUserId(currUser, requiredUser)) {
            result = true;
        } else if (requiredPriv != null && !requiredPriv.isBlank()) {
            result = check(requiredPriv, currUser.getId());
        } else {
            result = requiredRole == null && requiredUser == null;
        }
        return result;
    }
/*
OLD CODE from xhtml:

currUser = people.getPerson(sessionScope.userId);
if ((viewScope.reqRole != null) &amp;&amp; !sessionScope.userRole.equalsIgnoreCase(viewScope.reqRole)) {
    // Missing req userRole
    if ((reqId == null) || !pass.canAccessUserId(currUser, reqId)) {
        // No user-override... redirect
        sessionScope.afterLoginURL = request.requestURL.toString().concat("?").concat(request.queryString);
        jsft.redirect("/account/login.jsf");
    }
} else if (viewScope.reqRole == null) {
    // No userRole requirement... is there a user requirement?
    if ((reqId != null) &amp;&amp; !pass.canAccessUserId(currUser, reqId)) {
        // No user-override... redirect
        sessionScope.afterLoginURL = request.requestURL.toString().concat("?").concat(request.queryString);
        jsft.redirect("/account/login.jsf");
    }
}
*/

    public List<Person.Id> getPeopleWithPriv(final List<String> privNames) {
        final PersonCommands people = PersonCommands.getPersonCommands();
        return privNames.stream()
                .map(this::getPrivilegeMaybe)
                .map(op -> op.map(Privilege::getPeople).orElse(List.of()))
                .flatMap(Collection::stream)
                .distinct()
                .sorted((a, b) -> lastCommaPreferredComparator(people, a, b))
                .toList();
    }

    private int lastCommaPreferredComparator(final PersonCommands people, final Person.Id a, final Person.Id b) {
        final Person aPerson = people.getPerson(a);
        final Person bPerson = people.getPerson(b);
        if (aPerson == null) {
            return -1;
        }
        if (bPerson == null) {
            return 1;
        }
        return CharSequence.compare(
                aPerson.getLast() + ',' + aPerson.getPreferredName(),
                bPerson.getLast() + ',' + bPerson.getPreferredName());
    }

    private Optional<Privilege> getPrivilegeMaybe(final String privName) {
        final Optional<Privilege> priv = dao.getPrivilege(privName)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, Optional.empty()))
                .join();
        if (priv.isEmpty()) {
            log.debug("Unknown privilege '" + privName + "'!");
        }
        return priv;
    }

    private <T> List<T> addToList(final Collection<T> list, T newElt) {
        final List<T> result = new ArrayList<>(list);
        result.add(newElt);
        return result;
    }

    private <T> List<T> removeFromList(final Collection<T> list, T toRemove) {
        final List<T> result = new ArrayList<>(list);
        result.remove(toRemove);
        return result;
    }

    private <T> T logAndReturn(final Throwable ex, final T result) {
        log.warn("Exception!", ex);
        return result;
    }
}
