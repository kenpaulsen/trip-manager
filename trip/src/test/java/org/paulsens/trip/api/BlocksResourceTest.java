package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.action.BlockListCommands;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The block-list edge: self-scoped, idempotent, and every bean refusal has a status. */
public class BlocksResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("blocks-me");
    private static final Person.Id THEM = Person.Id.from("blocks-them");

    private BlockListCommands blocks;
    private BlocksResource resource;

    @BeforeMethod
    public void bind() {
        blocks = bindMock(BlockListCommands.class);
        resource = resource(new BlocksResource());
        signedInAs(ME);
    }

    @Test
    public void listsTheCallersOwnBlocks() {
        Mockito.when(blocks.blockedBy(ME)).thenReturn(List.of(THEM));
        final Response response = resource.list();
        Assert.assertEquals(response.getStatus(), 200);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("personIds"), List.of("blocks-them"));
    }

    @Test
    public void mutationsNeedTheCsrfSentinel() {
        Assert.assertEquals(resource.block("x", null).getStatus(), 403);
        Assert.assertEquals(resource.unblock("x", null).getStatus(), 403);
        Mockito.verifyNoInteractions(blocks);
    }

    @Test
    public void blockOutcomesMapToStatuses() {
        Mockito.when(blocks.block(ME, THEM)).thenReturn(new BlockListCommands.BlockOutcome(true, null, null,
                List.of(THEM)));
        final Response ok = resource.block("blocks-them", CSRF_OK);
        Assert.assertEquals(ok.getStatus(), 200);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) ok.getEntity();
        Assert.assertEquals(body.get("personIds"), List.of("blocks-them"));

        Mockito.when(blocks.block(ME, Person.Id.from("ghost"))).thenReturn(new BlockListCommands.BlockOutcome(false,
                BlockListCommands.REFUSED_NOT_FOUND, "No such person.", List.of()));
        Assert.assertEquals(resource.block("ghost", CSRF_OK).getStatus(), 404);

        Mockito.when(blocks.block(ME, ME)).thenReturn(new BlockListCommands.BlockOutcome(false,
                BlockListCommands.REFUSED_SELF, "You cannot block yourself.", List.of()));
        Assert.assertEquals(resource.block("blocks-me", CSRF_OK).getStatus(), 400);

        Mockito.when(blocks.block(ME, Person.Id.from("full"))).thenReturn(new BlockListCommands.BlockOutcome(false,
                BlockListCommands.REFUSED_FULL, "full", List.of()));
        Assert.assertEquals(resource.block("full", CSRF_OK).getStatus(), 409);

        Mockito.when(blocks.block(ME, Person.Id.from("store"))).thenReturn(new BlockListCommands.BlockOutcome(false,
                BlockListCommands.REFUSED_STORE, "store", List.of()));
        Assert.assertEquals(resource.block("store", CSRF_OK).getStatus(), 500);

        Mockito.when(blocks.block(ME, null)).thenReturn(new BlockListCommands.BlockOutcome(false,
                BlockListCommands.REFUSED_SELF, "You cannot block yourself.", List.of()));
        Assert.assertEquals(resource.block("  ", CSRF_OK).getStatus(), 400, "a blank id is nobody");
    }

    @Test
    public void unblockIsIdempotent() {
        Mockito.when(blocks.unblock(ME, THEM)).thenReturn(new BlockListCommands.BlockOutcome(true, null, null,
                List.of()));
        final Response ok = resource.unblock("blocks-them", CSRF_OK);
        Assert.assertEquals(ok.getStatus(), 200);
        Mockito.when(blocks.unblock(ME, THEM)).thenReturn(new BlockListCommands.BlockOutcome(false,
                BlockListCommands.REFUSED_STORE, "store", List.of(THEM)));
        Assert.assertEquals(resource.unblock("blocks-them", CSRF_OK).getStatus(), 500);
    }
}
