package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * A servlet response a test can read back: the bytes the servlet wrote and the status it refused with.
 *
 * <p>The response is a Mockito mock, not a hand-written fake, because the servlet tests also
 * {@code Mockito.verify} headers, redirects and content types on it. Only {@code sendError(int)} moves the
 * status: a servlet that answers through {@code setStatus} is verified on the mock (or traps it itself, as
 * {@code MediaUploadServletTest} does), so a refusal written as a body never reads as an error here.
 */
public final class CapturedResponse {
    private final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);

    private CapturedResponse() {
    }

    /** A fresh response at 200 with an empty body. */
    public static CapturedResponse capture() throws IOException {
        final CapturedResponse captured = new CapturedResponse();
        Mockito.when(captured.response.getOutputStream()).thenReturn(streamInto(captured.body));
        Mockito.doAnswer(call -> captured.status.getAndSet(call.getArgument(0)))
                .when(captured.response).sendError(ArgumentMatchers.anyInt());
        return captured;
    }

    /** The bare stream, for a test that keeps its own response mock. */
    public static ServletOutputStream streamInto(final ByteArrayOutputStream body) {
        return new BufferStream(body);
    }

    public HttpServletResponse response() {
        return response;
    }

    public ByteArrayOutputStream body() {
        return body;
    }

    /** 200 until the servlet calls {@code sendError}; a test resets it between requests. */
    public AtomicInteger status() {
        return status;
    }

    private static final class BufferStream extends ServletOutputStream {
        private final ByteArrayOutputStream body;

        private BufferStream(final ByteArrayOutputStream body) {
            this.body = body;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(final WriteListener listener) {
            // Blocking writes only.
        }

        @Override
        public void write(final int b) {
            body.write(b);
        }
    }
}
