package org.paulsens.trip.audit;

import java.lang.reflect.Method;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Which sinks the audit trail chooses at startup.
 *
 * <p>This is worth a test because getting it wrong is undetectable at runtime. The selection happens once, in a
 * class initializer, on whichever thread audits first -- so a wrong answer is not an error, it is an audit page
 * that is permanently and silently empty.
 *
 * <p>The specific trap: {@code FakeData.isLocal()} reports LOCAL whenever there is no {@code FacesContext},
 * which is true of any startup task, scheduled job or test harness. Keying the DynamoDB sink off it would have
 * disabled indexing for the life of any JVM that happened to audit from a non-request thread first.
 */
public class SinkSelectionTest {

    @AfterMethod
    public void clearProperties() {
        System.clearProperty("trip.audit.log.group");
        System.clearProperty(Audit.DYNAMO_INDEX_PROP);
    }

    private static boolean dynamoEnabled() throws Exception {
        final Method method = Audit.class.getDeclaredMethod("dynamoIndexEnabled");
        method.setAccessible(true);
        return (boolean) method.invoke(null);
    }

    @Test
    public void indexingFollowsTheDeploymentSignalNotTheRequestContext() throws Exception {
        // There is no FacesContext in this test -- exactly the situation that used to disable indexing.
        System.setProperty("trip.audit.log.group", "/trip/audit");
        Assert.assertTrue(dynamoEnabled(),
                "A deployed JVM must index even when the deciding thread is not serving a request");
    }

    @Test
    public void indexingIsOffWithoutTheDeploymentSignal() throws Exception {
        // Local runs and unit tests: no log group, so no table writes and no AWS dependency.
        Assert.assertFalse(dynamoEnabled());
    }

    @Test
    public void explicitOverrideWins() throws Exception {
        // How an integration test opts into the real table.
        System.setProperty(Audit.DYNAMO_INDEX_PROP, "true");
        Assert.assertTrue(dynamoEnabled());

        System.setProperty("trip.audit.log.group", "/trip/audit");
        System.setProperty(Audit.DYNAMO_INDEX_PROP, "false");
        Assert.assertFalse(dynamoEnabled(), "An explicit off must beat the deployment signal");
    }
}
