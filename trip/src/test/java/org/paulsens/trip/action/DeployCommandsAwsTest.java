package org.paulsens.trip.action;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.function.Consumer;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.deploy.PipelineStatus;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.ActionExecution;
import software.amazon.awssdk.services.codepipeline.model.ActionState;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateRequest;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateResponse;
import software.amazon.awssdk.services.codepipeline.model.StageExecution;
import software.amazon.awssdk.services.codepipeline.model.StageState;
import software.amazon.awssdk.services.codepipeline.model.StartPipelineExecutionRequest;
import software.amazon.awssdk.services.codepipeline.model.StartPipelineExecutionResponse;

/**
 * {@link DeployCommands} with the CodePipeline client injected -- the most consequential bean in the admin UI.
 *
 * <p>Two refusals carry the money: an unknown actor cannot start a run (no unattributed deploys, ever), and a
 * run in progress refuses a second start (CodePipeline would happily supersede the build underway).
 */
public class DeployCommandsAwsTest {

    private static final String PIPELINE_PROP = "trip.pipeline.name";

    private DeployCommands deploy;
    private CodePipelineClient pipeline;

    @BeforeMethod
    public void wireClient() throws Exception {
        deploy = new DeployCommands();
        pipeline = Mockito.mock(CodePipelineClient.class);
        final Field field = DeployCommands.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(deploy, pipeline);
        System.setProperty(PIPELINE_PROP, "trip-pipeline");
    }

    @AfterMethod(alwaysRun = true)
    public void clearProperty() {
        System.clearProperty(PIPELINE_PROP);
    }

    private static StageState stage(final String name, final String status, final String summary,
            final String url) {
        return StageState.builder()
                .stageName(name)
                .latestExecution(StageExecution.builder()
                        .pipelineExecutionId("exec-7").status(status).build())
                .actionStates(ActionState.builder()
                        .actionName(name + "-action")
                        .latestExecution(ActionExecution.builder()
                                .summary(summary)
                                .lastStatusChange(Instant.now())
                                .externalExecutionUrl(url)
                                .build())
                        .build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private void pipelineStateAnswers(final GetPipelineStateResponse response) {
        Mockito.when(pipeline.getPipelineState(ArgumentMatchers.<Consumer<GetPipelineStateRequest.Builder>>any()))
                .thenReturn(response);
    }

    @Test
    public void unconfiguredMeansEverythingExplainsItself() {
        System.clearProperty(PIPELINE_PROP);
        if (System.getenv(DeployCommands.PIPELINE_VAR) != null) {
            throw new org.testng.SkipException(DeployCommands.PIPELINE_VAR + " is set in this environment");
        }

        Assert.assertFalse(deploy.isConfigured());
        Assert.assertEquals(deploy.getPipelineName(), "");
        final PipelineStatus status = deploy.getStatus();
        Assert.assertFalse(status.isConfigured());
        Assert.assertNull(deploy.start(new AuditActor("a@x", "id")), "Nothing to start");
        Mockito.verifyNoInteractions(pipeline);
    }

    @Test
    public void statusMapsStagesSummariesAndTheCommitMessage() {
        pipelineStateAnswers(GetPipelineStateResponse.builder()
                .stageStates(
                        stage("Source", "Succeeded",
                                "{\"ProviderType\":\"GitHub\",\"CommitMessage\":\"Fix the itinerary page\"}",
                                "https://console.aws.example/source"),
                        stage("Build", "InProgress", "plain text summary",
                                "http://not-https.example/x"))
                .build());

        final PipelineStatus status = deploy.getStatus();

        Assert.assertTrue(status.isConfigured());
        Assert.assertTrue(status.isRunning());
        Assert.assertEquals(status.getStages().size(), 2);
        Assert.assertEquals(status.getStages().get(0).getSummary(), "Fix the itinerary page",
                "The commit message is dug out of the source stage's JSON blob");
        Assert.assertEquals(status.getStages().get(1).getSummary(), "plain text summary");
        Assert.assertEquals(status.getStages().get(0).getConsoleUrl(), "https://console.aws.example/source");
        Assert.assertNull(status.getStages().get(1).getConsoleUrl(),
                "A non-https link from the API must not be rendered as a clickable URL");
        Assert.assertEquals(status.getExecutionId(), "exec-7");
    }

    @Test
    public void anUnreadablePipelineReportsFailureInsteadOfThrowing() {
        Mockito.when(pipeline.getPipelineState(ArgumentMatchers.<Consumer<GetPipelineStateRequest.Builder>>any()))
                .thenThrow(new IllegalStateException("User ... is not authorized to perform ..."));

        final PipelineStatus status = deploy.getStatus();

        Assert.assertNotNull(status.getError());
        Assert.assertTrue(status.getError().contains("cdk deploy TripApp"),
                "Access denied must read as an undeployed grant, not a broken feature");
    }

    @Test
    public void startRefusesAnUnknownActor() {
        Assert.assertThrows(IllegalArgumentException.class, () -> deploy.start((AuditActor) null));
        Assert.assertThrows(IllegalArgumentException.class, () -> deploy.start((Person.Id) null));
        Assert.assertThrows(IllegalArgumentException.class, () -> deploy.startAs(" "));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> deploy.start(new AuditActor(null, null)));
        Mockito.verifyNoInteractions(pipeline);
    }

    @Test
    public void startRefusesWhileARunIsInProgress() {
        pipelineStateAnswers(GetPipelineStateResponse.builder()
                .stageStates(stage("Build", "InProgress", null, null))
                .build());

        Assert.assertNull(deploy.start(new AuditActor("deployer@x", "d-id")));

        Mockito.verify(pipeline, Mockito.never())
                .startPipelineExecution(ArgumentMatchers.<Consumer<StartPipelineExecutionRequest.Builder>>any());
    }

    @Test
    public void aCleanStartAnswersTheExecutionId() {
        pipelineStateAnswers(GetPipelineStateResponse.builder()
                .stageStates(stage("Build", "Succeeded", null, null))
                .build());
        Mockito.when(pipeline.startPipelineExecution(
                ArgumentMatchers.<Consumer<StartPipelineExecutionRequest.Builder>>any()))
                .thenReturn(StartPipelineExecutionResponse.builder().pipelineExecutionId("exec-42").build());

        Assert.assertEquals(deploy.start(new AuditActor("deployer@x", "d-id")), "exec-42");
    }

    @Test
    public void aFailedStartIsNullAndAudited() {
        pipelineStateAnswers(GetPipelineStateResponse.builder()
                .stageStates(stage("Build", "Succeeded", null, null))
                .build());
        Mockito.when(pipeline.startPipelineExecution(
                ArgumentMatchers.<Consumer<StartPipelineExecutionRequest.Builder>>any()))
                .thenThrow(new IllegalStateException("throttled"));

        Assert.assertNull(deploy.start(new AuditActor("deployer@x", "d-id")));
    }

    @Test
    public void startByPersonIdResolvesTheActor() {
        pipelineStateAnswers(GetPipelineStateResponse.builder()
                .stageStates(stage("Build", "Succeeded", null, null))
                .build());
        Mockito.when(pipeline.startPipelineExecution(
                ArgumentMatchers.<Consumer<StartPipelineExecutionRequest.Builder>>any()))
                .thenReturn(StartPipelineExecutionResponse.builder().pipelineExecutionId("exec-43").build());

        // An unknown id still deploys: the actor carries the id even when the person record is blank.
        Assert.assertEquals(deploy.start(Person.Id.from("some-person")), "exec-43");
    }

    @Test
    public void startAsAcceptsANameOrTheSystemSentinel() {
        pipelineStateAnswers(GetPipelineStateResponse.builder()
                .stageStates(stage("Build", "Succeeded", null, null))
                .build());
        Mockito.when(pipeline.startPipelineExecution(
                ArgumentMatchers.<Consumer<StartPipelineExecutionRequest.Builder>>any()))
                .thenReturn(StartPipelineExecutionResponse.builder().pipelineExecutionId("exec-44").build());

        Assert.assertEquals(deploy.startAs("ops@example.org"), "exec-44");
        Assert.assertEquals(deploy.startAs(AuditActor.SYSTEM_ID), "exec-44");
    }

    @Test
    public void summariesFallBackToRawTextWhenNotTheExpectedJson() {
        Assert.assertEquals(deploy.commitMessageOrRaw("plain words"), "plain words");
        Assert.assertEquals(deploy.commitMessageOrRaw("{\"CommitMessage\":\"the fix\"}"), "the fix");
        Assert.assertEquals(deploy.commitMessageOrRaw("{\"SomethingElse\":1}"), "{\"SomethingElse\":1}");
        Assert.assertEquals(deploy.commitMessageOrRaw("{not json"), "{not json");
    }

    @Test
    public void localTimeConvertsForDisplay() {
        Assert.assertNull(deploy.localTime(null));
        Assert.assertNotNull(deploy.localTime(Instant.now()));
    }
}
