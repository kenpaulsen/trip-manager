package org.paulsens.tckt.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BindingTest {

    @Test
    public void equalsWorks() {
        EqualsVerifier.forClass(Binding.class)
                .withIgnoredFields("id")
                .verify();
    }

    @Test
    public void canCreateABinding() {
        final String srcId = RandomData.genAlpha(3);
        final String destId = RandomData.genAlpha(7);
        final Binding binding = new Binding(null, srcId, destId, BindingType.COURSE, BindingType.USER);
        Assert.assertEquals(binding.getSrcId().getValue(), srcId);
        Assert.assertTrue(binding.getSrcId() instanceof Course.Id);
        Assert.assertEquals(binding.getDestId().getValue(), destId);
        Assert.assertTrue(binding.getDestId() instanceof User.Id);
        Assert.assertEquals(binding.getSrcType(), BindingType.COURSE);
        Assert.assertEquals(binding.getDestType(), BindingType.USER);
    }
}