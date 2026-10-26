package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class CurrencyRateTest {

    @Test
    public void builderMatchesTheNoArgConstructor() {
        assertEquals(CurrencyRate.builder().build(), new CurrencyRate());
    }

    @Test
    public void theCodeIsThreeLettersUpperCasedAndTheRateNeverNegative() {
        final CurrencyRate eur = CurrencyRate.builder().code(" eur ").perUsdMicros(830_000L).note("  ").build();
        assertEquals(eur.getCode(), "EUR");
        assertEquals(eur.getPerUsdMicros(), 830_000L);
        assertNull(eur.getNote());
        assertNull(CurrencyRate.builder().code("EURO").build().getCode());
        assertNull(CurrencyRate.builder().code("E1R").build().getCode());
        assertNull(CurrencyRate.normalizeCode(null));
        assertEquals(CurrencyRate.builder().perUsdMicros(-5L).build().getPerUsdMicros(), 0L);
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final CurrencyRate bam = CurrencyRate.builder().code("BAM").perUsdMicros(1_660_000L).note("Oct 3").build();
        assertEquals(mapper.readValue(mapper.writeValueAsString(bam), CurrencyRate.class), bam);
    }
}
