package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class BudgetLodgingLineTest {

    @Test
    public void builderMatchesTheNoArgConstructorOnceTheIdIsShared() {
        final BudgetLodgingLine built = BudgetLodgingLine.builder().build();
        final BudgetLodgingLine plain = new BudgetLodgingLine();
        assertNotNull(built.getId());
        plain.setId(built.getId());
        assertEquals(built, plain);
    }

    @Test
    public void countsAreClampedAndSinglesNeverExceedPeople() {
        final BudgetLodgingLine line = BudgetLodgingLine.builder().nights(-1).people(4).rooms(-2).singles(9)
                .offerId(" ").label("  Pansion · Double  ").payee("").note(" n ").build();
        assertEquals(line.getNights(), 0);
        assertEquals(line.getRooms(), 0);
        assertEquals(line.getSingles(), 4);
        assertNull(line.getOfferId());
        assertNull(line.getPayee());
        assertEquals(line.getLabel(), "Pansion · Double");
        assertEquals(line.getNote(), "n");
        assertEquals(BudgetLodgingLine.builder().people(-3).singles(-1).build().getSingles(), 0);
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final BudgetLodgingLine line = BudgetLodgingLine.builder().id("x").offerId("o").label("L").nights(10)
                .people(46).rooms(28).singles(10).payee("Huanita").note("note").build();
        assertEquals(mapper.readValue(mapper.writeValueAsString(line), BudgetLodgingLine.class), line);
    }
}
