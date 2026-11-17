package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.testng.Assert;
import org.testng.annotations.Test;

/** JSON round trips and the small behavioral contracts of the content-template model types. */
public class ContentModelsTest {

    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    private static ContentTemplate template() {
        return new ContentTemplate("tid", 3, "Name", "Desc", "<p>{{a}}</p>",
                List.of(new Placeholder("a", Placeholder.Type.RICH_TEXT, "A", "hint", true)),
                LocalDateTime.of(2026, 8, 1, 8, 0), "who");
    }

    private static ContentInstance instance(final int version) {
        return new ContentInstance("cid", "home.events", "Title", "tid", 3,
                new HashMap<>(Map.of("a", "v")), LocalDateTime.of(2026, 9, 1, 23, 59), 2, version,
                LocalDateTime.of(2026, 8, 2, 9, 0), "who");
    }

    @Test
    public void placeholderNormalizesItsParts() throws Exception {
        final Placeholder ph = new Placeholder("  name  ", null, " ", "h", true);
        Assert.assertEquals(ph.getName(), "name");
        Assert.assertEquals(ph.getType(), Placeholder.Type.TEXT, "null type defaults to TEXT");
        Assert.assertEquals(ph.getLabel(), "name", "blank label falls back to the name");
        Assert.assertTrue(ph.isRequired());
        final Placeholder back = MAPPER.readValue(MAPPER.writeValueAsString(ph), Placeholder.class);
        Assert.assertEquals(back, ph);
    }

    @Test
    public void templateRoundTripsAndCopies() throws Exception {
        final ContentTemplate tpl = template();
        final ContentTemplate back = MAPPER.readValue(MAPPER.writeValueAsString(tpl), ContentTemplate.class);
        Assert.assertEquals(back, tpl);

        final ContentTemplate copy = tpl.copy();
        Assert.assertEquals(copy, tpl);
        copy.getPlaceholders().add(new Placeholder("b", Placeholder.Type.TEXT, "B", null, false));
        Assert.assertEquals(tpl.getPlaceholders().size(), 1, "editing a copy must not touch the original");
    }

    /** Tags and the two "where it fits" flags: written only when set, so a row from before them is unchanged. */
    @Test
    public void templateTagsAndFlagsRoundTripAndLeaveOldRowsAlone() throws Exception {
        final ContentTemplate plain = template();
        final String plainJson = MAPPER.writeValueAsString(plain);
        Assert.assertFalse(plainJson.contains("tags") || plainJson.contains("childOnly")
                || plainJson.contains("fullWidth"), "a template with none writes none: " + plainJson);
        Assert.assertTrue(plain.getTags().isEmpty(), "no tags reads as an empty list, never null");
        Assert.assertFalse(plain.isChildOnly() || plain.isFullWidth());

        final ContentTemplate tagged = template();
        tagged.setTags(List.of("hero", "banner"));
        tagged.setChildOnly(true);
        tagged.setFullWidth(true);
        final String json = MAPPER.writeValueAsString(tagged);
        Assert.assertTrue(json.contains("\"tags\":[\"hero\",\"banner\"]"), json);
        Assert.assertTrue(json.contains("\"childOnly\":true") && json.contains("\"fullWidth\":true"), json);
        final ContentTemplate back = MAPPER.readValue(json, ContentTemplate.class);
        Assert.assertEquals(back, tagged);
        Assert.assertEquals(back.getTags(), List.of("hero", "banner"));

        final ContentTemplate copy = tagged.copy();
        Assert.assertEquals(copy, tagged);
        Assert.assertNotSame(copy.getTags(), tagged.getTags(), "a copy's tags are its own list");
        copy.setTags(List.of());
        Assert.assertEquals(MAPPER.readValue(MAPPER.writeValueAsString(copy), ContentTemplate.class), copy,
                "an emptied list is stored as none, so it still equals what comes back");
        copy.setTags(null);
        Assert.assertTrue(copy.getTags().isEmpty());
        Assert.assertEquals(tagged.getTags().size(), 2, "editing a copy must not touch the original");
    }

    @Test
    public void instanceRoundTripsCopiesAndLazyInits() throws Exception {
        final ContentInstance inst = instance(4);
        final ContentInstance back = MAPPER.readValue(MAPPER.writeValueAsString(inst), ContentInstance.class);
        Assert.assertEquals(back, inst);

        final ContentInstance copy = inst.copy();
        copy.getValues().put("b", "x");
        Assert.assertFalse(inst.getValues().containsKey("b"), "editing a copy must not touch the original");

        final ContentInstance bare = MAPPER.readValue("{\"id\":\"x\",\"section\":\"s\"}",
                ContentInstance.class);
        Assert.assertNotNull(bare.getValues(), "values lazily initialize for the dialog's map access");
        bare.setValues(Map.of("k", "v"));
        Assert.assertEquals(bare.getValues().get("k"), "v");

        final ContentTemplate bareTpl = MAPPER.readValue("{\"id\":\"x\"}", ContentTemplate.class);
        Assert.assertNotNull(bareTpl.getPlaceholders(), "placeholders lazily initialize too");
        bareTpl.setPlaceholders(List.of(new Placeholder("p", Placeholder.Type.TEXT, "P", null, false)));
        Assert.assertEquals(bareTpl.getPlaceholders().size(), 1);
    }

    @Test
    public void visibilityFollowsTheEventDate() {
        final ContentInstance inst = instance(1);
        Assert.assertTrue(inst.isVisibleAt(LocalDateTime.of(2026, 9, 1, 23, 59)),
                "visible through the moment itself");
        Assert.assertFalse(inst.isVisibleAt(LocalDateTime.of(2026, 9, 2, 0, 0)));
        inst.setEventDate(null);
        Assert.assertTrue(inst.isVisibleAt(LocalDateTime.of(2099, 1, 1, 0, 0)), "no date never expires");
    }

    @Test
    public void recordsFlattenVersionsCurrentFirst() throws Exception {
        final ContentTemplate v3 = template();
        final ContentTemplate v2 = template();
        v2.setVersion(2);
        final TemplateRecord rec = new TemplateRecord("tid", v3, List.of(v2));
        Assert.assertEquals(rec.getAllVersions().stream().map(ContentTemplate::getVersion).toList(),
                List.of(3, 2));
        Assert.assertEquals(rec.findVersion(2), v2);
        Assert.assertNull(rec.findVersion(1));
        Assert.assertEquals(MAPPER.readValue(MAPPER.writeValueAsString(rec), TemplateRecord.class), rec);

        final ContentRecord crec = new ContentRecord("cid", instance(2), List.of(instance(1)));
        Assert.assertEquals(crec.getAllVersions().size(), 2);
        Assert.assertEquals(crec.findVersion(1).getVersion(), 1);
        Assert.assertNull(crec.findVersion(9));
        Assert.assertEquals(MAPPER.readValue(MAPPER.writeValueAsString(crec), ContentRecord.class), crec);
    }

    @Test
    public void recordsTolerateNullHistory() {
        Assert.assertTrue(new TemplateRecord("t", template(), null).getPrevious().isEmpty());
        Assert.assertTrue(new ContentRecord("c", instance(1), null).getPrevious().isEmpty());
        // A null current (should never be stored, but a hand-edited row must not break rendering paths).
        Assert.assertTrue(new TemplateRecord("t", null, null).getAllVersions().isEmpty());
        Assert.assertTrue(new ContentRecord("c", null, null).getAllVersions().isEmpty());
    }

    @Test
    public void chatAttachmentHiddenContract() throws Exception {
        final ChatAttachment legacy = MAPPER.readValue("{\"kind\":\"image\",\"s3Key\":\"k\",\"size\":1}",
                ChatAttachment.class);
        Assert.assertFalse(legacy.isHidden(), "pre-flag chat messages read visible");
        final ChatAttachment hidden = new ChatAttachment("image", "k", "image/jpeg", 1L,
                null, null, "thumb", "cap", true);
        Assert.assertTrue(hidden.isHidden());
        final ChatAttachment back = MAPPER.readValue(MAPPER.writeValueAsString(hidden), ChatAttachment.class);
        Assert.assertTrue(back.isHidden());
        Assert.assertEquals(back, hidden);
        final ChatAttachment eightArg = new ChatAttachment("image", "k", "image/jpeg", 1L,
                null, null, "thumb", "cap");
        Assert.assertFalse(eightArg.isHidden());
    }
}
