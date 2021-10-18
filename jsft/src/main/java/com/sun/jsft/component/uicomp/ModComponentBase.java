package com.sun.jsft.component.uicomp;

import com.sun.jsft.commands.ComponentCommands;
import jakarta.faces.component.NamingContainer;
import jakarta.faces.component.UIComponent;
import jakarta.faces.component.UIComponentBase;
import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ComponentSystemEvent;
import jakarta.faces.event.ComponentSystemEventListener;
import jakarta.faces.event.PhaseId;
import jakarta.faces.event.PreRenderViewEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
 *  <p> This component provides the functionality to insert component(s) before or after the specified id.</p>
 */
public abstract class ModComponentBase extends UIComponentBase implements NamingContainer {

    /**
     * <p> Default constructor.</p>
     */
    public ModComponentBase() {
        FacesContext ctx = FacesContext.getCurrentInstance();

        // Ensure we have a FacesContext 
        if (ctx == null) {
            // Test env?
            return;
        }

        // May be null in test environment
        boolean isPost = ctx.isPostback();

        // Ensure the request is either a GET (!POST) ... *OR* a POST but
        // it's not currently in the RESTORE_VIEW phase
        if (!isPost || (isPost && !ctx.getCurrentPhaseId().equals(PhaseId.RESTORE_VIEW))) {
// HACK: Mojarra recreates this component during the RENDER phase for some reason
            UIViewRoot viewRoot = ctx.getViewRoot();
boolean viewPopulated = ctx.getAttributes().containsKey(viewRoot);
//System.out.println("############# Mojarra hack: '" + viewPopulated + "'");
if (!viewPopulated) {
// Don't do this if we've already done it...
            viewRoot.subscribeToEvent(
                PreRenderViewEvent.class,
                getComponentSystemEventListener());
}
        }
    }

    /**
     * <p> This method should return the <code>ComponentSystemEventListener</code> instance which performs
     *     the work done by this class.</p>
     */
    public abstract ComponentSystemEventListener getComponentSystemEventListener();

    public abstract String getFamily();

    @Override
    public boolean getRendersChildren() {
        return true;
    }

    /**
     * <p> Do nothing.  This component has no visual effect of its own.</p>
     */
    @Override
    public void encodeBegin(FacesContext context) throws IOException {
        // Override the default behavior and do nothing... save time.
    }

    /**
     * <p> Do nothing.  This component has no visual effect of its own.</p>
     */
    @Override
    public void encodeEnd(FacesContext ctx) throws IOException {
        // Override the default behavior and do nothing... save time.
    }

    /**
     * <p> This method returns the component or id of the target receiving the new child(ren).</p>
     */
    public Object getTarget() {
        return getStateHelper().eval(PropertyKeys.target);
    }

    /**
     * <p> This method sets the component or id of the target receiving the new child(ren).</p>
     */
    public void setTarget(Object target) {
        getStateHelper().put(PropertyKeys.target, target);
    }

    /**
     * <p> This method returns the component or id of the source of the new child(ren).</p>
     */
    public Object getSrc() {
        return getStateHelper().eval(PropertyKeys.source);
    }

    /**
     * <p> This method sets the component or id of the source of the new child(ren).</p>
     */
    public void setSrc(Object src) {
        getStateHelper().put(PropertyKeys.source, src);
    }

    /**
     * <p> Listener base class.</p>
     */
    public static class PreRenderViewListenerBase<T extends ModComponentBase> implements ComponentSystemEventListener {
        /**
         *  Constructor.  Do not use... for deserialization only.
         */
        public PreRenderViewListenerBase() {
            this(null);
        }

        /**
         *  Constructor.
         */
        public PreRenderViewListenerBase(T comp) {
            this.comp = comp;
        }

        /**
         *  <p> Perform the insert/update/delete.  It is expected this method
         *      will be overridden to perform the desired action.  However,
         *      after the action has been performed, this implementation should
         *      be called (i.e. <code>super.processEvent(event)</code>).  This
         *      implementation removes this component from the UIComponent tree
         *      to clean up.  This prevents JSF from attempting to
         *      [de]serialize this object, which will fail.</P>
         */
        public void processEvent(ComponentSystemEvent event) throws AbortProcessingException {
            // Remove event listener...
            // Can't... causes an exception during event listener iteration...
            //UIViewRoot viewRoot = FacesContext.getCurrentInstance().getViewRoot();
            //viewRoot.unsubscribeFromEvent(PreRenderViewEvent.class, this);

            // Remove component
            COMP_COMMANDS.replaceUIComponent(getModComponent(), null);
        }

        /**
         * <p> This returns the <code>UIComponent</code> which is defining the operation
         *     (insert, replace, remove, etc.).</p>
         */
        public T getModComponent() {
            return this.comp;
        }

        /**
         * <p> This method returns the target component in which the operation is to perform.</p>
         */
        public UIComponent getTargetComponent() {
            UIComponent result = null;

            // Find the target in which to add children
            Object target = getModComponent().getTarget();
            if (target == null) {
                return null;
            }

            // Ensure it is a UIComponent, or find it if it's an id
            result = (UIComponent) resolveComponent(target);
            if (result == null) {
                throw new IllegalArgumentException(
                        "Unable to find UIComponent target: '"
                        + target + "'");
            }

            return result;
        }

        private UIComponent resolveComponent(Object obj) {
            UIComponent result = null;
            if (obj instanceof UIComponent) {
                result = (UIComponent) obj;
            } else if (obj instanceof String) {
                // Need to find the UIComponent
                String id = (String) obj;
                if (id.contains(":")) {
                    // Use clientId search...
                    result = COMP_COMMANDS.getUIComponent(id);
                }
                if (result == null) {
                    // Use simple id search...
                    result = COMP_COMMANDS.findUIComponent(
                        (UIViewRoot) FacesContext.getCurrentInstance().
                        getViewRoot(), id);
                }
            }
            return result;
        }

        /**
         * <p> Returns a <code>List</code> of <code>UIComponent</code>s.  The
         *     list will be newly created so that any existing list (i.e.
         *     when the component's children are used) is not effected.  This
         *     eliminates concurrent modification exceptions if adding the
         *     list's UIComponents to somewhere else in the UIComponent
         *     tree.  When the <code>src</code> attribute is specified, only
         *     the component or id to the component specified by the src
         *     attribute will be returned by this method (i.e. single item
         *     list).</p>
         */
        public List<UIComponent> getSourceComponents() {
            List<UIComponent> result = null;

            // Find the src of the children
            T modComp = getModComponent();
            Object src = modComp.getSrc();
            if (src == null) {
                // Use ModComp kids
// https://java.net/jira/browse/JAVASERVERFACES-3332 / https://java.net/jira/browse/JAVASERVERFACES-3502
// NOTE: Instead of doing this here, do manually once for the entire page...
if (!replacedUIInstructions) {
    COMP_COMMANDS.fixUIInstructions(modComp, true);
    replacedUIInstructions = true;
}
                result = new ArrayList<UIComponent>(modComp.getChildren());
            } else {
                // resolve id to UIComponent and use it (single item List)
                UIComponent srcComp = resolveComponent(src);
                if (srcComp == null) {
                    throw new IllegalArgumentException(
                            "Unable to find UIComponent specified by the 'src'"
                            + " attribute: '" + src + "'");
                }

                // Create an ArrayList and add it
                result = new ArrayList<UIComponent>(1);
// https://java.net/jira/browse/JAVASERVERFACES-3332 / https://java.net/jira/browse/JAVASERVERFACES-3502
// NOTE: Instead of doing this here, do manually once for the entire page...
if (!replacedUIInstructions) {
    COMP_COMMANDS.fixUIInstructions(srcComp, true);
    replacedUIInstructions = true;
}
                result.add(srcComp);
            }

            return result;
        }

        protected static final ComponentCommands COMP_COMMANDS = ComponentCommands.getInstance();

        private boolean replacedUIInstructions = false;
        private T comp = null;
    }

    enum PropertyKeys {
        target,
        source
    }
}
