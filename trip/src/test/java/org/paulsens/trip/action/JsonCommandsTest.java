package org.paulsens.trip.action;

import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

public class JsonCommandsTest {

    @Test
    @SuppressWarnings("unchecked")
    void canSerializeAMap() throws Exception {
        final JsonCommands jsonCommands = new JsonCommands();
        final Map<String, String> before = Map.of("a", "b", "c", "d");
        final String json = jsonCommands.toJson(before);
        final Map<String, String> after = DAO.getInstance().getMapper().readValue(json, Map.class);
        Assert.assertEquals(before, after);
    }
}