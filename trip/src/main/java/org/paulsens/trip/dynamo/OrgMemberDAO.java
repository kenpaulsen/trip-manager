package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.PartitionCache;
import org.paulsens.trip.model.OrgMember;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Org membership rows -- the source of truth for who is in an {@link Organization}. One partition per org
 * (PK orgId, SK personId), cached per partition like {@link TransactionDAO}'s ledgers. The by-person direction
 * deliberately has no index: the derived {@link Person#getOrgIds()} list answers it, the same way
 * {@code Person.familyId} answers "which family".
 */
@Slf4j
public class OrgMemberDAO {
    /** Package-visible so {@link InMemoryPersistence} can register the table for local mode. */
    static final String ORG_MEMBERS_TABLE = "org_members";
    static final String ORG_ID = "orgId";
    static final String PERSON_ID = "personId";
    private static final String CONTENT = "content";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PartitionCache<String, OrgMember> cache;

    protected OrgMemberDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PartitionCache.<String, OrgMember>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.ORG_MEMBER_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(member -> member.getPersonId().getValue())
                .idFormatter(personId -> personId)
                .serializer(this::toJson)
                .deserializer(this::parseMember)
                .order(Comparator.comparing(member -> member.getPersonId().getValue()))
                .build();
    }

    protected Boolean saveMember(final OrgMember member) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ORG_ID, AttributeValue.builder().s(member.getOrgId().getValue()).build());
        map.put(PERSON_ID, AttributeValue.builder().s(member.getPersonId().getValue()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(member)).build());
        final boolean saved = persistence.putItem(b -> b.tableName(ORG_MEMBERS_TABLE).item(map))
                .sdkHttpResponse().isSuccessful();
        return saved && cache.put(member.getOrgId().getValue(), member);
    }

    protected List<OrgMember> getMembers(final Organization.Id orgId) {
        return cache.getAll(orgId.getValue(), () -> loadMembers(orgId));
    }

    protected Optional<OrgMember> getMember(final Organization.Id orgId, final Person.Id personId) {
        if (orgId == null || personId == null) {
            return Optional.empty();
        }
        return cache.getOne(orgId.getValue(), personId.getValue(), () -> loadMembers(orgId));
    }

    protected Boolean deleteMember(final Organization.Id orgId, final Person.Id personId) {
        final Map<String, AttributeValue> key = Map.of(
                ORG_ID, AttributeValue.builder().s(orgId.getValue()).build(),
                PERSON_ID, AttributeValue.builder().s(personId.getValue()).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(ORG_MEMBERS_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        return deleted && cache.remove(orgId.getValue(), personId.getValue());
    }

    private List<OrgMember> loadMembers(final Organization.Id orgId) {
        return persistence.queryAll(qb -> membersByOrgId(qb, orgId)).stream()
                .map(item -> item.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parseMember(content.s()))
                .filter(member -> member != null)
                .toList();
    }

    private void membersByOrgId(final QueryRequest.Builder qb, final Organization.Id orgId) {
        qb.tableName(ORG_MEMBERS_TABLE)
                .keyConditionExpression("orgId = :c")
                .expressionAttributeValues(
                        Map.of(":c", AttributeValue.builder().s(orgId.getValue()).build()));
    }

    private OrgMember parseMember(final String json) {
        try {
            return mapper.readValue(json, OrgMember.class);
        } catch (final IOException ex) {
            log.error("Unable to parse org member record: " + json, ex);
            return null;
        }
    }

    private String toJson(final OrgMember member) {
        try {
            return mapper.writeValueAsString(member);
        } catch (final IOException ex) {
            log.error("Unable to serialize org member: " + member.getOrgId() + "/" + member.getPersonId(), ex);
            return null;
        }
    }
}
