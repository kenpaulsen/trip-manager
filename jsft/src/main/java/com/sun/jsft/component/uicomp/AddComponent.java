package com.sun.jsft.component.uicomp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.faces.component.UIComponent;
import javax.faces.event.AbortProcessingException;
import javax.faces.event.ComponentSystemEvent;
import javax.faces.event.ComponentSystemEventListener;


/**
 *  <p>	This component provides the functionality to add component(s) at the
 *	end of specified id's children.  Usage:</p>
 *
 *  <p>	<code>
 *	&lt;jsft:addComponent id="foo" target="some:target:component"&gt;<br />
 *	    &lt;component(s)ToAdd /&gt;<br />
 *	&lt;/jsft:addComponent&gt;<br />
 *	</code></p>
 *
 *  <p>	OR:</p>
 *
 *  <p>	<code>
 *	&lt;jsft:addComponent id="foo" target="some:target:component" src="some:existing:component:to:add" before="true|false" /&gt;<br />
 *	</code></p>
 *
 *  <p>	Note: Both <code>target</code> and <code>src</code> attributes may be
 *	absolute or relative ids, and they may also be a
 *	<code>UIComponent</code> object.</p>
 */
public class AddComponent extends InsertComponent {

    /**
     *	<p> This method returns the <code>ComponentSystemEventListener</code>
     *	    instance which performs the work done by this class.</p>
     */
    @Override
    public ComponentSystemEventListener getComponentSystemEventListener() {
	if (compListener == null) {
	    compListener = new AddComponent.PreRenderViewListener(this);
	}
	return compListener;
    }

    /**
     *
     */
    @Override
    public String getFamily() {
	return FAMILY;
    }

    /**
     *	<p> Listener used to relocate the children.</p>
     */
    public static class PreRenderViewListener extends ModComponentBase.PreRenderViewListenerBase<AddComponent> {
	/**
	 *  Constructor.  Do not use... for deserialization only.
	 */
	public PreRenderViewListener() {
	    this(null);
	}

	/**
	 *  Constructor.
	 */
	public PreRenderViewListener(AddComponent comp) {
	    super(comp);
	}

	/**
	 *  <p>	Perform the move.</P>
	 */
	public void processEvent(ComponentSystemEvent event) throws AbortProcessingException {
	    AddComponent modComp = getModComponent();
	    if (modComp == null) {
		// Here due to deserialization, ignore...
		return;
	    }

	    // Get the component & srcKids
	    List<UIComponent> srcKids = getSourceComponents();
	    if (!srcKids.isEmpty()) {
		boolean done = false;

		// Find the target in which to add srcKids
		UIComponent targetComp = getTargetComponent();
		if (targetComp == null) {
		    throw new IllegalArgumentException(
			    "No 'target' property was specified on component '"
			    + modComp.getClientId() + "'");
		}

		// Check to see if we want it at the beginning or the end...
		List<UIComponent> targetKids = targetComp.getChildren();
		if (modComp.isBefore() && !targetKids.isEmpty()) {
		    // Really doing an insert before the 1st child of target...
		    // Get the 1st target kid
		    targetComp = targetKids.get(0);

		    // Get the 1st child and insert it before or after (rest
		    // srcKids will be inserted after this)
		    Iterator<UIComponent> iter = srcKids.iterator();
		    UIComponent newKid = iter.next();
		    COMP_COMMANDS.insertUIComponent(true, targetComp, newKid);

		    // Loop through remaining srcKids and insert after newKid
		    while (iter.hasNext()) {
			targetComp = newKid;
			newKid = iter.next();
			COMP_COMMANDS.insertUIComponentAfter(targetComp, newKid);
		    }

		    // Move complete... we're done.
		    done = true;
		} // else fall through to regular add-to-end case b/c no kids

		if (!done) {
		    // Add Children to target...
		    Iterator<UIComponent> iter = srcKids.iterator();
		    while (iter.hasNext()) {
			COMP_COMMANDS.addUIComponent(targetComp, iter.next());
		    }
		}
	    }

	    // This will remove this add component...
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
    public static final String FAMILY	=   AddComponent.class.getName();
}
