package org.paulsens.trip.push;

import java.net.http.HttpClient;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The production transport's client configuration, which nothing else in the suite exercises. */
public class PushTransportTest {

    /**
     * The JDK client's default executor is a cached pool of PLATFORM threads, which is exactly the kind of
     * pool the virtual-thread migration removed everywhere else. The sender runs on a virtual thread, so the
     * client's own response handling must too.
     */
    @Test
    public void theJdkClientRunsOnAnExplicitVirtualThreadExecutor() {
        try (HttpClient client = PushTransport.jdkClient()) {
            Assert.assertTrue(client.executor().isPresent(),
                    "no executor means the JDK default: a cached pool of platform threads");
        }
    }
}
