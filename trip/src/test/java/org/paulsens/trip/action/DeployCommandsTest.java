package org.paulsens.trip.action;

import java.time.Instant;
import java.util.List;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.deploy.PipelineStageStatus;
import org.paulsens.trip.model.deploy.PipelineStatus;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.codepipeline.model.ActionExecution;
import software.amazon.awssdk.services.codepipeline.model.ActionState;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateResponse;
import software.amazon.awssdk.services.codepipeline.model.StageExecution;
import software.amazon.awssdk.services.codepipeline.model.StageState;

/**
 * Turning a CodePipeline response into the few things the Deploy page shows.
 *
 * <p>Everything here is the mapping, not the calls: {@code getStatus} and {@code start} are one SDK call each
 * and are covered by the page rendering and by actually deploying. What is worth pinning is the shape the page
 * depends on — which is also where the surprises live, since the interesting fields arrive as a JSON blob
 * inside a string field, and console URLs arrive as free text.
 */
public class DeployCommandsTest {

    private final DeployCommands deploy = new DeployCommands();

    @BeforeClass
    void beforeClass() {
        // commitMessageOrRaw parses through the shared ObjectMapper, which comes off the DAO.
        FakeData.initFakeData();
    }

    @Test
    public void withNoPipelineConfiguredTheFeatureIsInertRatherThanBroken() {
        // The state on a laptop and in every test. The page must render and explain itself, and above all
        // nothing here may reach for AWS credentials that are not there.
        Assert.assertFalse(deploy.isConfigured());
        final PipelineStatus status = deploy.getStatus();
        Assert.assertSame(status, PipelineStatus.UNCONFIGURED);
        Assert.assertEquals(status.getOverall(), "Not configured");
        Assert.assertTrue(status.getStages().isEmpty());
        Assert.assertNotNull(status.getError(), "an unconfigured page still has to say why it is empty");
    }

    @Test
    public void aSourceStageShowsTheCommitMessage() {
        // CodePipeline hides the one genuinely useful string -- what is being deployed -- inside a JSON blob
        // in its summary field.
        Assert.assertEquals(
                deploy.commitMessageOrRaw(
                        "{\"ProviderType\":\"GitHub\",\"CommitMessage\":\"Fix the background colour\"}"),
                "Fix the background colour");
    }

    @Test
    public void anUnexpectedSummaryIsShownRatherThanDropped() {
        // A summary nobody anticipated is still information. Dropping it would leave the column blank with no
        // hint that there was something there.
        Assert.assertEquals(deploy.commitMessageOrRaw("Deployment d-5ALHBW24K succeeded"),
                "Deployment d-5ALHBW24K succeeded");
        Assert.assertEquals(deploy.commitMessageOrRaw("{not json at all"), "{not json at all");
        Assert.assertEquals(deploy.commitMessageOrRaw("{\"ProviderType\":\"GitHub\"}"),
                "{\"ProviderType\":\"GitHub\"}", "no commit message means show the blob, not an empty cell");
    }

    @Test
    public void onlyHttpsConsoleLinksAreOffered() {
        // These strings come from an AWS response, but they are rendered as a link on an admin page -- and a
        // link is the one thing on that page someone will click without reading. Checked, not assumed.
        Assert.assertNull(DeployCommands.consoleUrlOf(actionWithUrl("javascript:alert(1)")));
        Assert.assertNull(DeployCommands.consoleUrlOf(actionWithUrl("http://console.aws.amazon.com/x")));
        Assert.assertNull(DeployCommands.consoleUrlOf(null));
        Assert.assertEquals(DeployCommands.consoleUrlOf(actionWithUrl("https://console.aws.amazon.com/x")),
                "https://console.aws.amazon.com/x");
    }

    @Test
    public void theExecutionLinkBeatsTheProjectLink() {
        final ActionState action = ActionState.builder()
                .entityUrl("https://console.aws.amazon.com/project")
                .latestExecution(ActionExecution.builder()
                        .externalExecutionUrl("https://console.aws.amazon.com/this-run").build())
                .build();
        Assert.assertEquals(DeployCommands.consoleUrlOf(action), "https://console.aws.amazon.com/this-run",
                "someone opening the console mid-run wants THIS run, not the project page");
    }

    @Test
    public void aRunningStageMakesTheWholePipelineRunning() {
        // Drives both the auto-refresh and the refusal to start a second run, so it is worth being explicit:
        // running must beat a stale failure on an earlier stage, or the page hides the run being watched.
        final PipelineStatus status = deploy.toStatus("trip-manager", GetPipelineStateResponse.builder()
                .stageStates(stage("Source", "Failed"), stage("Build", "InProgress"))
                .build());
        Assert.assertTrue(status.isRunning());
        Assert.assertEquals(status.getOverall(), "Running");
        Assert.assertTrue(status.isOk(), "reading the state succeeded; a failed STAGE is not a failed READ");
    }

    @Test
    public void aFailedStageIsReportedAsFailed() {
        final PipelineStatus status = deploy.toStatus("trip-manager", GetPipelineStateResponse.builder()
                .stageStates(stage("Source", "Succeeded"), stage("Build", "Failed"))
                .build());
        Assert.assertFalse(status.isRunning());
        Assert.assertEquals(status.getOverall(), "Failed");
    }

    @Test
    public void anUnreadablePipelineIsNeverReportedAsIdle() {
        // The distinction the page turns on. "Could not read it" and "nothing is happening" look identical to
        // anyone glancing at a status line, and only one of them means everything is fine.
        final PipelineStatus status = PipelineStatus.failed("trip-manager", "boom");
        Assert.assertFalse(status.isOk());
        Assert.assertEquals(status.getOverall(), "Unavailable");
        Assert.assertFalse(status.isRunning(), "an unreadable pipeline must not claim to be running either");
    }

    @Test
    public void anEmptyPipelineIsUnknownRatherThanSucceeded() {
        final PipelineStatus status = deploy.toStatus("trip-manager", GetPipelineStateResponse.builder().build());
        Assert.assertEquals(status.getOverall(), "Unknown", "no stages is not a healthy pipeline");
    }

    @Test
    public void accessDeniedNamesTheDeployStepThatFixesIt() {
        // The expected state between shipping this page and running 'cdk deploy TripApp'. Without naming it,
        // the page reads as a broken feature rather than an undeployed one.
        final String message = DeployCommands.describe(new AccessDeniedException("not authorized"));
        Assert.assertTrue(message.contains("cdk deploy TripApp"), "should name the fix; got: " + message);
        Assert.assertTrue(message.contains("codepipeline:StartPipelineExecution"), message);
    }

    @Test
    public void anOrdinaryFailureKeepsItsOwnMessage() {
        final String message = DeployCommands.describe(new IllegalStateException("connection reset"));
        Assert.assertTrue(message.contains("connection reset"), message);
    }

    @Test
    public void stageStatusFlagsMatchCodePipelinesVocabulary() {
        Assert.assertTrue(stageStatus("InProgress").isRunning());
        Assert.assertTrue(stageStatus("Failed").isBad());
        Assert.assertTrue(stageStatus("Stopped").isBad());
        Assert.assertTrue(stageStatus("Cancelled").isBad());
        Assert.assertTrue(stageStatus("Succeeded").isSucceeded());
        // Superseded is none of the three: a run replaced by a newer one is neither a failure to investigate
        // nor a success to trust.
        Assert.assertFalse(stageStatus("Superseded").isBad());
        Assert.assertFalse(stageStatus("Superseded").isSucceeded());
        // A stage that has never run reports no status at all, and must not throw on the way to the page.
        Assert.assertFalse(stageStatus(null).isRunning());
        Assert.assertFalse(stageStatus(null).isBad());
    }

    private static PipelineStageStatus stageStatus(final String status) {
        return new PipelineStageStatus("Build", status, null, Instant.now(), null);
    }

    private static ActionState actionWithUrl(final String url) {
        return ActionState.builder().entityUrl(url).build();
    }

    private static StageState stage(final String name, final String status) {
        return StageState.builder()
                .stageName(name)
                .latestExecution(StageExecution.builder().status(status).pipelineExecutionId("exec-1").build())
                .actionStates(List.of(ActionState.builder().actionName(name).build()))
                .build();
    }

    /** Stands in for the SDK's own AccessDeniedException, whose name is what {@code describe} keys off. */
    private static final class AccessDeniedException extends RuntimeException {
        AccessDeniedException(final String message) {
            super(message);
        }
    }
}
