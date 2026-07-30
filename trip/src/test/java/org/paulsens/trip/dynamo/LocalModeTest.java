package org.paulsens.trip.dynamo;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * How local mode is decided.
 *
 * <p>This is worth testing because getting it wrong is invisible everywhere it could be caught. The whole test
 * suite -- unit, webtest and integration -- deliberately runs in local mode, so a bug that makes local mode
 * <em>too easy to enter</em> makes every test greener, not redder. On 2026-07-30 that shipped: the mode was
 * inferred as "no {@code FacesContext} means local", the chat digest scheduler became the first thing to touch
 * the {@code DAO} at context startup, and the entire production deployment served fake data -- no real people,
 * no real trips, no real logins.
 *
 * <p>So these tests assert the *direction* of the default, not just the mechanism. Production is what you get
 * when nobody asked for anything else.
 */
public class LocalModeTest {

    /**
     * Surefire sets {@value LocalMode#LOCAL_PROP} for the whole forked JVM, and every other test in it depends on
     * that: without it they resolve to production and reach for real DynamoDB. So this class RESTORES the property
     * it found rather than clearing it -- clearing it would leave the rest of the suite pointed at live tables,
     * which is the same accident in miniature that this class exists to prevent.
     */
    private String originalProperty;

    @BeforeMethod
    public void rememberProperty() {
        originalProperty = System.getProperty(LocalMode.LOCAL_PROP);
    }

    @AfterMethod
    public void resetMode() {
        LocalMode.reset();
        if (originalProperty == null) {
            System.clearProperty(LocalMode.LOCAL_PROP);
        } else {
            System.setProperty(LocalMode.LOCAL_PROP, originalProperty);
        }
    }

    @Test
    public void aJvmThatAsksForNothingIsNotLocal() {
        // The regression. There is no FacesContext on this thread and no container has resolved anything --
        // exactly the state of a startup listener, a JAX-RS resource or a scheduled task inside a real
        // deployment. Answering "local" here is what silently swapped production's datastore for FakeData.
        System.clearProperty(LocalMode.LOCAL_PROP);
        Assert.assertFalse(LocalMode.isLocal(),
                "Local mode must never be inferred; with nothing asking for it the answer is production");
    }

    @Test
    public void theDescriptorTurnsLocalModeOn() {
        LocalMode.resolveFrom(contextWithLocal("true"));
        Assert.assertTrue(LocalMode.isLocal(), "local=true in web.xml is the supported way in");
    }

    @Test
    public void theDescriptorKeepsProductionOnWithoutAFacesContext() {
        // The deployed descriptor says local=false, and this thread has no FacesContext. Before the fix the
        // missing FacesContext won and the deployment went fake; now the descriptor is the authority.
        LocalMode.resolveFrom(contextWithLocal("false"));
        Assert.assertFalse(LocalMode.isLocal(), "A deployment that says local=false must stay on the real store");
    }

    @Test
    public void theDescriptorOutranksAnAmbientProperty() {
        // A stray TRIP_LOCAL_MODE / -Dtrip.local.mode in whatever shell launched the container must not be able
        // to flip a real deployment onto fake data. Inside a container the descriptor decides, full stop.
        System.setProperty(LocalMode.LOCAL_PROP, "true");
        LocalMode.resolveFrom(contextWithLocal("false"));
        Assert.assertFalse(LocalMode.isLocal(), "web.xml must outrank the property when both are present");
    }

    @Test
    public void thePropertyIsTheWayInForATestJvm() {
        // No servlet container at all: mvn test. Surefire sets this property, which is why the suite gets fake
        // data without any test having to arrange it.
        System.setProperty(LocalMode.LOCAL_PROP, "true");
        Assert.assertTrue(LocalMode.isLocal());
    }

    @Test
    public void aMissingFacesServletIsNotLocal() {
        // A descriptor that does not register the Faces Servlet under the expected name yields no init-param.
        // Guessing "local" from a lookup failure is the same class of mistake as guessing it from a thread.
        final ServletContext context = Mockito.mock(ServletContext.class);
        Mockito.when(context.getServletRegistration(Mockito.anyString())).thenReturn(null);
        LocalMode.resolveFrom(context);
        Assert.assertFalse(LocalMode.isLocal(), "An unreadable descriptor must fail towards production");
    }

    @Test
    public void aNullContextIsNotLocal() {
        LocalMode.resolveFrom(null);
        Assert.assertFalse(LocalMode.isLocal());
    }

    @Test
    public void fakeDataAgreesWithTheResolvedMode() {
        // FakeData.isLocal() is what the DAO actually calls, so the delegation is part of the contract.
        LocalMode.resolveFrom(contextWithLocal("false"));
        Assert.assertFalse(FakeData.isLocal());
        LocalMode.resolveFrom(contextWithLocal("true"));
        Assert.assertTrue(FakeData.isLocal());
    }

    private static ServletContext contextWithLocal(final String value) {
        final ServletRegistration registration = Mockito.mock(ServletRegistration.class);
        Mockito.when(registration.getInitParameter("local")).thenReturn(value);
        final ServletContext context = Mockito.mock(ServletContext.class);
        Mockito.when(context.getServletRegistration("Faces Servlet")).thenReturn(registration);
        return context;
    }
}
