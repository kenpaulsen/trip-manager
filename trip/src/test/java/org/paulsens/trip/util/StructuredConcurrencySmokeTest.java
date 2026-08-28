package org.paulsens.trip.util;

import java.util.concurrent.StructuredTaskScope;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * Proves the preview toolchain end-to-end: javac accepts preview sources, surefire launches with
 * --enable-preview, the mockito javaagent tolerates preview classfiles, and JaCoCo instruments them.
 * The assertions are almost beside the point -- if this class loads and runs, the Phase 1 toolchain works.
 */
public class StructuredConcurrencySmokeTest {

    private static final ScopedValue<String> CONTEXT = ScopedValue.newInstance();

    @Test
    public void forkedSubtasksRunAndJoin() throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            final var left = scope.fork(this::left);
            final var right = scope.fork(this::right);
            scope.join();
            assertEquals(left.get() + right.get(), "leftright");
        }
    }

    @Test
    public void scopedValueFlowsIntoForks() throws Exception {
        final String seen = ScopedValue.where(CONTEXT, "bound").call(this::readContextInFork);
        assertEquals(seen, "bound");
    }

    @Test
    public void scopedValueUnboundOutsideScope() {
        assertEquals(readContext(), "unbound");
    }

    private String left() {
        return "left";
    }

    private String right() {
        return "right";
    }

    private String readContextInFork() throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            final var subtask = scope.fork(this::readContext);
            scope.join();
            return subtask.get();
        }
    }

    private String readContext() {
        return CONTEXT.orElse("unbound");
    }
}
