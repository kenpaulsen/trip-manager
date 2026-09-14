package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.push.PushDevice;
import org.paulsens.trip.push.PushDevices;
import org.paulsens.trip.push.PushNotifications;
import org.paulsens.trip.push.PushPrefs;
import org.paulsens.trip.push.PushSender;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The {@code #{push}} bean: the profile page's scalars and Save, and the wire shapes the resource reuses. */
public class PushCommandsTest {

    private static final String P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private final PushCommands push = new PushCommands();

    private static Person.Id saved() throws IOException {
        final Person person = Person.builder().first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("pushcmd." + RandomData.genAlpha(10).toLowerCase(Locale.ROOT) + "@example.com").build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person.getId();
    }

    @Test
    public void theProfileScalarsReadBlankUntilSomethingIsStored() throws IOException {
        final Person.Id me = saved();
        Assert.assertTrue(push.isEnabledFor(me), "on by default");
        Assert.assertEquals(push.quietStart(me), "");
        Assert.assertEquals(push.quietEnd(me), "");
        Assert.assertEquals(push.quietZone(me), "");

        Assert.assertTrue(push.savePrefsFromUi(me, false, "22:00", "07:00", "America/Los_Angeles"),
                "success does not growl; the page does");
        Assert.assertFalse(push.isEnabledFor(me));
        Assert.assertEquals(push.quietStart(me), "22:00");
        Assert.assertEquals(push.quietEnd(me), "07:00");
        Assert.assertEquals(push.quietZone(me), "America/Los_Angeles");

        Assert.assertTrue(push.savePrefsFromUi(me, true, null, null, null), "blank both clears quiet hours");
        Assert.assertEquals(push.quietStart(me), "");
        Assert.assertEquals(push.quietZone(me), "");
        Assert.assertFalse(push.savePrefsFromUi(me, true, "22:00", "", null), "one end alone is refused");
        Assert.assertFalse(push.savePrefsFromUi(me, true, "9pm", "7am", null));
        Assert.assertFalse(push.savePrefsFromUi(null, true, null, null, null));
    }

    @Test
    public void zoneChoicesAreTheTravelFamiliesPlusUtcSorted() {
        final List<String> zones = push.zoneChoices();
        Assert.assertTrue(zones.contains("America/Los_Angeles"));
        Assert.assertTrue(zones.contains("Europe/Zagreb"));
        Assert.assertTrue(zones.contains("Pacific/Auckland"));
        Assert.assertTrue(zones.contains("Australia/Sydney"));
        Assert.assertTrue(zones.contains("Asia/Manila"));
        Assert.assertTrue(zones.contains("UTC"));
        Assert.assertFalse(zones.contains("Etc/GMT+3"));
        Assert.assertFalse(zones.contains("GMT"));
        Assert.assertEquals(zones, zones.stream().sorted().toList());
    }

    @Test
    public void devicesRoundTripThroughTheBeanAndTheirWireShapeHidesCredentials() throws IOException {
        final Person.Id me = saved();
        final PushDevices.Outcome registered = push.registerWeb(me, "https://example.invalid/s/1", P256DH, AUTH,
                "Safari", "https://acme.example", AuditActor.system());
        Assert.assertTrue(registered.ok(), registered.message());
        final Map<String, Object> described = push.describe(push.prefsOf(me));
        Assert.assertEquals(described.get("enabled"), true);
        Assert.assertEquals(described.get("quietHoursStart"), "");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> devices = (List<Map<String, Object>>) described.get("devices");
        Assert.assertEquals(devices.size(), 1);
        Assert.assertEquals(devices.get(0).get("kind"), "web");
        Assert.assertEquals(devices.get(0).get("label"), "Safari");
        Assert.assertEquals(devices.get(0).get("origin"), "https://acme.example");
        Assert.assertFalse(devices.get(0).containsKey("endpoint"));
        Assert.assertFalse(devices.get(0).containsKey("environment"));
        final String id = (String) devices.get(0).get("id");

        Assert.assertTrue(push.removeDeviceFromUi(me, id));
        Assert.assertFalse(push.removeDeviceFromUi(me, id), "already gone: a warning, not a failure");
        Assert.assertTrue(push.registerWeb(me, "https://example.invalid/s/2", P256DH, AUTH, null, null,
                AuditActor.system()).ok());
        Assert.assertTrue(push.unsubscribeWeb(me, "https://example.invalid/s/2", AuditActor.system()));
        Assert.assertFalse(push.removeDevice(me, "nothing", AuditActor.system()));
        Assert.assertEquals(push.registerIos(me, "zz", "sandbox", "s", "l", "v", AuditActor.system()).code(),
                PushDevices.REFUSED_BAD_TOKEN);
        Assert.assertTrue(push.setPrefs(me, false, null, null, null).ok());
        Assert.assertEquals(PushCommands.describeDevice(PushDevice.ios("ab".repeat(16), "production", "s", null,
                "1", 3L)).get("label"), "");
    }

    @Test
    public void localModeOffersTheEphemeralVapidKeyAndTheTestPushGoesThroughTheFacade() throws IOException {
        Assert.assertNotNull(push.vapidPublicKey(), "local mode mints a throwaway pair so subscribe() works");
        final PushNotifications facade = Mockito.mock(PushNotifications.class);
        final Person.Id me = saved();
        Mockito.when(facade.sendTest(me)).thenReturn(new PushSender.Report(0, List.of(), "off"));
        final PushCommands wired = new PushCommands() {
            @Override
            protected PushNotifications notifications() {
                return facade;
            }
        };
        Assert.assertEquals(wired.sendTest(me).skipped(), "off");
        Assert.assertEquals(push.prefsOf(me), PushPrefs.defaults());
    }
}
