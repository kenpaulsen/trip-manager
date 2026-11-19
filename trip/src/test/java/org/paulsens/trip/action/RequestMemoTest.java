package org.paulsens.trip.action;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The one per-request memo: computed once per key and request, nulls included, and nothing outside a request. */
public class RequestMemoTest {

    @Test
    public void aKeyIsComputedOncePerRequestMap() {
        final Map<String, Object> request = new HashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(RequestMemo.get(request, "k", calls::incrementAndGet), Integer.valueOf(1));
        Assert.assertEquals(RequestMemo.get(request, "k", calls::incrementAndGet), Integer.valueOf(1));
        Assert.assertNull(RequestMemo.get(request, "none", () -> null));
        Assert.assertNull(RequestMemo.get(request, "none", () -> "computed again"), "a null answer is memoized");
        RequestMemo.forget(request, "k");
        Assert.assertEquals(RequestMemo.get(request, "k", calls::incrementAndGet), Integer.valueOf(2));
        RequestMemo.forget(null, "k");
    }

    @Test
    public void aPutReplacesTheAnswerAndIsANoOpWithNoMap() {
        final Map<String, Object> request = new HashMap<>();
        Assert.assertEquals(RequestMemo.get(request, "k", () -> "read"), "read");
        RequestMemo.put(request, "k", "written");
        Assert.assertEquals(RequestMemo.get(request, "k", () -> "read again"), "written");
        RequestMemo.put(null, "k", "nowhere");
    }

    @Test
    public void outsideARequestEveryCallComputes() {
        final AtomicInteger calls = new AtomicInteger();
        RequestMemo.get("k", calls::incrementAndGet);
        RequestMemo.get("k", calls::incrementAndGet);
        Assert.assertEquals(calls.get(), 2);
        Assert.assertNull(RequestMemo.facesRequestMap());
    }

    @Test
    public void insideAFacesRequestTheRequestMapIsUsed() {
        final Map<String, Object> request = new HashMap<>();
        TestFaces.install(request);
        try {
            Assert.assertSame(RequestMemo.facesRequestMap(), request);
            Assert.assertEquals(RequestMemo.get("faces", () -> "v"), "v");
            Assert.assertEquals(request.get("faces"), "v");
        } finally {
            TestFaces.clear();
        }
    }
}
