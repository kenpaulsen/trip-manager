package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.ScopeUtil;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;

/**
 * The crop dialog's image source, exposed as {@code #{pendingPhoto}} — deliberately its own tiny bean rather
 * than a method on each feature's upload bean. PrimeFaces dynamic content stores the image's EL expression
 * as a STRING and re-evaluates it on a later resource request, outside the view; a {@code ui:param} alias
 * (how the shared dialog names its upload bean) does not exist there, so an aliased expression resolves to
 * null and every cropper image 404s into the error page's redirect-home. A fixed {@code @Named} bean is the
 * one shape that survives that round trip, and serving the preview is purpose-agnostic anyway: whichever
 * feature parked the upload, the image is just "the current pending upload of the signed-in person".
 */
@Named("pendingPhoto")
@ApplicationScoped
public class PendingPhotoView {

    private PendingUploads pendingUploads = PendingUploads.getPendingUploads();

    void setPendingUploadsForTest(final PendingUploads pendingUploads) {
        this.pendingUploads = pendingUploads;
    }

    /**
     * The pending upload's preview JPEG. An unclaimable token (expired, foreign, absent) streams empty
     * rather than erroring — the component shows nothing and the dialog's Start Over recovers.
     */
    public StreamedContent getCropperImage() {
        return DefaultStreamedContent.builder()
                .contentType("image/jpeg")
                .stream(this::cropperStream)
                .build();
    }

    private InputStream cropperStream() {
        final String token = sessionGet(PendingUploads.SESSION_TOKEN_KEY);
        final String owner = callerId();
        final byte[] bytes = (token == null || owner == null)
                ? new byte[0]
                : pendingUploads.peek(token, owner)
                        .map(PendingUploads.Pending::previewJpeg).orElse(new byte[0]);
        return new ByteArrayInputStream(bytes);
    }

    private String callerId() {
        final Person.Id id = caller().personId();
        return id == null ? null : id.getValue();
    }

    /** Seams mirroring {@code ProfilePhotoCommands}: tests supply the caller and session directly. */
    protected Caller caller() {
        return Caller.current();
    }

    protected String sessionGet(final String key) {
        return ScopeUtil.getInstance().getSessionMap(key);
    }
}
