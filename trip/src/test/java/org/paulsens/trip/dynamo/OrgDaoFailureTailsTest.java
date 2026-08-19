package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.OrgMember;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

/**
 * The org DAOs' failure tails: corrupt rows are filtered rather than fatal, transport failures answer
 * empty/false rather than tearing the page down, and a serializer regression is contained to the cache.
 */
public class OrgDaoFailureTailsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void corruptRowsAreFilteredNotFatal() {
        final InMemoryPersistence store = new InMemoryPersistence();
        final Organization.Id orgId = Organization.Id.newInstance();
        store.putItem(b -> b.tableName(OrganizationDAO.ORG_TABLE).item(Map.of(
                "id", AttributeValue.builder().s(orgId.getValue()).build(),
                "content", AttributeValue.builder().s("not json {").build())));
        store.putItem(b -> b.tableName(OrganizationDAO.ORG_TABLE).item(Map.of(
                "id", AttributeValue.builder().s("good-row").build(),
                "content", AttributeValue.builder()
                        .s("{\"id\":\"good-row\",\"name\":\"Survivor\",\"version\":1}").build())));
        store.putItem(b -> b.tableName(OrgMemberDAO.ORG_MEMBERS_TABLE).item(Map.of(
                OrgMemberDAO.ORG_ID, AttributeValue.builder().s(orgId.getValue()).build(),
                OrgMemberDAO.PERSON_ID, AttributeValue.builder().s("p1").build(),
                "content", AttributeValue.builder().s("also not json").build())));

        // A stubbed (non-local) cache client puts the caches in soft-revalidate mode, so the reads run the
        // real table loaders -- which is where corrupt rows must be filtered rather than fatal.
        final OrganizationDAO orgDao = new OrganizationDAO(mapper, store, scanModeClient());
        Assert.assertEquals(orgDao.getOrganizations().size(), 1, "The corrupt row is skipped, not fatal");
        Assert.assertEquals(orgDao.getOrganizations().get(0).getName(), "Survivor");
        orgDao.clearCache();

        final OrgMemberDAO memberDao = new OrgMemberDAO(mapper, store, scanModeClient());
        Assert.assertTrue(memberDao.getMembers(orgId).isEmpty(), "A corrupt member row is skipped");
    }

    /** A stubbed shared-cache client: never loaded, never locks -- every read runs the DAO's loader. */
    private static org.paulsens.trip.cache.CacheClient scanModeClient() {
        final org.paulsens.trip.cache.CacheClient client =
                Mockito.mock(org.paulsens.trip.cache.CacheClient.class);
        Mockito.when(client.getValue(ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());
        Mockito.when(client.getHash(ArgumentMatchers.any())).thenReturn(Map.of());
        Mockito.when(client.getHashFields(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new java.util.HashMap<>());
        Mockito.when(client.tryAcquireLock(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        return client;
    }

    @Test
    public void processorDaoFailureTailsAreContained() throws Exception {
        // Corrupt row: filtered by the partition loader, not fatal.
        final InMemoryPersistence store = new InMemoryPersistence();
        final Organization.Id orgId = Organization.Id.newInstance();
        store.putItem(b -> b.tableName(PaymentProcessorDAO.PROCESSORS_TABLE).item(Map.of(
                PaymentProcessorDAO.ORG_ID, AttributeValue.builder().s(orgId.getValue()).build(),
                PaymentProcessorDAO.CONFIG_ID, AttributeValue.builder().s("bad").build(),
                "content", AttributeValue.builder().s("not json").build())));
        final PaymentProcessorDAO dao = new PaymentProcessorDAO(mapper, store, new InMemoryCacheClient());
        Assert.assertTrue(dao.getConfigs(orgId).isEmpty(), "A corrupt config row is skipped");

        // Refused put: false + version restored, like the org DAO.
        final PutItemResponse.Builder refused = PutItemResponse.builder();
        refused.sdkHttpResponse(SdkHttpResponse.builder().statusCode(500).build());
        final Persistence refusing = Mockito.mock(Persistence.class);
        Mockito.when(refusing.putItem(ArgumentMatchers.any())).thenReturn(refused.build());
        final org.paulsens.trip.model.PaymentProcessorConfig config =
                org.paulsens.trip.model.PaymentProcessorConfig.builder()
                        .orgId(orgId).label("Refused")
                        .type(org.paulsens.trip.model.ProcessorType.FAKE).build();
        final PaymentProcessorDAO refusingDao =
                new PaymentProcessorDAO(mapper, refusing, new InMemoryCacheClient());
        Assert.assertFalse(refusingDao.saveConfig(config));
        Assert.assertEquals(config.getVersion(), 0L);

        // Cache-serializer regression: contained, the row is durable.
        final ObjectMapper flaky = Mockito.spy(mapper);
        Mockito.when(flaky.writeValueAsString(ArgumentMatchers.any()))
                .thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)))
                .thenThrow(new com.fasterxml.jackson.databind.JsonMappingException(null, "cannot serialize"));
        final PaymentProcessorDAO flakyDao =
                new PaymentProcessorDAO(flaky, new InMemoryPersistence(), new InMemoryCacheClient());
        Assert.assertTrue(flakyDao.saveConfig(org.paulsens.trip.model.PaymentProcessorConfig.builder()
                .orgId(Organization.Id.newInstance()).label("Flaky")
                .type(org.paulsens.trip.model.ProcessorType.FAKE).build()));
    }

    @Test
    public void pointReadOfAnUnknownIdAnswersEmpty() {
        final OrganizationDAO dao =
                new OrganizationDAO(mapper, new InMemoryPersistence(), new InMemoryCacheClient());
        Assert.assertTrue(dao.getOrganizations().isEmpty());
        // The partition is loaded and empty, so this goes down the point-read fallback.
        Assert.assertTrue(dao.getOrganization(Organization.Id.newInstance()).isEmpty());
    }

    @Test
    public void pointReadTransportFailureAnswersEmpty() {
        final Persistence broken = Mockito.mock(Persistence.class);
        Mockito.when(broken.scanAll(ArgumentMatchers.any())).thenReturn(java.util.List.of());
        Mockito.when(broken.getItem(ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("read refused"));
        final OrganizationDAO dao = new OrganizationDAO(mapper, broken, new InMemoryCacheClient());
        Assert.assertTrue(dao.getOrganization(Organization.Id.newInstance()).isEmpty(),
                "A transport failure on the point read is contained");
    }

    @Test
    public void anUnsuccessfulPutMapsToFalseAndRestoresTheVersion() throws Exception {
        final PutItemResponse.Builder refused = PutItemResponse.builder();
        refused.sdkHttpResponse(SdkHttpResponse.builder().statusCode(500).build());
        final Persistence refusing = Mockito.mock(Persistence.class);
        Mockito.when(refusing.putItem(ArgumentMatchers.any())).thenReturn(refused.build());

        final Organization org = Organization.builder().name("Refused").build();
        final OrganizationDAO orgDao = new OrganizationDAO(mapper, refusing, new InMemoryCacheClient());
        Assert.assertFalse(orgDao.saveOrganization(org));
        Assert.assertEquals(org.getVersion(), 0L, "A refused save restores the caller's version");

        final OrgMemberDAO memberDao = new OrgMemberDAO(mapper, refusing, new InMemoryCacheClient());
        Assert.assertFalse(memberDao.saveMember(
                new OrgMember(Organization.Id.newInstance(), Person.Id.newInstance(), null)));
    }

    @Test
    public void aCacheSerializerRegressionIsContained() throws Exception {
        // First serialization (the row write) succeeds; the second (the cache write-through) fails. The
        // save itself must still report success -- the row is durable, the cache heals on next load.
        final ObjectMapper flaky = Mockito.spy(mapper);
        Mockito.when(flaky.writeValueAsString(ArgumentMatchers.any()))
                .thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)))
                .thenThrow(new com.fasterxml.jackson.databind.JsonMappingException(null, "cannot serialize"));
        final OrganizationDAO orgDao =
                new OrganizationDAO(flaky, new InMemoryPersistence(), new InMemoryCacheClient());
        Assert.assertTrue(orgDao.saveOrganization(Organization.builder().name("Flaky").build()));

        final ObjectMapper flaky2 = Mockito.spy(mapper);
        Mockito.when(flaky2.writeValueAsString(ArgumentMatchers.any()))
                .thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)))
                .thenThrow(new com.fasterxml.jackson.databind.JsonMappingException(null, "cannot serialize"));
        final OrgMemberDAO memberDao =
                new OrgMemberDAO(flaky2, new InMemoryPersistence(), new InMemoryCacheClient());
        Assert.assertTrue(memberDao.saveMember(
                new OrgMember(Organization.Id.newInstance(), Person.Id.newInstance(), null)));
    }

}
