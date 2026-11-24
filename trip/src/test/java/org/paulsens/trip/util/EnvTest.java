package org.paulsens.trip.util;

import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.regions.Region;

public class EnvTest {
    private static final String PROP = "trip.envtest.setting";

    /** Some variable this process really has, so the environment leg is exercised without being able to set one. */
    private static Map.Entry<String, String> anEnvVar() {
        return System.getenv().entrySet().stream()
                .filter(entry -> !entry.getValue().isBlank())
                .filter(entry -> System.getProperty(entry.getKey().toLowerCase(java.util.Locale.ROOT)
                        .replace('_', '.')) == null)
                .findFirst().orElseThrow();
    }

    @Test
    public void aNonBlankPropertyWinsAndIsTrimmed() {
        final Map.Entry<String, String> env = anEnvVar();
        System.setProperty(PROP, "  from-prop  ");
        try {
            Assert.assertEquals(Env.setting(PROP, env.getKey()), "from-prop");
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void aBlankOrMissingPropertyFallsBackToTheEnvironment() {
        final Map.Entry<String, String> env = anEnvVar();
        Assert.assertEquals(Env.setting(PROP, env.getKey()), env.getValue().trim());
        System.setProperty(PROP, "   ");
        try {
            Assert.assertEquals(Env.setting(PROP, env.getKey()), env.getValue().trim(),
                    "a blank property is unset, not an override");
        } finally {
            System.clearProperty(PROP);
        }
        Assert.assertEquals(Env.setting(env.getKey()), env.getValue().trim(), "the derived property name is unset");
    }

    @Test
    public void unsetEverywhereIsNull() {
        Assert.assertNull(Env.setting(PROP, "TRIP_ENVTEST_NO_SUCH_VARIABLE"));
        Assert.assertNull(Env.setting("TRIP_ENVTEST_NO_SUCH_VARIABLE"));
    }

    @Test
    public void theDerivedPropertyIsTheVariableNameInDots() {
        System.setProperty("trip.envtest.derived", "x");
        try {
            Assert.assertEquals(Env.setting("TRIP_ENVTEST_DERIVED"), "x");
        } finally {
            System.clearProperty("trip.envtest.derived");
        }
    }

    @Test
    public void theRegionDefaultsToTheActiveDeploymentAndCanBeOverridden() {
        final String before = System.getProperty(Env.REGION_PROP);
        try {
            System.setProperty(Env.REGION_PROP, " eu-central-1 ");
            Assert.assertEquals(Env.awsRegion(), Region.EU_CENTRAL_1);
            System.clearProperty(Env.REGION_PROP);
            if (System.getenv(Env.REGION_VAR) == null) {
                Assert.assertEquals(Env.awsRegion(), Region.US_WEST_2);
            }
        } finally {
            if (before != null) {
                System.setProperty(Env.REGION_PROP, before);
            }
        }
    }
}
