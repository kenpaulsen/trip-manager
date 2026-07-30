package org.paulsens.trip.web;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.paulsens.trip.dynamo.LocalMode;

/**
 * Resolves which datastore this deployment uses, before anything can ask.
 *
 * <p><b>This listener must stay first in {@code web.xml}.</b> Listeners run in declaration order and all of them
 * run before any servlet initialises or any request arrives, so being first is what guarantees
 * {@link LocalMode#isLocal()} has a real answer by the time the {@code DAO} singleton is constructed. The
 * ordering is load-bearing, not stylistic: {@code ChatLifecycleListener} touches the DAO in
 * {@code contextInitialized}, so if it ran first the mode would still be unresolved at the moment the decision
 * was made.
 *
 * <p>Declared by hand rather than annotated, like every other component here, because
 * {@code metadata-complete="true"} makes {@code @WebListener} a silent no-op.
 *
 * <p>It deliberately does nothing else. A bootstrap step that resolves one value cannot fail in a way that needs
 * handling, and keeping it to that means there is never a reason to move it later in the order.
 *
 * @see LocalMode for why the fallback direction is production and why nothing here sniffs the environment
 */
public class TripBootstrapListener implements ServletContextListener {

    @Override
    public void contextInitialized(final ServletContextEvent event) {
        LocalMode.resolveFrom(event.getServletContext());
    }
}
