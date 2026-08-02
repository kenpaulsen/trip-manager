package org.paulsens.trip.model.deploy;

import java.io.Serializable;
import java.time.Instant;
import lombok.Value;

/**
 * One stage of the deployment pipeline, flattened for display.
 *
 * <p>Deliberately a small immutable snapshot rather than the SDK's own response type. The page holds this in
 * {@code viewScope}, and anything in {@code viewScope} must be {@code Serializable} or the session save fails
 * and every later request on that session 500s site-wide -- an SDK response object carries a client, a
 * response-metadata object and an SdkHttpResponse, none of which are serializable.
 */
@Value
public class PipelineStageStatus implements Serializable {

    /** Stage name as CodePipeline reports it: Source, Build, IntegTest, DeployProd. */
    String name;
    /** Raw CodePipeline status: Succeeded, InProgress, Failed, Stopped, Stopping, Cancelled, Superseded. */
    String status;
    /**
     * A one-line human summary, when the stage offers one.
     *
     * <p>For a source action this is the <b>commit message</b>, dug out of the JSON blob CodePipeline puts in
     * its summary field. That is the single most useful thing on the page -- "what is actually deploying?" --
     * and it is also arbitrary text written by whoever made the commit, so it must be rendered escaped.
     */
    String summary;
    /** When the stage last changed status, or null if it has never run. */
    Instant lastChanged;
    /** The AWS console deep link for this stage's action, or null. Always an https console URL. */
    String consoleUrl;

    /** Whether this stage is running right now. Drives the page's auto-refresh and the disabled Deploy button. */
    public boolean isRunning() {
        return "InProgress".equals(status);
    }

    /** Whether this stage ended badly. Failed and Stopped are distinct in CodePipeline but read the same here. */
    public boolean isBad() {
        return "Failed".equals(status) || "Stopped".equals(status) || "Cancelled".equals(status);
    }

    public boolean isSucceeded() {
        return "Succeeded".equals(status);
    }

    /** A CSS colour for the status pill. Kept here so the page has no colour logic in EL. */
    public String getColor() {
        if (isRunning()) {
            return "#b8860b";
        }
        if (isBad()) {
            return "#c0392b";
        }
        return isSucceeded() ? "#18ae6b" : "#777777";
    }
}
