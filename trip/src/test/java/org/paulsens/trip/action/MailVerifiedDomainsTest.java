package org.paulsens.trip.action;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.GetIdentityVerificationAttributesRequest;
import software.amazon.awssdk.services.ses.model.GetIdentityVerificationAttributesResponse;
import software.amazon.awssdk.services.ses.model.IdentityVerificationAttributes;
import software.amazon.awssdk.services.ses.model.ListIdentitiesRequest;
import software.amazon.awssdk.services.ses.model.ListIdentitiesResponse;
import software.amazon.awssdk.services.ses.model.VerificationStatus;

/**
 * {@link MailCommands#verifiedSendingDomains()} -- the Settings page's From-domain dropdown. The suite
 * runs local (where the method answers the fixed fake list), so the SES path is exercised through the
 * package-private {@code memoizedVerifiedDomains} with the client mocked: pagination, the
 * verified-only filter, the memo, and serve-stale-on-failure.
 */
public class MailVerifiedDomainsTest {

    @Test
    public void localModeAnswersTheFixedFakeList() {
        Assert.assertEquals(new MailCommands().verifiedSendingDomains(),
                List.of("centerforpeacewest.com", "example.com", "unitetrip.com", "visitqueenofpeace.com"),
                "local mode never touches SES and the webtests rely on these domains");
    }

    @Test
    public void sesDomainsArePagedFilteredToVerifiedSortedAndMemoized() {
        final SesClient ses = Mockito.mock(SesClient.class);
        stubPages(ses,
                page(List.of("visitqueenofpeace.com", "pending.example"), "page-2"),
                page(List.of("centerforpeacewest.com"), null));
        Mockito.when(ses.getIdentityVerificationAttributes(
                        ArgumentMatchers.<Consumer<GetIdentityVerificationAttributesRequest.Builder>>any()))
                .thenReturn(attributes(Map.of(
                        "visitqueenofpeace.com", VerificationStatus.SUCCESS,
                        "pending.example", VerificationStatus.PENDING,
                        "centerforpeacewest.com", VerificationStatus.SUCCESS)));
        final MailCommands mail = new MailCommands(ses);

        final List<String> domains = mail.memoizedVerifiedDomains();
        Assert.assertEquals(domains, List.of("centerforpeacewest.com", "visitqueenofpeace.com"),
                "unverified identities are dropped and the rest sorted");
        Assert.assertEquals(mail.memoizedVerifiedDomains(), domains);
        Mockito.verify(ses, Mockito.times(2)).listIdentities(
                ArgumentMatchers.<Consumer<ListIdentitiesRequest.Builder>>any());
        // Two pages = two list calls for the FIRST resolve; the second resolve hit the memo.
    }

    @Test
    public void aFailureAnswersEmptyThenServesStaleAfterASuccess() {
        final SesClient broken = Mockito.mock(SesClient.class);
        Mockito.when(broken.listIdentities(
                        ArgumentMatchers.<Consumer<ListIdentitiesRequest.Builder>>any()))
                .thenThrow(new IllegalStateException("SES down"));
        Assert.assertEquals(new MailCommands(broken).memoizedVerifiedDomains(), List.of(),
                "no cache yet: a failure answers empty");

        // TTL forced to zero so the second call refreshes -- into a failure, which must serve stale.
        final SesClient flaky = Mockito.mock(SesClient.class);
        stubPages(flaky, page(List.of("visitqueenofpeace.com"), null));
        Mockito.when(flaky.getIdentityVerificationAttributes(
                        ArgumentMatchers.<Consumer<GetIdentityVerificationAttributesRequest.Builder>>any()))
                .thenReturn(attributes(Map.of("visitqueenofpeace.com", VerificationStatus.SUCCESS)));
        final MailCommands mail = new MailCommands(flaky) {
            @Override
            long verifiedDomainsTtlMs() {
                return 0L;
            }
        };
        Assert.assertEquals(mail.memoizedVerifiedDomains(), List.of("visitqueenofpeace.com"));
        Mockito.reset(flaky);
        Mockito.when(flaky.listIdentities(
                        ArgumentMatchers.<Consumer<ListIdentitiesRequest.Builder>>any()))
                .thenThrow(new IllegalStateException("SES down"));
        Assert.assertEquals(mail.memoizedVerifiedDomains(), List.of("visitqueenofpeace.com"),
                "a refresh failure serves the previous result rather than emptying the dropdown");
    }

    private static void stubPages(final SesClient ses, final ListIdentitiesResponse first,
            final ListIdentitiesResponse... rest) {
        Mockito.when(ses.listIdentities(
                        ArgumentMatchers.<Consumer<ListIdentitiesRequest.Builder>>any()))
                .thenReturn(first, rest);
    }

    private static ListIdentitiesResponse page(final List<String> identities, final String nextToken) {
        return ListIdentitiesResponse.builder().identities(identities).nextToken(nextToken).build();
    }

    private static GetIdentityVerificationAttributesResponse attributes(
            final Map<String, VerificationStatus> statuses) {
        final Map<String, IdentityVerificationAttributes> attrs = new java.util.HashMap<>();
        statuses.forEach((identity, status) -> attrs.put(identity,
                IdentityVerificationAttributes.builder().verificationStatus(status).build()));
        return GetIdentityVerificationAttributesResponse.builder().verificationAttributes(attrs).build();
    }
}
