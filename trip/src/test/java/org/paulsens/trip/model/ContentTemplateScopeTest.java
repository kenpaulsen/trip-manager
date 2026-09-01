package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

/** A template's owning organization: null = shared, survives copies and the JSON round trip. */
public class ContentTemplateScopeTest {

    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    @Test
    public void sharedIsTheDefaultAndTheCompatibilityShapes() {
        final ContentTemplate legacy = new ContentTemplate("t", 1, "T", null, "<p>{{a}}</p>", List.of(),
                LocalDateTime.of(2026, 9, 1, 8, 0), "who");
        Assert.assertNull(legacy.getOrgId());
        Assert.assertFalse(legacy.isOrgOwned());
        final ContentTemplate v2 = new ContentTemplate("t", 1, "T", null, "", List.of(), null, "who",
                TemplateKind.CONTAINER, List.of("text-only"), 3, null);
        Assert.assertNull(v2.getOrgId(), "the v2 shape without an org is a shared template");
        Assert.assertEquals(v2.getKind(), TemplateKind.CONTAINER);
        v2.setOrgId("   ");
        Assert.assertFalse(v2.isOrgOwned(), "blank is not an owner");
    }

    @Test
    public void anOwnerSurvivesCopyAndJson() throws Exception {
        final ContentTemplate owned = new ContentTemplate("t", 2, "T", "d", "<p>{{a}}</p>",
                List.of(new Placeholder("a", Placeholder.Type.TEXT, "A", null, true)),
                LocalDateTime.of(2026, 9, 1, 8, 0), "who");
        owned.setOrgId("11111111-2222-3333-4444-555555555555");
        Assert.assertTrue(owned.isOrgOwned());
        final ContentTemplate copy = owned.copy();
        Assert.assertEquals(copy, owned);
        Assert.assertEquals(copy.getOrgId(), owned.getOrgId());

        final String json = MAPPER.writeValueAsString(owned);
        Assert.assertTrue(json.contains("\"orgId\""));
        Assert.assertEquals(MAPPER.readValue(json, ContentTemplate.class), owned);

        final ContentTemplate shared = owned.copy();
        shared.setOrgId(null);
        Assert.assertFalse(MAPPER.writeValueAsString(shared).contains("orgId"),
                "a shared template writes no owner (the pre-org-sites row shape)");
    }
}
