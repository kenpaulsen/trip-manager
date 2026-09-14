package org.paulsens.trip.push;

import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The links and the icon a push carries: the trip's site, the org's logo when it has one. */
public class PushLinksTest {

    @Test
    public void theIconIsTheOrgsLogoWhenItHasOneElseTheSiteLogo() throws Exception {
        final ConfigCommands config = new ConfigCommands();
        final String base = PushLinks.base(null, config);
        Assert.assertFalse(base.endsWith("/"));
        Assert.assertEquals(PushLinks.iconFor(null, config), base + PushLinks.SITE_LOGO);

        final Organization branded = Organization.builder().id(Organization.Id.from("org-" + System.nanoTime()))
                .name("Acme").build();
        branded.getSettingsOverrides().putAll(Map.of(KnownSettings.SITE_LOGO_URL.getName(),
                "https://cdn.example/acme/logo.png"));
        Assert.assertTrue(DAO.getInstance().saveOrganization(branded));
        final Trip acme = Trip.builder().id("t-" + System.nanoTime()).orgId(branded.getId().getValue()).build();
        Assert.assertEquals(PushLinks.iconFor(acme, config), "https://cdn.example/acme/logo.png");

        final Organization plain = Organization.builder().id(Organization.Id.from("org-" + System.nanoTime()))
                .name("Plain").build();
        Assert.assertTrue(DAO.getInstance().saveOrganization(plain));
        final Trip plainTrip = Trip.builder().id("t-" + System.nanoTime()).orgId(plain.getId().getValue()).build();
        Assert.assertEquals(PushLinks.iconFor(plainTrip, config), base + PushLinks.SITE_LOGO);

        final Trip orphan = Trip.builder().id("t-" + System.nanoTime()).orgId("no-such-org").build();
        Assert.assertEquals(PushLinks.iconFor(orphan, config), base + PushLinks.SITE_LOGO);
        Assert.assertEquals(PushLinks.iconFor(Trip.builder().id("t").orgId(" ").build(), config),
                base + PushLinks.SITE_LOGO);
    }

    @Test
    public void urlsAndDeepLinksFollowTheContract() {
        final ConfigCommands config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getString(KnownSettings.CHAT_MAIL_BASE_URL)).thenReturn("https://site.example/");
        Mockito.when(config.getString(KnownSettings.REG_MAIL_BASE_URL)).thenReturn("https://reg.example");
        Assert.assertEquals(PushLinks.chatLink("t1"), "unitetrip://chat/t1");
        Assert.assertEquals(PushLinks.photosLink("t1"), "unitetrip://trip/t1/photos");
        Assert.assertEquals(PushLinks.paymentsLink(null), "unitetrip://trip//payments");
        Assert.assertEquals(PushLinks.chatUrl(null, "t1", config), "https://site.example/trip/chat.jsf?trip=t1");
        Assert.assertEquals(PushLinks.photoUrl(null, "t1", "chat/t1/a b.jpg", config),
                "https://site.example/trip/tripMedia.jsf?trip=t1&photo=chat%2Ft1%2Fa+b.jpg");
        Assert.assertEquals(PushLinks.photoUrl(null, "t1", null, config),
                "https://site.example/trip/tripMedia.jsf?trip=t1&photo=");
        Assert.assertEquals(PushLinks.tripUrl(Trip.builder().id("t1").build(), KnownSettings.REG_MAIL_BASE_URL,
                config), "https://reg.example/trip/tripDetails.jsf?trip=t1");
        Assert.assertEquals(PushLinks.tripUrl(null, KnownSettings.REG_MAIL_BASE_URL, config),
                "https://reg.example/trip/tripDetails.jsf?trip=");
        Assert.assertEquals(PushLinks.paymentsUrl(null, "t1", config), "https://site.example/trip/pay.jsf?trip=t1");
        Assert.assertEquals(PushLinks.supportUrl(config), "https://site.example/admin/support.jsf");
        Assert.assertEquals(PushLinks.imageUrl("chat/t1/x-small.jpg", null, config),
                "https://site.example/chat-photos/chat/t1/x-small.jpg", "local mode: the servlet path");
        Assert.assertNull(PushLinks.imageUrl(" ", null, config));
        Assert.assertNull(PushLinks.imageUrl(null, null, config));
    }
}
