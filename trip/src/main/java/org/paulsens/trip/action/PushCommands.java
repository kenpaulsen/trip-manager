package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.push.PushDevice;
import org.paulsens.trip.push.PushDevices;
import org.paulsens.trip.push.PushNotifications;
import org.paulsens.trip.push.PushPrefs;
import org.paulsens.trip.push.PushSecrets;
import org.paulsens.trip.push.PushSender;

/**
 * Push notification preferences and devices for the JSF profile page ({@code #{push}}) and, wrapped by
 * {@code PushResource}, the REST edge -- one implementation, never two ({@code docs/push-notifications.md}).
 *
 * <p>Every method takes the person explicitly: the profile page passes the profile owner (its own-profile
 * gate already applied) and the resource passes the caller. No method is overloaded -- EL resolves overloads
 * by runtime type and picks wrong -- and none is {@code final} (Weld's proxy, WELD-001480).
 */
@Slf4j
@Named("push")
@ApplicationScoped
public class PushCommands {

    /** The zone families a traveler is likely to live in, so the profile picker is not 600 rows long. */
    private static final List<String> ZONE_PREFIXES = List.of("America/", "Europe/", "Pacific/", "Australia/",
            "Asia/");

    // --- profile page (scalars only: nothing here may land in viewScope as an object) ---

    /** The master switch, as stored. On when there is no row yet. */
    public boolean isEnabledFor(final Person.Id personId) {
        return devices().prefsOf(personId).isEnabled();
    }

    /** {@code HH:mm} or {@code ""}. */
    public String quietStart(final Person.Id personId) {
        return blankFor(devices().prefsOf(personId).getQuietHoursStart());
    }

    /** {@code HH:mm} or {@code ""}. */
    public String quietEnd(final Person.Id personId) {
        return blankFor(devices().prefsOf(personId).getQuietHoursEnd());
    }

    /** The IANA zone id or {@code ""}. */
    public String quietZone(final Person.Id personId) {
        return blankFor(devices().prefsOf(personId).getTimeZone());
    }

    /** {@code America/*}, {@code Europe/*}, {@code Pacific/*}, {@code Australia/*}, {@code Asia/*} and UTC, sorted. */
    public List<String> zoneChoices() {
        return ZoneId.getAvailableZoneIds().stream()
                .filter(PushCommands::offered)
                .sorted()
                .toList();
    }

    private static boolean offered(final String zone) {
        return "UTC".equals(zone) || ZONE_PREFIXES.stream().anyMatch(zone::startsWith);
    }

    /**
     * The profile fieldset's Save: the master switch and the quiet hours in one write. Blank start AND end
     * clears quiet hours. Refusals growl through {@link PageFeedback}; success does NOT -- the page growls
     * its own "saved" line on a true return (the website agent's contract, 2026-09-13).
     */
    public boolean savePrefsFromUi(final Person.Id me, final Boolean enabled, final String quietStart,
            final String quietEnd, final String quietZone) {
        final PushDevices.Outcome outcome = devices().setPrefs(me, enabled,
                quietStart == null ? "" : quietStart, quietEnd == null ? "" : quietEnd,
                quietZone == null ? "" : quietZone);
        if (outcome.ok()) {
            return true;
        }
        PageFeedback.error("Notification settings not saved", outcome.message());
        return false;
    }

    /** The profile page's Remove: one device by listing id. */
    public boolean removeDeviceFromUi(final Person.Id me, final String id) {
        final boolean removed = devices().remove(me, id, AuditActor.current());
        if (removed) {
            PageFeedback.info("Device removed.");
        } else {
            PageFeedback.warn("That device was already gone.");
        }
        return removed;
    }

    // --- shared with the REST edge ---

    public PushPrefs prefsOf(final Person.Id me) {
        return devices().prefsOf(me);
    }

    public PushDevices.Outcome registerIos(final Person.Id me, final String token, final String environment,
            final String selector, final String label, final String appVersion, final AuditActor actor) {
        return devices().registerIos(me, token, environment, selector, label, appVersion, actor);
    }

    public PushDevices.Outcome registerWeb(final Person.Id me, final String endpoint, final String p256dh,
            final String auth, final String label, final String origin, final AuditActor actor) {
        return devices().registerWeb(me, endpoint, p256dh, auth, label, origin, actor);
    }

    public PushDevices.Outcome setPrefs(final Person.Id me, final Boolean enabled, final String quietStart,
            final String quietEnd, final String timeZone) {
        return devices().setPrefs(me, enabled, quietStart, quietEnd, timeZone);
    }

    public boolean removeDevice(final Person.Id me, final String idOrToken, final AuditActor actor) {
        return devices().remove(me, idOrToken, actor);
    }

    public boolean unsubscribeWeb(final Person.Id me, final String endpoint, final AuditActor actor) {
        return devices().removeWebByEndpoint(me, endpoint, actor);
    }

    /** The VAPID public key browsers subscribe with (base64url), or null when web push is not configured. */
    public String vapidPublicKey() {
        return secrets().vapid().map(PushSecrets.VapidKey::publicKey).orElse(null);
    }

    public PushSender.Report sendTest(final Person.Id me) {
        return notifications().sendTest(me);
    }

    /**
     * The wire shape of the prefs (the GET/PUT prefs body): unset quiet-hours fields render as {@code ""},
     * never null, and devices carry their listing id -- never a token or an endpoint.
     */
    public Map<String, Object> describe(final PushPrefs prefs) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", prefs.isEnabled());
        result.put("quietHoursStart", blankFor(prefs.getQuietHoursStart()));
        result.put("quietHoursEnd", blankFor(prefs.getQuietHoursEnd()));
        result.put("timeZone", blankFor(prefs.getTimeZone()));
        result.put("devices", prefs.getDevices().stream().map(PushCommands::describeDevice).toList());
        return result;
    }

    static Map<String, Object> describeDevice(final PushDevice device) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", device.getKind().wire());
        result.put("id", device.id());
        result.put("label", device.getLabel() == null ? "" : device.getLabel());
        if (device.getKind() == PushDevice.Kind.IOS) {
            result.put("environment", device.getEnvironment());
        } else {
            result.put("origin", device.getOrigin() == null ? "" : device.getOrigin());
        }
        result.put("registeredAt", device.getRegisteredAt());
        result.put("lastSeenAt", device.getLastSeenAt());
        return result;
    }

    private static String blankFor(final String value) {
        return value == null ? "" : value;
    }

    /** Seams: resolved per call so constructing the bean (Weld, at startup) touches no DAO. */
    protected PushDevices devices() {
        return PushDevices.getInstance();
    }

    protected PushSecrets secrets() {
        return PushSecrets.getInstance();
    }

    protected PushNotifications notifications() {
        return PushNotifications.getInstance();
    }
}
