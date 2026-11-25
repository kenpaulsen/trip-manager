package org.paulsens.trip.action;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.deploy.PipelineStageStatus;
import org.paulsens.trip.model.deploy.PipelineStatus;
import org.paulsens.trip.util.Env;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.ActionState;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateResponse;
import software.amazon.awssdk.services.codepipeline.model.StageState;
import software.amazon.awssdk.services.codepipeline.model.StartPipelineExecutionResponse;

/**
 * Starting the deployment pipeline, and showing what it is doing, from the admin UI.
 *
 * <p>This exists so releasing does not require the AWS console or a laptop with credentials. It is guarded by
 * the trip-wide {@code siteDeployer} privilege rather than by {@code admin}: deploying is a different kind of
 * act from editing a trip, and the set of people who should be able to do it is smaller than the set of site
 * administrators.
 *
 * <p><b>Starting a run costs money and replaces the running task.</b> CodeBuild minutes are billed, and the
 * blue/green deployment at the end swaps out the very container serving this page. Neither is a reason not to
 * have the button -- it is what the pipeline is for -- but both are reasons the page confirms first, refuses
 * while a run is already in progress, and records every start in the audit trail.
 *
 * <p>Configured by {@code TRIP_PIPELINE_NAME}. When it is unset -- laptops, tests -- everything here answers
 * "not configured" and the page renders and explains itself, exactly as {@link MediaCommands} does without a
 * bucket. Nothing in this class touches AWS until something asks it to.
 *
 * <p>The synchronous client, like {@code MediaCommands} and unlike the DAO's async ones: both operations are
 * user-initiated on a request thread that has nothing else to do and must report the outcome in the response.
 *
 * <p>A second, optional pipeline runs the FULL browser-test suite on demand ({@code TRIP_WEBTEST_PIPELINE_NAME}):
 * the deploy pipeline runs only a smoke subset, and the full suite otherwise runs only on a laptop before a push.
 * It deploys nothing, so it is the same start-and-watch with its own words, and the same refusals: a known actor
 * is required, a run in progress is never superseded, and every start is audited.
 */
@Slf4j
@Named("deploy")
@ApplicationScoped
public class DeployCommands {

    static final String PIPELINE_VAR = "TRIP_PIPELINE_NAME";
    static final String SUITE_PIPELINE_VAR = "TRIP_WEBTEST_PIPELINE_NAME";
    static final String REGION_VAR = "TRIP_DYNAMO_REGION";
    private static final String DEFAULT_REGION = "us-west-2";
    /** The zone the site operates in; the same one the rest of the admin UI reads dates in. */
    private static final ZoneId SITE_ZONE = ZoneId.of("America/Los_Angeles");

    /** What a start is called in the audit trail and on the page, per pipeline. */
    private record RunKind(String noun, String busy, String busyDetail, String started, String startedDetail,
            String notStarted) {
    }

    private static final RunKind DEPLOYMENT = new RunKind("a deployment",
            "feedback.deployCommands.alreadyDeploying", "feedback.deployCommands.alreadyDeployingDetail",
            "feedback.deployCommands.started", "feedback.deployCommands.startedDetail",
            "feedback.deployCommands.notStarted");
    private static final RunKind FULL_SUITE = new RunKind("a full webtest run",
            "feedback.deployCommands.alreadyTesting", "feedback.deployCommands.alreadyTestingDetail",
            "feedback.deployCommands.suiteStarted", "feedback.deployCommands.suiteStartedDetail",
            "feedback.deployCommands.suiteNotStarted");

    private volatile CodePipelineClient client;

    /** Whether this deployment has a pipeline at all. False on a laptop and in every test. */
    public boolean isConfigured() {
        return pipelineName() != null;
    }

    public String getPipelineName() {
        final String name = pipelineName();
        return name == null ? "" : name;
    }

    /**
     * The current state of every stage.
     *
     * <p>Never throws: an unreadable pipeline is reported as {@link PipelineStatus#failed}, because a page that
     * blows up tells an operator less than one that says why it is empty. In particular an
     * {@code AccessDeniedException} here means the task role has not been granted the pipeline permissions yet,
     * which is a deploy step, not a bug -- and the message says so.
     */
    public PipelineStatus getStatus() {
        return statusOf(pipelineName());
    }

    /** Whether this deployment can run the full browser-test suite on demand. */
    public boolean isFullSuiteConfigured() {
        return suitePipelineName() != null;
    }

    /** The full-suite pipeline's stages, read like {@link #getStatus()} (never throws). */
    public PipelineStatus getFullSuiteStatus() {
        return statusOf(suitePipelineName());
    }

    private PipelineStatus statusOf(final String name) {
        if (name == null) {
            return PipelineStatus.unconfigured(FakeData.isLocal());
        }
        try {
            return toStatus(name, client().getPipelineState(req -> req.name(name)));
        } catch (final RuntimeException ex) {
            log.error("Unable to read the state of pipeline '" + name + "'", ex);
            return PipelineStatus.failed(name, describe(ex, name));
        }
    }

    /**
     * Starts a pipeline run on behalf of a signed-in person.
     *
     * <p>Refused while a run is already in progress. CodePipeline would accept the request and supersede the
     * running execution, which wastes the build already underway and -- more to the point -- makes two people
     * clicking at once look like one deployment that mysteriously restarted.
     *
     * <p>The id is resolved to an actor here rather than by the caller so that a page or a resource holding a
     * {@code Person.Id} does not have to know how audit identity is assembled.
     *
     * @return the new execution id, or null if nothing was started.
     */
    public String start(final Person.Id personId) {
        if (personId == null) {
            throw new IllegalArgumentException("A deployment must record who started it.");
        }
        final Person person = new PersonCommands().getPerson(personId);
        // The email may be absent -- getPerson answers a blank Person for an unknown id. The actor still
        // carries the id, which is enough to attribute the deploy; a missing name must not block it.
        final String email = (person == null) ? null : person.getEmail();
        return start(new AuditActor(email, personId.getValue()));
    }

    /**
     * Starts the pipeline on behalf of a named actor -- an email address, or {@link AuditActor#SYSTEM_ID} when
     * the application triggered the deployment itself.
     *
     * <p>Exists so a caller that has a name but no {@code Person.Id} still has to say something. It is
     * deliberately not possible to start a deployment without naming somebody.
     *
     * <p>Named {@code startAs} rather than being a third {@code start} overload: EL resolves overloads by the
     * runtime type of the argument, and a page passing a session value that is sometimes a {@code Person.Id}
     * and sometimes a {@code String} would silently pick a different method depending on which page set it.
     * For the same reason the deploy page passes {@code audit.currentActor} -- always an {@link AuditActor},
     * never whatever type {@code sessionScope.userId} happens to hold on the page that set it -- rather than an
     * id whose runtime type varies.
     */
    public String startAs(final String actorName) {
        if (actorName == null || actorName.isBlank()) {
            throw new IllegalArgumentException("A deployment must record who started it.");
        }
        return start(AuditActor.SYSTEM_ID.equalsIgnoreCase(actorName.trim())
                ? AuditActor.system() : new AuditActor(actorName.trim(), null));
    }

    /**
     * Starts the pipeline, recording {@code who} as the actor.
     *
     * <p>There is deliberately NO argument-less form. One resolving the actor through {@code FacesContext}
     * would record a deploy triggered over the API, or from any pool thread, as having been started by nobody.
     * Starting a deployment is billable and changes what is running in production, so "somebody deployed" is
     * not an acceptable record of it -- and the failure is silent, which is how it would survive review.
     */
    public String start(final AuditActor who) {
        return startRun(pipelineName(), who, DEPLOYMENT);
    }

    /**
     * Starts the full browser-test suite on behalf of a named actor, with the same refusals as {@link
     * #start(AuditActor)}: no unattributed run, none while one is in progress. It tests the newest image built
     * from the current {@code main} and deploys nothing.
     *
     * @return the new execution id, or null if nothing was started.
     */
    public String startFullSuite(final AuditActor who) {
        return startRun(suitePipelineName(), who, FULL_SUITE);
    }

    private String startRun(final String name, final AuditActor who, final RunKind kind) {
        if (who == null || !who.isKnown()) {
            throw new IllegalArgumentException("A pipeline run must record who started it.");
        }
        if (name == null) {
            growl(FacesMessage.SEVERITY_ERROR, Text.get("feedback.deployCommands.noPipeline"),
                    Text.get("feedback.deployCommands.noPipelineDetail"));
            return null;
        }
        // No fallback to AuditActor.current(): the guard above already required a known actor, and a fallback
        // here is exactly how an unattributed deploy would creep back in.
        final AuditActor actor = who;
        final PipelineStatus current = statusOf(name);
        if (current.isRunning()) {
            growl(FacesMessage.SEVERITY_WARN, Text.get(kind.busy()), Text.get(kind.busyDetail()));
            return null;
        }
        try {
            final StartPipelineExecutionResponse response = client().startPipelineExecution(req -> req.name(name));
            final String executionId = response.pipelineExecutionId();
            Audit.builder(AuditAction.DEPLOY, AuditOutcome.SUCCESS)
                    .actor(actor)
                    .target(AuditEventBuilder.TARGET_PIPELINE, name)
                    .message("Started " + kind.noun() + " of '" + name + "'; execution " + executionId)
                    .log();
            growl(FacesMessage.SEVERITY_INFO, Text.get(kind.started()), Text.get(kind.startedDetail(), executionId));
            return executionId;
        } catch (final RuntimeException ex) {
            log.error("Unable to start pipeline '" + name + "'", ex);
            // Audited as a FAILURE too. A refused deployment attempt is exactly as interesting as a successful
            // one -- more so, if someone is trying to deploy and cannot.
            Audit.builder(AuditAction.DEPLOY, AuditOutcome.FAILURE)
                    .actor(actor)
                    .target(AuditEventBuilder.TARGET_PIPELINE, name)
                    .message("Failed to start " + kind.noun() + " of '" + name + "': " + ex.getMessage())
                    .log();
            growl(FacesMessage.SEVERITY_ERROR, Text.get(kind.notStarted()), describe(ex, name));
            return null;
        }
    }

    /**
     * An {@link Instant} as a local date-time for display.
     *
     * <p>Needed because {@code f:convertDateTime} has no {@code instant} type -- the valid ones are
     * {@code localDateTime}, {@code zonedDateTime} and friends -- and handing it an Instant throws at RENDER
     * time. That surfaces as the container's default error page, which is {@code /index.jsf}, so the symptom
     * is the admin silently landing on the public home page rather than seeing an error. The audit page solves
     * it the same way, via {@code AuditViewCommands.utcTime}.
     *
     * <p>The site's own zone rather than UTC: this page is read by someone deciding whether a release is
     * stuck, and how long ago a stage changed has to be obvious at a glance.
     */
    public LocalDateTime localTime(final Instant when) {
        return when == null ? null : LocalDateTime.ofInstant(when, SITE_ZONE);
    }

    PipelineStatus toStatus(final String name, final GetPipelineStateResponse response) {
        final List<PipelineStageStatus> stages = new ArrayList<>();
        for (final StageState stage : response.stageStates()) {
            stages.add(toStage(stage));
        }
        return new PipelineStatus(name, stages, executionIdOf(response), null, true, Instant.now());
    }

    PipelineStageStatus toStage(final StageState stage) {
        final String status = stage.latestExecution() == null ? null
                : stage.latestExecution().statusAsString();
        // The first action carries the console link and, for a source stage, the commit message. A stage with
        // several actions (Source has two repos) reports the first; the link goes to the stage either way.
        final ActionState first = stage.actionStates().isEmpty() ? null : stage.actionStates().get(0);
        return new PipelineStageStatus(stage.stageName(), status, summaryOf(stage),
                lastChangedOf(first), consoleUrlOf(first));
    }

    /**
     * The best one-line summary a stage offers.
     *
     * <p>Source actions put a JSON blob in {@code summary} ({@code {"ProviderType":..,"CommitMessage":..}}),
     * so the commit message is dug out of it -- that is the thing worth reading. Anything unparseable falls
     * back to the raw text rather than being dropped: a summary nobody anticipated is still information.
     */
    String summaryOf(final StageState stage) {
        final List<String> parts = new ArrayList<>();
        for (final ActionState action : stage.actionStates()) {
            if (action.latestExecution() != null && action.latestExecution().summary() != null) {
                parts.add(commitMessageOrRaw(action.latestExecution().summary()));
            }
        }
        return parts.isEmpty() ? null : String.join(" | ", parts.stream().distinct().toList());
    }

    String commitMessageOrRaw(final String summary) {
        final String trimmed = summary.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            final JsonNode node = DAO.getInstance().getMapper().readTree(trimmed);
            final JsonNode commit = node.get("CommitMessage");
            return commit == null || commit.asText().isBlank() ? trimmed : commit.asText().trim();
        } catch (final Exception ex) {
            log.debug("Pipeline summary was not the JSON shape we expected; showing it raw", ex);
            return trimmed;
        }
    }

    private static Instant lastChangedOf(final ActionState action) {
        return (action == null || action.latestExecution() == null) ? null
                : action.latestExecution().lastStatusChange();
    }

    /**
     * The console link for a stage, preferring the specific execution over the project.
     *
     * <p>Only ever an {@code https} console URL, and it is checked rather than assumed: these strings come from
     * an AWS response, but they are rendered as a link on an admin page, and a link is the one thing on this
     * page that a person will click without reading.
     */
    static String consoleUrlOf(final ActionState action) {
        if (action == null) {
            return null;
        }
        final String execution = action.latestExecution() == null ? null
                : action.latestExecution().externalExecutionUrl();
        final String url = (execution == null || execution.isBlank()) ? action.entityUrl() : execution;
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return null;
        }
        return url;
    }

    private static String executionIdOf(final GetPipelineStateResponse response) {
        return response.stageStates().stream()
                .filter(stage -> stage.latestExecution() != null)
                .map(stage -> stage.latestExecution().pipelineExecutionId())
                .findFirst().orElse(null);
    }

    /**
     * An operator-facing sentence for an SDK failure.
     *
     * <p>Access denied is called out by name because it is the expected state between shipping this page and
     * granting the task role its pipeline permissions -- without this it reads as a broken feature rather than
     * an undeployed one.
     */
    static String describe(final RuntimeException ex, final String name) {
        final String type = ex.getClass().getSimpleName();
        if (type.contains("AccessDenied") || String.valueOf(ex.getMessage()).contains("not authorized")) {
            return Text.get("feedback.deployCommands.notPermitted");
        }
        if (type.contains("PipelineNotFound")) {
            return Text.get("feedback.deployCommands.pipelineNotFound", name);
        }
        return type + ": " + ex.getMessage();
    }

    private static void growl(final FacesMessage.Severity severity, final String summary, final String detail) {
        PageFeedback.message(severity, summary, detail);
    }

    private static String pipelineName() {
        return Env.setting(PIPELINE_VAR);
    }

    private static String suitePipelineName() {
        return Env.setting(SUITE_PIPELINE_VAR);
    }

    private CodePipelineClient client() {
        CodePipelineClient existing = client;
        if (existing == null) {
            synchronized (this) {
                existing = client;
                if (existing == null) {
                    final String region = Env.setting(REGION_VAR);
                    existing = CodePipelineClient.builder()
                            .region(Region.of(region == null ? DEFAULT_REGION : region))
                            .credentialsProvider(DefaultCredentialsProvider.builder().build())
                            .build();
                    client = existing;
                }
            }
        }
        return existing;
    }
}
