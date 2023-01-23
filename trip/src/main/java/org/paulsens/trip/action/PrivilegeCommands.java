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
@Named("privCmd")
@ApplicationScoped
public class PrivilegeCommands {
    private static final long TIMEOUT = 5_000;
    private final DAO dao = DAO.getInstance();

    public Privilege getPrivilege(final String privName) {
        return getPrivilegeMaybe(privName).orElse(null);
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

    public boolean check(final String privName, final Person.Id personId) {
        return getPrivilegeMaybe(privName)
                .map(priv -> priv.getPeople().contains(personId))
                .orElse(false);
    }

    public boolean add(final String privName, final Person.Id personId) {
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

    private Optional<Privilege> getPrivilegeMaybe(final String privName) {
        final Optional<Privilege> priv = dao.getPrivilege(privName)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, Optional.empty()))
                .join();
        if (priv.isEmpty()) {
            log.warn("Unknown privilege '" + privName + "'!");
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
