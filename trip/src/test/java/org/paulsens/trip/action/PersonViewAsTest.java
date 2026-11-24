package org.paulsens.trip.action;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.web.FakeSessions;
import org.paulsens.trip.web.Sessions;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The bean face of the View As push/pop ({@code #{people.viewAs}} / {@code #{people.endViewAs}}). The
 * session mechanics themselves are pinned down in {@code SessionsTest}; what this covers is the bean's
 * behavior around them -- refusal without a session, the switch, and the round trip.
 */
public class PersonViewAsTest {

    @Test
    public void viewAsWithoutAFacesContextRefusesQuietly() {
        // No FacesContext in unit tests, so the public overload resolves a null session: refused, no throw.
        Assert.assertFalse(new PersonCommands().viewAs(Person.Id.from("whoever")));
        Assert.assertFalse(new PersonCommands().endViewAs());
        Assert.assertFalse(new PersonCommands().isViewingAs(), "no session: nobody is viewing as anyone");
    }

    @Test
    public void viewAsSwitchesAndEndViewAsRestores() {
        final Person.Id adminId = Person.Id.from("va-admin");
        final Person.Id targetId = Person.Id.from("va-target");
        final Map<String, Object> attrs = new HashMap<>();
        attrs.put(PersonCommands.ACTIVE_USER_ID, adminId);
        attrs.put(PersonCommands.ACTIVE_USER_ROLE, "admin");
        attrs.put(Sessions.LOGIN_EMAIL, "va-admin@example.com");
        attrs.put(Sessions.ADMIN_USER, adminId);
        attrs.put("resumeRegTrip", "some-trip");
        final HttpSession session = FakeSessions.over(attrs);
        final PersonCommands people = new PersonCommands();

        Assert.assertTrue(people.viewAs(session, targetId));
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), targetId);
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ROLE), "user");
        Assert.assertNull(attrs.get("resumeRegTrip"), "admin browsing state must not follow the switch");

        Assert.assertTrue(people.endViewAs(session));
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), adminId);
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ROLE), "admin");
        Assert.assertEquals(attrs.get("resumeRegTrip"), "some-trip", "the snapshot came back");
        Assert.assertFalse(people.endViewAs(session), "nothing left to pop");
    }

    @Test
    public void viewAsRefusesANonAdminSession() {
        final Map<String, Object> attrs = new HashMap<>();
        attrs.put(PersonCommands.ACTIVE_USER_ID, Person.Id.from("plain"));
        attrs.put(PersonCommands.ACTIVE_USER_ROLE, "user");
        final HttpSession session = FakeSessions.over(attrs);

        Assert.assertFalse(new PersonCommands().viewAs(session, Person.Id.from("someone")));
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), Person.Id.from("plain"));
    }
}
