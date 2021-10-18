package com.sun.jsft.component.uicomp;

import jakarta.faces.component.UIComponent;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ComponentSystemEvent;
import jakarta.faces.event.ComponentSystemEventListener;

/**
 * <p> This component provides the functionality to remove a component
 *     specified by the given <code>target</code>.  Target can be an
 *     <code>id</code> or the actual <code>UIComponent</code> to remove.</p>
 *
 *  <p> <code>
 *        &lt;jsft:removeComponent id="foo" target="target:component:to:remove" /&gt;<br />
 *      </code></p>
 */
public class RemoveComponent extends ModComponentBase {

    /**
     * <p> This method should return the <code>ComponentSystemEventListener</code> instance which performs
     *     the work done by this class.</p>
     */
    @Override
    public ComponentSystemEventListener getComponentSystemEventListener() {
        if (compListener == null) {
            compListener = new RemoveComponent.PreRenderViewListener(this);
        }
        return compListener;
    }

    @Override
    public String getFamily() {
        return FAMILY;
    }

    /**
     * <p> Overriden to throw <code>UnsupportedOperationException</code>.</p>
     */
    @Override
    public String getSrc() {
        throw new UnsupportedOperationException(
                "The 'src' attribute is not valid for jsft:remove.");
    }

    /**
     * <p> Overriden to throw <code>UnsupportedOperationException</code>.</p>
     */
    @Override
    public void setSrc(Object src) {
        throw new UnsupportedOperationException(
                "The 'src' attribute is not valid for jsft:remove.");
    }

    /**
     * <p> Listener used to remove the target.</p>
     */
    public static class PreRenderViewListener extends ModComponentBase.PreRenderViewListenerBase<RemoveComponent> {
        /**
         *  Constructor.  Do not use... for deserialization only.
         */
        public PreRenderViewListener() {
            this(null);
        }

        /**
         *  Constructor.
         */
        public PreRenderViewListener(RemoveComponent comp) {
            super(comp);
        }

        /**
         *  <p>        Perform the removal.</P>
         */
        public void processEvent(ComponentSystemEvent event) throws AbortProcessingException {
            RemoveComponent modComp = getModComponent();
            if (modComp == null) {
                // Here due to deserialization, ignore...
                return;
            }

            // Find the target to remove
            UIComponent targetComp = getTargetComponent();
            if (targetComp == null) {
                // Do nothing...
                return;
            }

            // Remove target
            COMP_COMMANDS.replaceUIComponent(targetComp, null);

            // This will remove this remove component...
            super.processEvent(event);
        }
    }

    /**
     * <p> The component family.</p>
     */
    public static final String FAMILY        =   RemoveComponent.class.getName();

    /**
     * <p> Listener instance.</p>
     */
    private transient ComponentSystemEventListener compListener = null;
}
