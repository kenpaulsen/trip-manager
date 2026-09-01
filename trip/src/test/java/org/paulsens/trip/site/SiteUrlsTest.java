package org.paulsens.trip.site;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Derived, never stored: a link about an organization with its own subdomain must carry that subdomain,
 * whatever the site-wide base-URL setting says and whatever host the sender ran on. Everything else keeps
 * the setting's value. The registration mail tokens are checked end to end because that is the path a
 * pilgrim actually clicks.
 */
public class SiteUrlsTest {

    private final ConfigCommands config = new ConfigCommands();

    @Test
    public void anOrgWithASlugIsAddressedOnItsOwnSite() {
        final Organization acme = new Organization();
        acme.setSlug("Acme ");
        Assert.assertEquals(SiteUrls.baseUrl(acme, KnownSettings.REG_MAIL_BASE_URL, config),
                "https://acme.unitetrip.com", "lower-cased and trimmed, on the configured base domain");
        Assert.assertEquals(SiteUrls.baseUrl(acme, KnownSettings.CHAT_MAIL_BASE_URL, config),
                "https://acme.unitetrip.com", "the site setting named does not matter once the org has a site");
    }

    @Test
    public void everyOtherOrgKeepsTheSiteSetting() {
        final String regBase = KnownSettings.REG_MAIL_BASE_URL.getDefaultValue();
        Assert.assertEquals(SiteUrls.baseUrl(null, KnownSettings.REG_MAIL_BASE_URL, config), regBase);
        final Organization plain = new Organization();
        Assert.assertEquals(SiteUrls.baseUrl(plain, KnownSettings.REG_MAIL_BASE_URL, config), regBase);
        plain.setSlug("   ");
        Assert.assertEquals(SiteUrls.baseUrl(plain, KnownSettings.REG_MAIL_BASE_URL, config), regBase,
                "a blank slug is no slug");
        Assert.assertEquals(SiteUrls.baseUrlForOrgId(null, KnownSettings.REG_MAIL_BASE_URL, config), regBase);
        Assert.assertEquals(SiteUrls.baseUrlForOrgId(" ", KnownSettings.REG_MAIL_BASE_URL, config), regBase);
        Assert.assertEquals(SiteUrls.baseUrlForOrgId("no-such-org", KnownSettings.REG_MAIL_BASE_URL, config),
                regBase, "an unknown org id is the site rung, not an error");
        Assert.assertEquals(SiteUrls.baseUrlForTrip(null, KnownSettings.REG_MAIL_BASE_URL, config), regBase);
    }

    @Test
    public void anUnreadableOrgRowDegradesToTheSiteRung() {
        // A mail link is better on the shared site than never sent: the org lookup failing (cache outage)
        // must not propagate into the sender.
        Assert.assertEquals(SiteUrls.baseUrlForOrgId("some-org", KnownSettings.REG_MAIL_BASE_URL, config,
                SiteUrlsTest::cacheDown), KnownSettings.REG_MAIL_BASE_URL.getDefaultValue());
    }

    private static java.util.Optional<Organization> cacheDown(final Organization.Id id) {
        throw new IllegalStateException("cache down");
    }

    @Test
    public void aStoredSettingLosesItsTrailingSlashSoCallersCanAppendPaths() {
        final ConfigCommands mocked = Mockito.mock(ConfigCommands.class);
        Mockito.when(mocked.getString(KnownSettings.CHAT_MAIL_BASE_URL)).thenReturn("https://x.example//");
        Assert.assertEquals(SiteUrls.baseUrl(null, KnownSettings.CHAT_MAIL_BASE_URL, mocked), "https://x.example");
        Mockito.when(mocked.getString(KnownSettings.CHAT_MAIL_BASE_URL)).thenReturn(null);
        Assert.assertEquals(SiteUrls.baseUrl(null, KnownSettings.CHAT_MAIL_BASE_URL, mocked), "",
                "a null setting (a mock, an outage) never becomes the literal 'null' in a link");
    }

    @Test
    public void anUnreadableBaseDomainFallsBackToTheShippedOne() {
        // A sender's test mocks ConfigCommands and answers null for everything it did not stub; a mail
        // link with a null host would be worse than one on the shipped domain.
        final ConfigCommands mocked = Mockito.mock(ConfigCommands.class);
        Assert.assertEquals(SiteUrls.orgSiteUrl("acme", mocked), "https://acme.unitetrip.com");
        Mockito.when(mocked.getString(KnownSettings.SITE_ORGSITES_BASE_DOMAIN)).thenReturn(" Trips.Example ");
        Assert.assertEquals(SiteUrls.orgSiteUrl("acme", mocked), "https://acme.trips.example");
    }

    @Test
    public void hostOfExtractsTheHostnameForMailCopy() {
        Assert.assertEquals(SiteUrls.hostOf("https://acme.unitetrip.com"), "acme.unitetrip.com");
        Assert.assertEquals(SiteUrls.hostOf("https://www.visitqueenofpeace.com/"), "www.visitqueenofpeace.com");
        Assert.assertEquals(SiteUrls.hostOf(""), "");
        Assert.assertEquals(SiteUrls.hostOf(null), "");
        Assert.assertEquals(SiteUrls.hostOf("just-words"), "just-words", "no host: the value itself");
        Assert.assertEquals(SiteUrls.hostOf("http://bad host/"), "http://bad host/", "unparseable: the value");
    }

    @Test
    public void registrationMailLinksLandOnTheTripsOrgSite() throws Exception {
        final Organization org = new Organization();
        org.setName("Mail " + RandomData.genAlpha(6));
        final String slug = "mail" + RandomData.genAlpha(6).toLowerCase(Locale.ROOT);
        org.setSlug(slug);
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        final Trip trip = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Org trip").build();
        trip.setOrgId(org.getId().getValue());
        final Person person = Person.builder().first("Pat").last("Pilgrim").build();
        final RegistrationCommands reg = new RegistrationCommands(
                () -> new Caller(person.getId(), true, new AuditActor("admin@test", "admin"), null));

        final Map<String, Object> received = reg.receivedMailValues(trip, List.of(person));
        Assert.assertEquals(received.get("tripUrl"),
                "https://" + slug + ".unitetrip.com/trip/tripDetails.jsf?trip=" + trip.getId());
        final Map<String, Object> approved = reg.approvedMailValues(trip, person);
        Assert.assertTrue(approved.get("itineraryUrl").toString()
                .startsWith("https://" + slug + ".unitetrip.com/trip/itinerary.jsf?trip=" + trip.getId()));
        Assert.assertEquals(approved.get("profileUrl"),
                "https://" + slug + ".unitetrip.com/account/person.jsf?id=" + person.getId().getValue());

        // The same trip with no org site: the site-wide setting, exactly as before.
        final Trip shared = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Shared").build();
        Assert.assertEquals(reg.receivedMailValues(shared, List.of()).get("tripUrl"),
                KnownSettings.REG_MAIL_BASE_URL.getDefaultValue() + "/trip/tripDetails.jsf?trip=" + shared.getId());
    }
}
