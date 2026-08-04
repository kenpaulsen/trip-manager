package org.redisson.codec;

/**
 * A throwable whose CLASS NAME sits under a package {@code SessionRecoveryFilter} treats as a session
 * deserialisation failure.
 *
 * <p>Lives in this package deliberately: the filter matches on the class name string, and Kryo/Redisson's codec
 * are not on the unit-test classpath. Fabricating the name is how the class-name branch gets exercised at all;
 * the stack-frame branch is covered separately by planting frames on an ordinary exception.
 */
public class FakeCodecFailure extends RuntimeException {
    public FakeCodecFailure(final String message) {
        super(message);
    }
}
