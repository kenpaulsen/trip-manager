package org.paulsens.trip.action;

import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.model.Organization;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link OrgOwnedLists}: the write guard both of an organization's named lists start with, and the naming rule. */
public class OrgOwnedListsTest {

    private static final String ORG = "org-owned-lists-org";

    @Test
    public void aCallerWhoMayNotManageTheOrgIsRefusedWithTheCallersOwnSentence() {
        final OrgCommands orgs = Mockito.mock(OrgCommands.class);
        Mockito.when(orgs.canManageOrg(ORG)).thenReturn(false);
        Assert.assertNull(OrgOwnedLists.editable(orgs, ORG, "Only its admins can."));
        Mockito.verify(orgs, Mockito.never()).freshOrg(ORG);
    }

    @Test
    public void aManagerGetsAFreshCopyAndAnOrgThatIsGoneGetsNothing() {
        final OrgCommands orgs = Mockito.mock(OrgCommands.class);
        final Organization org = new Organization();
        Mockito.when(orgs.canManageOrg(ORG)).thenReturn(true);
        Mockito.when(orgs.freshOrg(ORG)).thenReturn(org);
        Assert.assertSame(OrgOwnedLists.editable(orgs, ORG, "refused"), org);
        Mockito.when(orgs.freshOrg(ORG)).thenReturn(null);
        Assert.assertNull(OrgOwnedLists.editable(orgs, ORG, "refused"), "a vanished org is refused, not saved");
    }

    @Test
    public void anItemIsFoundByItsIdOrNotAtAll() {
        final List<String[]> items = List.<String[]>of(new String[] {"a", "Sea"}, new String[] {"b", "Sky"});
        Assert.assertEquals(OrgOwnedLists.indexOf(items, item -> item[0], "b"), 1);
        Assert.assertEquals(OrgOwnedLists.indexOf(items, item -> item[0], "z"), -1);
        Assert.assertEquals(OrgOwnedLists.indexOf(items, item -> item[0], null), -1);
    }

    @Test
    public void aNameIsTrimmedCollapsedAndCappedAndUniqueWithoutCase() {
        Assert.assertEquals(OrgOwnedLists.clean("  Sea   blue ", 20), "Sea blue");
        Assert.assertNull(OrgOwnedLists.clean("   ", 20));
        Assert.assertNull(OrgOwnedLists.clean(null, 20));
        Assert.assertNull(OrgOwnedLists.clean("x".repeat(21), 20));
        final List<String[]> items = List.<String[]>of(new String[] {"a", "Sea Blue"}, new String[] {"b", null});
        Assert.assertFalse(OrgOwnedLists.free(items, item -> item[0], item -> item[1], "sea blue", null));
        Assert.assertTrue(OrgOwnedLists.free(items, item -> item[0], item -> item[1], "sea blue", "a"),
                "an item may keep its own name");
        Assert.assertTrue(OrgOwnedLists.free(items, item -> item[0], item -> item[1], "Green", null));
    }
}
