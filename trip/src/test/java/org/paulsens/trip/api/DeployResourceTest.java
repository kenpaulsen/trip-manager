package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.DeployCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.deploy.PipelineStageStatus;
import org.paulsens.trip.model.deploy.PipelineStatus;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link DeployResource}, the endpoint that spends money.
 *
 * <p>Starting the pipeline is billable and changes production, so the negative space is the specification here:
 * every test of {@code start} that does not end in a started run must also prove the bean's {@code start} was
 * never reached. A 4xx that had already fired the pipeline would be the worst bug this resource could have.
 */
public class DeployResourceTest extends ResourceTestSupport {

    private static final Person.Id DEPLOYER = Person.Id.from("deploy-admin");

    private DeployCommands deploy;
    private DeployResource resource;

    @BeforeMethod
    public void bindBeans() {
        deploy = bindMock(DeployCommands.class);
        bindMock(PersonCommands.class);
        resource = resource(new DeployResource());
    }

    private static PipelineStatus status(final String stageStatus) {
        return new PipelineStatus("trip-pipeline",
                List.of(new PipelineStageStatus("Build", stageStatus, null, Instant.now(), null)),
                "exec-1", null, true, Instant.now());
    }

    private void neverStarted() {
        Mockito.verify(deploy, Mockito.never()).start(ArgumentMatchers.any(AuditActor.class));
        Mockito.verify(deploy, Mockito.never()).start(ArgumentMatchers.any(Person.Id.class));
    }

    @Test
    public void statusNeedsSiteDeployer() {
        signedInAs(DEPLOYER);

        assertError(resource.status(), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(deploy);
    }

    @Test
    public void statusReportsThePipelineState() {
        signedInAsSiteAdmin(DEPLOYER);
        Mockito.when(deploy.isConfigured()).thenReturn(true);
        Mockito.when(deploy.getPipelineName()).thenReturn("trip-pipeline");
        Mockito.when(deploy.getStatus()).thenReturn(status("Succeeded"));

        final Response response = resource.status();

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("configured"), true);
        Assert.assertEquals(body.get("running"), false);
        Assert.assertEquals(body.get("ok"), true);
    }

    @Test
    public void startRefusesWithoutCsrfWithoutPrivilegeAndWithoutConfirmation() {
        signedInAsSiteAdmin(DEPLOYER);
        assertError(resource.start(null, new DeployResource.StartRequest("deploy")), 403, ApiErrors.CSRF);

        signedInAs(DEPLOYER);
        final DeployResource ordinary = resource(new DeployResource());
        assertError(ordinary.start(CSRF_OK, new DeployResource.StartRequest("deploy")),
                403, ApiErrors.FORBIDDEN);
        neverStarted();
    }

    /** A default-constructed or boolean-ish body must not be able to say "deploy". */
    @Test
    public void startNeedsTheExactConfirmationToken() {
        signedInAsSiteAdmin(DEPLOYER);

        assertError(resource.start(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.start(CSRF_OK, new DeployResource.StartRequest(null)), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.start(CSRF_OK, new DeployResource.StartRequest("true")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.start(CSRF_OK, new DeployResource.StartRequest("DEPLOY")), 400, ApiErrors.BAD_REQUEST);
        neverStarted();
    }

    /** "Not configured" and "already running" need different answers: one means stop, the other means wait. */
    @Test
    public void startDistinguishesUnconfiguredFromAlreadyRunning() {
        signedInAsSiteAdmin(DEPLOYER);
        Mockito.when(deploy.isConfigured()).thenReturn(false);

        assertError(resource.start(CSRF_OK, new DeployResource.StartRequest("deploy")),
                409, ApiErrors.CONFLICT);

        Mockito.when(deploy.isConfigured()).thenReturn(true);
        Mockito.when(deploy.getStatus()).thenReturn(status("InProgress"));

        assertError(resource.start(CSRF_OK, new DeployResource.StartRequest("deploy")),
                409, ApiErrors.CONFLICT);
        neverStarted();
    }

    @Test
    public void aConfirmedStartIsAttributedToTheActor() {
        signedInAsSiteAdmin(DEPLOYER);
        session("loginEmail", "deployer@example.com");
        Mockito.when(deploy.isConfigured()).thenReturn(true);
        Mockito.when(deploy.getStatus()).thenReturn(status("Succeeded"));
        Mockito.when(deploy.start(ArgumentMatchers.any(AuditActor.class))).thenReturn("exec-42");

        final Response response = resource.start(CSRF_OK, new DeployResource.StartRequest("deploy"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("executionId"), "exec-42");
        // The actor-taking overload: the no-arg one reads FacesContext and records nobody.
        Mockito.verify(deploy).start(ArgumentMatchers.argThat((AuditActor actor) ->
                "deployer@example.com".equals(actor.email())));
    }

    @Test
    public void aPipelineThatDidNotStartIs502() {
        signedInAsSiteAdmin(DEPLOYER);
        Mockito.when(deploy.isConfigured()).thenReturn(true);
        Mockito.when(deploy.getStatus()).thenReturn(status("Succeeded"));
        Mockito.when(deploy.start(ArgumentMatchers.any(AuditActor.class))).thenReturn(null);

        assertError(resource.start(CSRF_OK, new DeployResource.StartRequest("deploy")),
                502, ApiErrors.INTERNAL);
    }

    @Test
    public void theProducedTypeIsTheDeployMediaType() {
        Assert.assertEquals(new DeployResource().versionedType(), ApiMediaTypes.DEPLOY_V1);
    }
}
