package com.sun.jsft.component;

import java.util.Map;

import jakarta.faces.FactoryFinder;
import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.PhaseId;
import jakarta.faces.render.RenderKitFactory;
import jakarta.faces.render.ResponseStateManager;


/**
 *  <p>	This UIViewRoot can be used to restore the ViewScope state upon
 *	creation.  This overcomes the JSF shortfall where the ViewScope is not
 *	available until after the UIViewTree is completely restored.  This
 *	means that events fired during creation cannot use the ViewScope.
 *	Caveat: ViewScope changes that happen during View creation are not
 *	likely to be persisted at this time since the data will likely be
 *	overwritten later in the cycle -- so it is best to treat ViewScope data
 *	during "postAddToView" events as "read-only" data.  I may fix this at
 *	some point in the future, however...</p>
 */
public class ViewScopeViewRoot extends UIViewRoot {

    /**
     *	<p> Must restore the ViewState in the constructor because events which
     *	    may use it get fired very soon afterward.  Note: The setViewId()
     *	    method may be called before the 1st event and may be another
     *	    possible place to implement this code.</p>
     */
    public ViewScopeViewRoot() {
        super();

        // FacesContext
        FacesContext ctx = FacesContext.getCurrentInstance();

        // If this is not RESTORE_VIEW, don't restore (i.e. here by navigation)
        if (!ctx.getCurrentPhaseId().equals(PhaseId.RESTORE_VIEW)) {
            // skip restoring the ViewScope, its not for this instance...
            return;
        }

        // Mock the FacesContext so PreDestroyViewMap events don't fail
        ctx.setViewRoot(this);

        // View Id
        String viewId = (String) ctx.getAttributes().get(CURR_VIEW_ID);
        if (viewId == null) {
            throw new IllegalStateException(
                "The FacesContext must have an attribute set with the name: "
                + getClass().getName() + ".CURR_VIEW_ID ('" + CURR_VIEW_ID
                + "'), and the value equal to the current viewId. This is "
                + "typically done by overriding the ViewHandler and setting "
                + "this value in createView AND restoreView methods.");
        }

        // RenderKit Id
        String rkId = ctx.getApplication().getViewHandler().calculateRenderKitId(ctx);

        // ResponseStateManager
        ResponseStateManager manager = ((RenderKitFactory) FactoryFinder.
                getFactory(FactoryFinder.RENDER_KIT_FACTORY)).
                getRenderKit(ctx, rkId).getResponseStateManager();

        // Get the viewState
        Object state[] = (Object []) manager.getState(ctx, viewId);
        if (state != null) {
            // Found it!
            if (!(state[1] instanceof Object[])) {
                // Get clientId can't call getClientId(ctx) b/c the UIViewRoot
                // hasn't been created yet, so it's not in the FacesContext and
                // will fail to generate an id. Do it ourselves...
                String cid = this.getId();
                if (cid == null) {
                    // It will be null... no id is set on the UIViewRoot
                    cid = createUniqueId();
                    setId(cid); // Ensure this is set for the next time...
                }

                // Partial state saving (JSF 2 default...)
                Map<String, Object> states = (Map<String, Object>) state[1];
                Object viewState = (states == null) ? null : states.get(cid);
                if (viewState != null) {
                    // Don't restore everything now...
                    restoreAllState = false;
                    // Restore the viewMap
                    restoreState(ctx, viewState);
                    // Reset flag
                    restoreAllState = true;
                }
            } else {
                // Full state saving
                // Not currently handled... event cycle is different, this code
                // path is not applicable for what we're trying to do for now
            }
        }
    }

    /**
     *	<p> This method saves the super state as well as the View Map.</p>
     */
    @Override
    public Object saveState(FacesContext context) {
        Object [] values = new Object[2];
        values[0] = super.saveState(context);
        values[1] = getViewMap(false);
        return values;
    }

    /**
     *	<p> This is overriden to provide the ability to restore the View Map
     *	    from the given state info.  If {@link restoreAllState} is false,
     *	    it will restore only the View Map.</p>
     */
    @Override
    public void restoreState(FacesContext context, Object state) {
        Object values[] = (Object []) state;

        // Handle the super behavior (unless we only want to restore viewMap)
        if (restoreAllState) {
            super.restoreState(context, values[0]);
        }

        // Get the viewMap
        Map<String, Object> restoredViewMap = (Map<String, Object>) values[1];

        // Mojarra hack to Set to prevent saving multiple viewMaps per user
        // in session -- we are recreating everytime each request anyway.
// FIXME: Restore this, or make it safe w/ older versions prior to JSF 2.1
        //getTransientStateHelper().putTransient(
        //    "com.sun.faces.application.view.viewMapId", "-1");

        // Get the default Map (may be backed by session), this allows the
        // internal impl to do what it needs to do
        Map<String, Object> origMap = getViewMap(true);

        // Clear it (we want it to be clean)
        origMap.clear();

        // Copy the restored values into the system-managed viewMap
        origMap.putAll(restoredViewMap);

        // Set the Mojarra state variable -- this is Mojarra only...
        //    putAll() should work in all environments
        //getTransientStateHelper().putTransient(
            //"com.sun.faces.application.view.viewMap", restoredViewMap);
    }


    /**
     *	<p> Only <code>UIComponent</code> seems to provide a valid
     *	    equals(Object) method.  This method is important to ensure the view
     *	    scope is reset when navigating to the same page.  In this case, the
     *	    instance will be different... but equals() should be true.</p>
     */
    public boolean equals(Object obj) {
        // Sanity check
        if (obj == null) {
            return false;
        }

        // Check the class names
        if (this.getClass().getName().equals(obj.getClass().getName())) {
            // Same class... check the viewId's:
            UIViewRoot root = (UIViewRoot) obj;
            String myViewId = "" + this.getViewId();
            String otherViewId = "" + root.getViewId();
            if (myViewId.equals(otherViewId)) {
                return true;
            }
        }

        // Not a match
        return false;
    }

    // false will restore only the viewMap
    private boolean restoreAllState = true;

    /**
     *	<p> This Request-scoped key must be used to pass the current
     *	    <code>viewId</code> from the ViewHandler <code>createView</code>
     *	    and <code>restoreView</code> methods.  If this is not done, the
     *	    viewScope state cannot be restored.</p>
     */
    public static String CURR_VIEW_ID	= "jsftCVID";
}
