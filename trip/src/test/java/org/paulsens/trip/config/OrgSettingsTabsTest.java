package org.paulsens.trip.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.model.SettingDef;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** The org settings page's tabs: addressed by key, and together they show every org-overridable setting once. */
public class OrgSettingsTabsTest {

    @Test
    public void everyOrgOverridableSettingIsOnExactlyOneTab() {
        final List<SettingDef> shown = new ArrayList<>(OrgSettingsTabs.appearanceDefs());
        for (final String key : OrgSettingsTabs.KEYS) {
            shown.addAll(OrgSettingsTabs.defsFor(key));
        }
        assertEquals(shown.size(), shown.stream().distinct().count(), "no setting is offered on two tabs");
        assertEquals(new java.util.HashSet<>(shown), new java.util.HashSet<>(KnownSettings.orgOverridable()),
                "a new org-overridable setting must be given a tab (or a purpose-built Appearance control)");
        assertTrue(OrgSettingsTabs.appearanceDefs().stream().allMatch(def -> KnownSettings.isBranding(def.getName())),
                "the Appearance tab edits branding settings only");
        assertTrue(OrgSettingsTabs.defsFor(OrgSettingsTabs.PROFILE).contains(KnownSettings.SITE_CONTACT_NAME),
                "contact details sit together on Profile");
        assertTrue(OrgSettingsTabs.defsFor(OrgSettingsTabs.HOME).contains(KnownSettings.SITE_DONATE_URL));
    }

    @Test
    public void aTabIsAddressedByKeyAndAStaleLinkOpensTheFirst() {
        assertEquals(OrgSettingsTabs.KEYS, List.of("profile", "appearance", "home", "trips", "chat", "email",
                "privileges"), "page order; older ?tab= links depend on these keys, never on positions");
        assertEquals(OrgSettingsTabs.indexOf("chat"), 4);
        assertEquals(OrgSettingsTabs.indexOf(" privileges "), 6);
        assertEquals(OrgSettingsTabs.indexOf("profile"), 0);
        assertEquals(OrgSettingsTabs.indexOf(""), 0, "an empty ?tab= arrives as \"\", not null");
        assertEquals(OrgSettingsTabs.indexOf("nonesuch"), 0);
        assertEquals(OrgSettingsTabs.indexOf(null), 0);
        assertTrue(OrgSettingsTabs.defsFor(OrgSettingsTabs.EMAIL).isEmpty(), "Email has purpose-built controls only");
        assertTrue(OrgSettingsTabs.defsFor(null).isEmpty());
        assertTrue(OrgSettingsTabs.defsFor("nonesuch").isEmpty());
    }

    @Test
    public void aTabsSaveCarriesOnlyItsOwnSettings() {
        final Map<String, String> edit = new LinkedHashMap<>();
        edit.put(KnownSettings.REG_ALLOW_EDITS.getName(), "true");
        edit.put(KnownSettings.CHAT_REACTIONS_PALETTE.getName(), "unsaved chat edit");
        final Map<String, String> trips = OrgSettingsTabs.valuesFor(OrgSettingsTabs.TRIPS, edit);
        assertEquals(trips, Map.of(KnownSettings.REG_ALLOW_EDITS.getName(), "true"),
                "another tab's unsaved edit never rides this tab's Save; absent keys are not invented");
        assertTrue(OrgSettingsTabs.valuesFor(OrgSettingsTabs.TRIPS, null).isEmpty());

        final ConfigCommands config = new ConfigCommands();
        assertEquals(config.orgTabIndex("home"), 2);
        assertEquals(config.orgTabDefs("chat"), OrgSettingsTabs.defsFor("chat"));
        assertEquals(config.orgTabValues("trips", edit), trips);
    }
}
