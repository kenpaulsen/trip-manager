package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * {@code PersonDAO.resolvePeople}: a search's ids resolve concurrently (so the cache reads pipeline), and the
 * misses that fall through to DynamoDB are bounded, never more than {@code RESOLVE_LOADS} (16) in flight. A
 * permit leaked or taken per fork instead of per miss would deadlock or flood the client's 100-connection pool.
 */
public class PersonResolveTest {
    private static final int PEOPLE = 40;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    @Test(timeOut = 30_000)
    public void manyColdMissesAllResolveWithBoundedDynamoReads() throws Exception {
        final String last = "Resolvelast" + RandomData.genAlpha(8);
        final Persistence real = DynamoLocal.persistence();
        final PersonDAO writer = new PersonDAO(mapper, real);
        for (int i = 0; i < PEOPLE; i++) {
            final Person person = new Person();
            person.setFirst("P" + i);
            person.setLast(last);
            Assert.assertTrue(writer.savePerson(person));
        }
        final Persistence slow = Mockito.mock(Persistence.class, AdditionalAnswers.delegatesTo(real));
        Mockito.doAnswer(call -> timedRead(real, call))
                .when(slow).getItem(ArgumentMatchers.<Consumer<GetItemRequest.Builder>>any());
        final CacheClient cold = Mockito.mock(CacheClient.class,
                AdditionalAnswers.delegatesTo(new InMemoryCacheClient()));

        final List<Person> found = new PersonDAO(mapper, slow, cold).searchPeople(last.toLowerCase(), PEOPLE);

        Assert.assertEquals(found.size(), PEOPLE, "every cold miss resolves");
        Assert.assertTrue(reads.get() >= PEOPLE, "each person was a DynamoDB read: " + reads.get());
        Assert.assertTrue(maxInFlight.get() <= 16, "at most 16 DynamoDB reads at once: " + maxInFlight.get());
        Assert.assertTrue(maxInFlight.get() > 1, "the misses ran concurrently: " + maxInFlight.get());
    }

    /** One DynamoDB read, held briefly so overlapping reads can be counted. */
    private GetItemResponse timedRead(final Persistence real, final InvocationOnMock call)
            throws InterruptedException {
        reads.incrementAndGet();
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            Thread.sleep(20);
            return real.getItem(call.<Consumer<GetItemRequest.Builder>>getArgument(0));
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
