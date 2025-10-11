package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.ScopeUtil;

@Slf4j
@Named("people")
@ApplicationScoped
public class PersonCommands {
    // Session Scope variable of the active user (Person.Id); NOTE: When an admin user assumes someone else's
    // identity, this variable is set to the non-admin user. The admin user's id can be found in the "aUser" key.
    public static final String ACTIVE_USER_ID = "userId";
    public static final String ACTIVE_USER_ROLE = "userRole";

    public static PersonCommands getPersonCommands() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final PersonCommands result;
        if (ctx != null) {
            final Map<String, Object> appMap = ctx.getExternalContext().getApplicationMap();
            result = (PersonCommands) appMap.computeIfAbsent("people", key -> new PersonCommands());
        } else {
            result = new PersonCommands();
        }
        return result;
    }

    public Person createPerson() {
        return new Person();
    }

    public boolean savePerson(final Person person) {
        boolean result;
        try {
             result = DAO.getInstance().savePerson(person).exceptionally(ex -> {
                    TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Error saving: " + person.getFirst()
                            + " " + person.getLast(), ex.getMessage());
                 log.error("Error while saving user: ", ex);
                 return false;
                }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to save: " + person.getFirst()
                    + " " + person.getLast(), ex.getMessage());
            log.error("Error while saving user: ", ex);
            result = false;
        }
        return result;
    }

    public List<Person> getPeople() {
        return DAO.getInstance().getPeople()
                .orTimeout(3_000, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.error("Failed to get list of people!", ex);
                    return Collections.emptyList();
                })
                .join();
    }

    /**
     * Creates a new list that is sorted according to the given EL expression. {@code loopItem} in the EL represents an
     * object in the Person list that is being sorted. The EL expression should <em>NOT</em> contain the wrapping
     * {@code #{}} around it. The comparison of the expression will be a String comparison, perhaps something to
     * enhance in the future.
     * @return A new sorted list.
     */
    public <T> List<T> toSortedList(final List<T> before, final String expression) {
        final Map<String, Object> reqMap = FacesContext.getCurrentInstance().getExternalContext().getRequestMap();
        final List<T> after = new ArrayList<>(before);
        after.sort((a, b) -> stringCompareWithExpression(reqMap, a, b, expression));
        return after;
    }

    private <T> int stringCompareWithExpression(
            final Map<String, Object> reqMap, final T a, final T b, final String exp) {
        reqMap.put("loopItem", a);
        final String aStrVal = "" + ELUtil.getInstance().eval("#{" + exp + "}");
        reqMap.put("loopItem", b);
        final String bStrVal = "" + ELUtil.getInstance().eval("#{" + exp + "}");
        return aStrVal.compareTo(bStrVal);
    }

    public List<Person> getPeopleByIds(final List<Person.Id> ids) {
        return ids.stream().map(this::getPerson).toList();
    }

    public Person getPerson(final Person.Id id) {
        return getPersonInternal(id, Person::new);
    }

    public Person getCurrentPerson() {
        return getPerson(ScopeUtil.getInstance().getSessionMap(ACTIVE_USER_ID));
    }

    public Person getPersonByEmail(final String email) {
        return DAO.getInstance().getPersonByEmail(email)
                .exceptionally(ex -> {
                    log.error("Exception while trying to find person with email: " + email);
                    throw new IllegalStateException(ex);
                }).join();
    }

    /**
     * Tests to see if {@code userId} has access to {@code reqId}.
     * @param person    The user whom is requesting access.
     * @param reqId     The id to test for access.
     * @return  {@code true} if {@code userId} can access {@code reqId}.
     */
    public boolean canAccessUserId(final Person person, final Person.Id reqId) {
        if (person == null || reqId == null) {
            return false;
        }
        return person.getId().equals(reqId) || person.getManagedUsers().contains(reqId);
    }

    public boolean hasRole(final String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        return role.equalsIgnoreCase(ScopeUtil.getInstance().getSessionMap(ACTIVE_USER_ROLE));
    }

    public Person.Id id(final String id) {
        return Person.Id.from(id);
    }

    private Person getPersonInternal(final Person.Id id, final Supplier<Person> defaultPersonSupplier) {
        return DAO.getInstance().getPerson(id)
                .orTimeout(3_000, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.error("Failed to get person '" + id + "'!", ex);
                    return Optional.empty();
                }).join()
                .orElse(defaultPersonSupplier.get());
    }
}
