package com.sun.jsft.component.uicomp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import jakarta.faces.component.UIComponent;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ComponentSystemEvent;
import jakarta.faces.event.ComponentSystemEventListener;


/**
 *  <p>	This component provides the functionality to replace a component with
 *	one or more new components. Usage:</p>
 *
 *  <p>	<code>
 *	&lt;jsft:replaceComponent id="foo" target="some:target:component"&gt;<br />
 *	    &lt;component(s)ToUseInReplacement /&gt;<br />
 *	&lt;/jsft:replaceComponent&gt;<br />
 *	</code></p>
 *
 *  <p>	OR:</p>
 *
 *  <p>	<code>
 *	&lt;jsft:replaceComponent id="foo" target="some:target:component" src="existing:component:to:use:in:replacement" /&gt;<br />
 *	</code></p>
 *
 *  <p>	Note: Both <code>target</code> and <code>src</code> attributes may be
 *	absolute or relative ids, and they may also be a
 *	<code>UIComponent</code> object.</p>
 */
public class ReplaceComponent extends ModComponentBase {

    /**
     *	<p> This method returns the <code>ComponentSystemEventListener</code>
     *	    instance which performs the work done by this class.</p>
     */
    @Override
    public ComponentSystemEventListener getComponentSystemEventListener() {
	if (compListener == null) {
	    compListener = new ReplaceComponent.PreRenderViewListener(this);
	}
	return compListener;
    }

    /**
     *
     */
    public String getFamily() {
	return FAMILY;
    }

    /**
     *	<p> Listener used to replace the children.</p>
     */
    public static class PreRenderViewListener extends ModComponentBase.PreRenderViewListenerBase<ReplaceComponent> {
	/**
	 *  Constructor.  Do not use... for deserialization only.
	 */
	public PreRenderViewListener() {
	    this(null);
	}

	/**
	 *  Constructor.
	 */
	public PreRenderViewListener(ReplaceComponent comp) {
	    super(comp);
	}

	/**
	 *  <p>	Perform the replacement.</P>
	 */
	public void processEvent(ComponentSystemEvent event) throws AbortProcessingException {
	    ReplaceComponent modComp = getModComponent();
	    if (modComp == null) {
		// Here due to deserialization, ignore...
		return;
	    }

	    // Get the component & children
	    List<UIComponent> children = getSourceComponents();
	    if (children.isEmpty()) {
		// Treat as delete
		children = null;
	    }

	    // Find the target in which to add children
	    UIComponent targetComp = getTargetComponent();
	    if (targetComp == null) {
		throw new IllegalArgumentException(
			"No 'target' property was specified on component '"
			+ modComp.getClientId() + "'");
	    }

	    // Replace Children to target...
	    if (children == null) {
		// Delete
		COMP_COMMANDS.replaceUIComponent(targetComp, null);
	    } else {
		// Replace target w/ new kids...
		// 1st Replace w/ first kid
		Iterator<UIComponent> iter = children.iterator();
		UIComponent newKid = iter.next();
		COMP_COMMANDS.replaceUIComponent(targetComp, newKid);

		// Next... insert after last inserted kid for rest of children
		while (iter.hasNext()) {
		    targetComp = newKid;
		    newKid = iter.next();
		    COMP_COMMANDS.insertUIComponentAfter(targetComp, newKid);
		}
	    }

	    // This will remove this replace component...
	    super.processEvent(event);
	}
    }

    /**
     *	<p> Listener instance.</p>
     */
    private transient ComponentSystemEventListener compListener = null;

    /**
     *	<p> The component family.</p>
     */
    public static final String FAMILY	=   ReplaceComponent.class.getName();
}
