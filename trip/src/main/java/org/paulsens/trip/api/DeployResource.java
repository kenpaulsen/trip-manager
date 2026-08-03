package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.DeployCommands;
import org.paulsens.trip.model.deploy.PipelineStatus;

/**
 * Deployment status, and starting a deployment.
 *
 * <p>The most consequential endpoint on this API. Starting the pipeline costs money and changes what is running
 * in production, so it is gated on {@code siteDeployer} and requires the caller to say so explicitly: a
 * {@code POST} with no body does nothing. That confirmation is not ceremony -- a mobile client with a
 * mis-tapped button and a live session is exactly the failure mode worth a deliberate extra token.
 *
 * <p>It never starts a run implicitly. Nothing here is triggered by a read, a poll, or a status check.
 */
@Slf4j
@Path("deploy")
@TripApi
public class DeployResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.DEPLOY_V1;
    /** What a client must send to mean it. Deliberately not "true" -- a default-constructed body cannot say it. */
    private static final String CONFIRMATION = "deploy";

    @Override
    protected String versionedType() {
        return V1;
    }

    /** Current pipeline state. Read-only, and safe to poll. */
    @GET
    @Path("status")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response status() {
        if (!privileges().has(ApiPrivileges.SITE_DEPLOYER)) {
            return error(403, ApiErrors.FORBIDDEN, "Deploy access required.");
        }
        final DeployCommands deploy = Beans.get(DeployCommands.class);
        final PipelineStatus status = deploy.getStatus();
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", deploy.isConfigured());
        result.put("pipeline", deploy.getPipelineName());
        result.put("overall", status.getOverall());
        result.put("running", status.isRunning());
        result.put("ok", status.isOk());
        result.put("failed", status.isFailed());
        result.put("stages", status.getStages());
        return ok(result);
    }

    /**
     * Starts a pipeline run.
     *
     * <p>Refuses distinctly for each reason rather than returning one opaque failure. The bean reports "no
     * pipeline configured" and "already running" through {@code FacesMessage}s, which go nowhere off a Faces
     * thread and both leave it returning null -- so the two are checked here, where the difference can actually
     * reach the caller. A client seeing "already running" should wait; one seeing "not configured" should stop.
     */
    @POST
    @Path("start")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response start(@HeaderParam(CSRF_HEADER) final String csrf, final StartRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.SITE_DEPLOYER)) {
            return error(403, ApiErrors.FORBIDDEN, "Deploy access required.");
        }
        if (body == null || !CONFIRMATION.equals(body.confirm())) {
            return error(400, ApiErrors.BAD_REQUEST,
                    "Starting a deployment is billable and changes production; send {\"confirm\":\""
                            + CONFIRMATION + "\"} to proceed.");
        }
        final DeployCommands deploy = Beans.get(DeployCommands.class);
        if (!deploy.isConfigured()) {
            return error(409, ApiErrors.CONFLICT, "No pipeline is configured for this deployment.");
        }
        if (deploy.getStatus().isRunning()) {
            return error(409, ApiErrors.CONFLICT, "A run is already in progress; starting another would "
                    + "supersede it.");
        }
        // The actor-taking overload: the no-arg one reads FacesContext and would record the most consequential
        // action in the system as having been started by nobody.
        final String executionId = deploy.start(actor());
        if (executionId == null) {
            return error(502, ApiErrors.INTERNAL, "The pipeline did not start; see the application log.");
        }
        return ok(Map.of("started", true, "executionId", executionId));
    }

    /** Explicit confirmation that the caller means to spend money and change production. */
    public record StartRequest(String confirm) {
    }
}
