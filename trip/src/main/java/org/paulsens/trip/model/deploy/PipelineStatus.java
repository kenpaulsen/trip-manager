package org.paulsens.trip.model.deploy;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import lombok.Value;

/**
 * The deployment pipeline as the admin page shows it: every stage, plus why the page cannot show it when it
 * cannot.
 *
 * <p><b>Three distinct not-working states, kept apart on purpose.</b> "This build is not wired to a pipeline",
 * "the task is not allowed to look" and "the call failed" have completely different remedies, and collapsing
 * them into an empty page or a bare "error" is what makes an operator go and check the console instead --
 * which is the thing this page exists to save them.
 */
@Value
public class PipelineStatus implements Serializable {

    /** Shown when nothing is configured, so the page still renders and explains itself. */
    public static final PipelineStatus UNCONFIGURED = new PipelineStatus(
            null, List.of(), null,
            "No pipeline is configured for this deployment (TRIP_PIPELINE_NAME is unset), so there is nothing "
                    + "to start or show. This is expected on a laptop and in tests.",
            false, null);

    String pipelineName;
    List<PipelineStageStatus> stages;
    /** The id of the run these stages belong to, for cross-referencing the console. Null when never run. */
    String executionId;
    /** Non-null when the state could not be read. Rendered as-is, so it must stay operator-facing text. */
    String error;
    /** Whether a pipeline name is configured at all. False means the feature is simply not wired up here. */
    boolean configured;
    /** When this snapshot was taken, so a stale page is visibly stale rather than quietly wrong. */
    Instant fetchedAt;

    public PipelineStatus(final String pipelineName, final List<PipelineStageStatus> stages,
            final String executionId, final String error, final boolean configured, final Instant fetchedAt) {
        this.pipelineName = pipelineName;
        this.stages = stages == null ? List.of() : List.copyOf(stages);
        this.executionId = executionId;
        this.error = error;
        this.configured = configured;
        this.fetchedAt = fetchedAt;
    }

    /** A failure to read the state, which must never be mistaken for "the pipeline is idle". */
    public static PipelineStatus failed(final String pipelineName, final String error) {
        return new PipelineStatus(pipelineName, List.of(), null, error, true, Instant.now());
    }

    public boolean isOk() {
        return error == null;
    }

    /** Whether any stage is running. The Deploy button is refused while this is true. */
    public boolean isRunning() {
        return stages.stream().anyMatch(PipelineStageStatus::isRunning);
    }

    public boolean isFailed() {
        return stages.stream().anyMatch(PipelineStageStatus::isBad);
    }

    /**
     * One word for the whole pipeline, for the heading.
     *
     * <p>Running beats failed beats succeeded: a run in progress is the thing an operator is watching, and a
     * stale failure on an earlier stage must not hide it. "Unknown" rather than "Succeeded" when there are no
     * stages at all -- an empty pipeline is not a healthy one.
     */
    public String getOverall() {
        if (!configured) {
            return "Not configured";
        }
        if (!isOk()) {
            return "Unavailable";
        }
        if (isRunning()) {
            return "Running";
        }
        if (isFailed()) {
            return "Failed";
        }
        return stages.isEmpty() ? "Unknown" : "Succeeded";
    }

    public String getColor() {
        if (!configured || !isOk() || stages.isEmpty()) {
            return "#777777";
        }
        if (isRunning()) {
            return "#b8860b";
        }
        return isFailed() ? "#c0392b" : "#18ae6b";
    }
}
