package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Who a registration approval is PUSHED to: the person plus every family manager, mailbox or not --
 * unlike the email recipients, which need an address. Also pins {@code PersonCommands.managersOf}, the
 * address-blind half {@code mailableManagers} now filters.
 */
public class ApprovalRecipientIdsTest {

    private static Person saved(final String email) throws IOException {
        final Person person = Person.builder().first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email(email).build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    @Test
    public void thePersonAndEveryManagerAreRecipientsOnceEach() throws IOException {
        final Person creator = saved("creator." + RandomData.genAlpha(8).toLowerCase(Locale.ROOT) + "@example.com");
        final Person unmailable = saved(null);
        final Person child = saved(null);
        final Family family = Family.builder().id(Family.Id.from("fam-" + RandomData.genAlpha(8)))
                .memberIds(List.of(creator.getId(), unmailable.getId(), child.getId()))
                .managerIds(List.of(unmailable.getId(), creator.getId(), creator.getId()))
                .createdBy(creator.getId()).build();
        Assert.assertTrue(DAO.getInstance().saveFamily(family));
        child.setFamilyId(family.getId());
        Assert.assertTrue(DAO.getInstance().savePerson(child));

        final PersonCommands people = new PersonCommands();
        Assert.assertEquals(people.managersOf(child).stream().map(Person::getId).toList(),
                List.of(creator.getId(), unmailable.getId()), "creator first, then the rest, no duplicates");
        Assert.assertEquals(people.mailableManagers(child).stream().map(Person::getId).toList(),
                List.of(creator.getId()), "the address filter still applies to mail");
        Assert.assertEquals(people.managersOf(null), List.of());
        Assert.assertEquals(people.managersOf(creator), List.of(), "no family id, no managers");
        final Person orphan = saved(null);
        orphan.setFamilyId(Family.Id.from("no-such-family"));
        Assert.assertEquals(people.managersOf(orphan), List.of());

        final RegistrationCommands reg = new RegistrationCommands(() -> null);
        Assert.assertEquals(reg.approvalRecipientIds(child),
                List.of(child.getId(), creator.getId(), unmailable.getId()));
        Assert.assertEquals(reg.approvalRecipientIds(creator), List.of(creator.getId()));
        Assert.assertEquals(reg.approvalRecipientIds(null), List.of());
    }
}
