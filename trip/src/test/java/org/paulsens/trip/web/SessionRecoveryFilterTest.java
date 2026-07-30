package org.paulsens.trip.web;

import java.time.DateTimeException;
import java.util.concurrent.CompletionException;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Which failures are treated as an unreadable session.
 *
 * <p>Both directions matter and neither is obvious from the code. Too narrow and the outage this exists for
 * comes back — the real failure arrives as a {@code DateTimeException}, a class with nothing to do with
 * serialisation, and is only identifiable by the Kryo frames underneath it. Too broad and any application error
 * silently logs someone out, which would be a far worse bug than the one being fixed because it would look
 * random and would never appear in a stack trace.
 */
public class SessionRecoveryFilterTest {

    @Test
    public void theProductionFailureIsRecognised() {
        // Exactly the shape that took the home page down: a plain DateTimeException whose only clue is the Kryo
        // frame beneath it, wrapped by the time it reaches a filter.
        final DateTimeException real = new DateTimeException("Invalid value for MonthOfYear (valid values 1 - 12): 19");
        real.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("java.time.temporal.ValueRange", "checkValidValue", "ValueRange.java", 319),
                new StackTraceElement("com.esotericsoftware.kryo.serializers.TimeSerializers$LocalDateSerializer",
                        "read", "TimeSerializers.java", 115),
                new StackTraceElement("org.redisson.codec.Kryo5Codec$4", "decode", "Kryo5Codec.java", 199),
        });
        Assert.assertTrue(SessionRecoveryFilter.isSessionDeserializationFailure(
                new RuntimeException("wrapped", real)));
    }

    /**
     * Kryo is on TOMCAT's classloader, not the webapp's, so this test cannot reference KryoException -- the same
     * separation that keeps Redisson's Netty away from the app's. Detection therefore has to work from frames and
     * names rather than instanceof, which is what this asserts.
     */
    @Test
    public void aKryoFailureIsRecognisedThroughAWrapper() {
        final RuntimeException kryoish = new RuntimeException("Error during Java deserialization.");
        kryoish.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.esotericsoftware.kryo.serializers.JavaSerializer", "read",
                        "JavaSerializer.java", 68),
        });
        Assert.assertTrue(SessionRecoveryFilter.isSessionDeserializationFailure(
                new CompletionException(kryoish)));
    }

    @Test
    public void aMissingClassInTheCodecIsRecognised() {
        // The other shape this produces: a class the session manager's loader cannot see.
        final ClassNotFoundException missing = new ClassNotFoundException("org.paulsens.trip.model.Trip");
        missing.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.redisson.codec.Kryo5Codec$4", "decode", "Kryo5Codec.java", 199),
        });
        Assert.assertTrue(SessionRecoveryFilter.isSessionDeserializationFailure(missing));
    }

    @Test
    public void ordinaryApplicationErrorsAreLeftAlone() {
        // The important negative. Clearing sessions on these would turn every NPE into an unexplained logout.
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(
                new NullPointerException("something in a bean")));
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(
                new IllegalStateException("a normal failure", new IllegalArgumentException("nested"))));
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(null));
    }

    @Test
    public void aDateTimeExceptionFromApplicationCodeIsLeftAlone() {
        // Same exception type as the real incident, but no serialisation frames -- so it must NOT match. This is
        // what stops "bad date somewhere in the app" from logging people out.
        final DateTimeException appLevel = new DateTimeException("Invalid value for MonthOfYear: 19");
        appLevel.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.paulsens.trip.action.TripCommands", "saveTrip", "TripCommands.java", 56),
        });
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(appLevel));
    }

    @Test
    public void aCyclicCauseChainTerminates() {
        // CompletionException plumbing can produce a cycle; walking it must not hang the request thread.
        final RuntimeException a = new RuntimeException("a");
        final RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(b));
    }
}
