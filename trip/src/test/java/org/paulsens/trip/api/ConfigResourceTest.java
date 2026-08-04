package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.SettingSection;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link ConfigResource}: the property sheet over the wire.
 *
 * <p>The declared-names-only rule on the PUT is the behaviour most worth keeping honest. An unrecognised key
 * would create a row nothing reads -- exactly the orphan rows {@code GET /config/unknown} exists to surface --
 * so the endpoint must refuse it rather than helpfully storing it.
 */
public class ConfigResourceTest extends ResourceTestSupport {

    private static final Person.Id ADMIN = Person.Id.from("config-admin");

    /** A name guaranteed declared, wherever the registry goes next: taken from the registry itself. */
    private static final String KNOWN = KnownSettings.all().get(0).getName();

    private ConfigCommands config;
    private ConfigResource resource;

    @BeforeMethod
    public void bindBeans() {
        config = bindMock(ConfigCommands.class);
        bindMock(PersonCommands.class);
        resource = resource(new ConfigResource());
    }

    @Test
    public void everythingHereNeedsConfigAdmin() {
        signedInAs(ADMIN);

        assertError(resource.settings(), 403, ApiErrors.FORBIDDEN);
        assertError(resource.unknown(), 403, ApiErrors.FORBIDDEN);
        assertError(resource.saveKnown(CSRF_OK, Map.of(KNOWN, "x")), 403, ApiErrors.FORBIDDEN);
        assertError(resource.createUndeclared(CSRF_OK, new ConfigResource.NewSetting("a", "b", null, null)),
                403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(config);
    }

    @Test
    public void settingsGroupsTheSheetAndMarksDefaults() {
        signedInAsSiteAdmin(ADMIN);
        final SettingDef stored = new SettingDef("mail.from", Config.Type.STRING, "default@x", "From", "d");
        final SettingDef unset = new SettingDef("mail.bcc", Config.Type.STRING, "", "Bcc", "d");
        Mockito.when(config.getSections())
                .thenReturn(List.of(new SettingSection("Mail", null, List.of(stored, unset))));
        Mockito.when(config.getKnownValues()).thenReturn(Map.of("mail.from", "real@x"));

        final Response response = resource.settings();

        assertOk(response);
        final List<?> sections = (List<?>) response.getEntity();
        Assert.assertEquals(sections.size(), 1);
        @SuppressWarnings("unchecked")
        final Map<String, Object> section = (Map<String, Object>) sections.get(0);
        Assert.assertEquals(section.get("title"), "Mail");
        final List<?> settings = (List<?>) section.get("settings");
        @SuppressWarnings("unchecked")
        final Map<String, Object> first = (Map<String, Object>) settings.get(0);
        // A stored value and its default stay distinguishable, so a client can say "using the default" honestly.
        Assert.assertEquals(first.get("value"), "real@x");
        Assert.assertEquals(first.get("defaultValue"), "default@x");
        Assert.assertEquals(first.get("usingDefault"), false);
        @SuppressWarnings("unchecked")
        final Map<String, Object> second = (Map<String, Object>) settings.get(1);
        Assert.assertEquals(second.get("usingDefault"), true);
    }

    @Test
    public void unknownListsTheOrphanRows() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(config.getUnknown()).thenReturn(List.of(
                new Config("old.key", "v", null, null, LocalDateTime.now(), "who")));

        final Response response = resource.unknown();

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> row = (Map<String, Object>) ((List<?>) response.getEntity()).get(0);
        Assert.assertEquals(row.get("name"), "old.key");
        Assert.assertEquals(row.get("modifiedBy"), "who");
    }

    @Test
    public void savingNeedsCsrfAndABody() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.saveKnown(null, Map.of(KNOWN, "x")), 403, ApiErrors.CSRF);
        assertError(resource.saveKnown(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.saveKnown(CSRF_OK, Map.of()), 400, ApiErrors.BAD_REQUEST);
    }

    /** The undeclared-key refusal: storing it would create a row nothing reads. */
    @Test
    public void savingAnUndeclaredNameIsRefusedByName() {
        signedInAsSiteAdmin(ADMIN);

        final Response response = resource.saveKnown(CSRF_OK, Map.of("no.such.setting", "x"));

        assertError(response, 400, ApiErrors.BAD_REQUEST);
        Mockito.verify(config, Mockito.never())
                .saveKnown(ArgumentMatchers.anyMap(), ArgumentMatchers.anyString());
    }

    @Test
    public void savingDeclaredSettingsStampsTheActorsEmail() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        Mockito.when(config.saveKnown(ArgumentMatchers.anyMap(), ArgumentMatchers.anyString())).thenReturn(true);

        assertOk(resource.saveKnown(CSRF_OK, Map.of(KNOWN, "new-value")));

        Mockito.verify(config).saveKnown(Map.of(KNOWN, "new-value"), "admin@example.com");
    }

    @Test
    public void anActorWithNoEmailIsStampedAsApi() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(config.saveKnown(ArgumentMatchers.anyMap(), ArgumentMatchers.anyString())).thenReturn(true);

        assertOk(resource.saveKnown(CSRF_OK, Map.of(KNOWN, "v")));

        Mockito.verify(config).saveKnown(ArgumentMatchers.anyMap(), ArgumentMatchers.eq("api"));
    }

    @Test
    public void aFailedSaveIsReported() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(config.saveKnown(ArgumentMatchers.anyMap(), ArgumentMatchers.anyString())).thenReturn(false);

        assertError(resource.saveKnown(CSRF_OK, Map.of(KNOWN, "v")), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void creatingAnUndeclaredSettingNeedsANonBlankName() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.createUndeclared(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.createUndeclared(CSRF_OK, new ConfigResource.NewSetting("  ", "v", null, null)),
                400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void creatingAnUndeclaredSettingGoesThroughSaveNew() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(config.saveNew(ArgumentMatchers.eq("future.key"), ArgumentMatchers.eq("v"),
                ArgumentMatchers.eq("STRING"), ArgumentMatchers.eq("d"), ArgumentMatchers.anyString()))
                .thenReturn(true);

        assertOk(resource.createUndeclared(CSRF_OK,
                new ConfigResource.NewSetting("future.key", "v", "STRING", "d")));
    }

    /**
     * saveNew reports its refusal through a FacesMessage, which goes nowhere off a Faces thread; the client must
     * get a status code instead of a growl it will never see.
     */
    @Test
    public void aRefusedCreateBecomes422RatherThanASilentSuccess() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(config.saveNew(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(false);

        assertError(resource.createUndeclared(CSRF_OK, new ConfigResource.NewSetting("k", "v", null, null)),
                422, ApiErrors.VALIDATION_FAILED);
    }

    @Test
    public void theProducedTypeIsTheConfigMediaType() {
        Assert.assertEquals(new ConfigResource().versionedType(), ApiMediaTypes.CONFIG_V1);
    }
}
