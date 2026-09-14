package org.paulsens.trip.chat;

import java.util.List;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Person;

/**
 * Who is told when a route cannot reach the person a notification is for: their family managers, on that
 * same route. A parent registers a child and often not themselves, so the child is the one mentioned, replied
 * to or commented on -- and the child has no mailbox and no phone. Before this the mention was dropped at
 * both routes and nobody in the household heard (user decision 2026-09-14: it must reach someone).
 *
 * <p>Route by route, not above the routes: the email fallback is the mailable managers, the push fallback
 * every manager (a device is the reachability test, as for approvals). The copy names the child, the
 * dedupe key names the child too, so one manager with two children mentioned in one message is told about
 * each, and a manager who is also named directly is told once, as themselves.
 */
public final class OnBehalf {

    private OnBehalf() {
    }

    /** The name the household knows them by; never null, never an id. */
    public static String nameOf(final Person person) {
        final String name = person == null ? null : person.getPreferredName();
        return (name == null || name.isBlank()) ? "your family member" : name;
    }

    /** "Lucy's" -- the possessive for the templates' "your". */
    public static String possessiveOf(final Person person) {
        return nameOf(person) + "'s";
    }

    /** The route suffix for a dedupe key, so a manager's on-behalf send never collides with their own. */
    public static String route(final String route, final Person.Id child) {
        return route + ":for:" + (child == null ? "-" : child.getValue());
    }

    /** The managers the email route may address: valid addresses only, creator first. */
    public static List<Person> mailableManagers(final Person person) {
        return PersonCommands.getPersonCommands().mailableManagers(person);
    }

    /** The managers the push route may address: every manager, mailbox or not. */
    public static List<Person> managers(final Person person) {
        return PersonCommands.getPersonCommands().managersOf(person);
    }
}
