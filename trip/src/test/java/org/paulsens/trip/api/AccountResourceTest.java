package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.AccountDeletionCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The account edge: which bean outcome becomes which status, and that a blocked refusal carries the preview. */
public class AccountResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("account-me");

    private AccountDeletionCommands deletion;
    private AccountResource resource;

    @BeforeMethod
    public void bind() {
        deletion = bindMock(AccountDeletionCommands.class);
        resource = resource(new AccountResource());
        signedInAs(ME);
    }

    private static AccountDeletionCommands.Preview preview(final boolean blocked) {
        return new AccountDeletionCommands.Preview(blocked, true,
                blocked ? List.of(new AccountDeletionCommands.BlockReason(
                        AccountDeletionCommands.BLOCK_UPCOMING_TRIP, "t1", "Next trip", 0L, null)) : List.of(),
                0L, 0L, "office@example.org",
                List.of(new AccountDeletionCommands.TripLine("t1", "Next trip", "o1", null, null, "Confirmed",
                        false)),
                AccountDeletionCommands.ERASED, AccountDeletionCommands.RETAINED);
    }

    @Test
    public void previewIsShapedForTheApp() {
        Mockito.when(deletion.preview(ME)).thenReturn(preview(true));
        final Response response = resource.deletionPreview();
        Assert.assertEquals(response.getStatus(), 200);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("blocked"), true);
        Assert.assertEquals(body.get("contactEmail"), "office@example.org");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> reasons = (List<Map<String, Object>>) body.get("reasons");
        Assert.assertEquals(reasons.get(0).get("code"), AccountDeletionCommands.BLOCK_UPCOMING_TRIP);
        Assert.assertEquals(reasons.get(0).get("title"), "Next trip");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> trips = (List<Map<String, Object>>) body.get("trips");
        Assert.assertEquals(trips.get(0).get("status"), "Confirmed");
        Assert.assertEquals(body.get("erased"), AccountDeletionCommands.ERASED);
    }

    @Test
    public void previewForNobodyIs404() {
        Mockito.when(deletion.preview(ME)).thenReturn(null);
        Assert.assertEquals(resource.deletionPreview().getStatus(), 404);
    }

    @Test
    public void deleteRequiresTheCsrfSentinelForCookieCallers() {
        Assert.assertEquals(resource.delete(null, Map.of("confirm", "DELETE")).getStatus(), 403);
        Mockito.verifyNoInteractions(deletion);
    }

    @Test
    public void outcomesMapToStatuses() {
        Mockito.when(deletion.deleteOwnAccount(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("nope"),
                ArgumentMatchers.any(AuditActor.class)))
                .thenReturn(new AccountDeletionCommands.Outcome(false, AccountDeletionCommands.REFUSED_CONFIRMATION,
                        "Type DELETE", null));
        Assert.assertEquals(resource.delete(CSRF_OK, Map.of("confirm", "nope")).getStatus(), 400);

        Mockito.when(deletion.deleteOwnAccount(ArgumentMatchers.eq(ME), ArgumentMatchers.isNull(),
                ArgumentMatchers.any(AuditActor.class)))
                .thenReturn(new AccountDeletionCommands.Outcome(false, AccountDeletionCommands.REFUSED_NOT_FOUND,
                        "No such account.", null));
        Assert.assertEquals(resource.delete(CSRF_OK, null).getStatus(), 404);

        Mockito.when(deletion.deleteOwnAccount(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("DELETE"),
                ArgumentMatchers.any(AuditActor.class)))
                .thenReturn(new AccountDeletionCommands.Outcome(false, AccountDeletionCommands.REFUSED_BLOCKED,
                        "Your account can't be deleted yet.", preview(true)));
        final Response blocked = resource.delete(CSRF_OK, Map.of("confirm", "DELETE"));
        Assert.assertEquals(blocked.getStatus(), 409);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) blocked.getEntity();
        Assert.assertEquals(body.get("error"), "ACCOUNT_DELETE_BLOCKED");
        Assert.assertNotNull(body.get("preview"), "the refusal explains itself");

        Mockito.when(deletion.deleteOwnAccount(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("DELETE"),
                ArgumentMatchers.any(AuditActor.class)))
                .thenReturn(new AccountDeletionCommands.Outcome(false, AccountDeletionCommands.REFUSED_FAILED,
                        "boom", null));
        Assert.assertEquals(resource.delete(CSRF_OK, Map.of("confirm", "DELETE")).getStatus(), 500);

        Mockito.when(deletion.deleteOwnAccount(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("DELETE"),
                ArgumentMatchers.any(AuditActor.class)))
                .thenReturn(new AccountDeletionCommands.Outcome(true, null, null, null));
        final Response ok = resource.delete(CSRF_OK, Map.of("confirm", "DELETE"));
        Assert.assertEquals(ok.getStatus(), 200);
        @SuppressWarnings("unchecked")
        final Map<String, Object> done = (Map<String, Object>) ok.getEntity();
        Assert.assertEquals(done.get("deleted"), true);
    }
}
