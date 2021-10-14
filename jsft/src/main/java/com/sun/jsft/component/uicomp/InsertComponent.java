package com.sun.jsft.component.uicomp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import jakarta.faces.component.UIComponent;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ComponentSystemEvent;
import jakarta.faces.event.ComponentSystemEventListener;


/**
 *  <p>	This component provides the functionality to insert component(s)
 *	before or after the specified id.  Usage:</p>
 *
 *  <p>	<code>
 *	&lt;jsft:insertComponent id="foo" target="some:target:component"&gt;<br />
 *	    &lt;component(s)ToInsert /&gt;<br />
 *	&lt;/jsft:insertComponent&gt;<br />
 *	</code></p>
 *
 *  <p>	OR:</p>
 *
 *  <p>	<code>
 *	&lt;jsft:insertComponent id="foo" target="some:target:component" src="some:existing:component:to:insert" before="true|false" /&gt;<br />
 *	</code></p>
 *
 *  <p>	Note: Both <code>target</code> and <code>src</code> attributes may be
 *	absolute or relative ids, and they may also be a
 *	<code>UIComponent</code> object.</p>
 */
public class InsertComponent extends ModComponentBase {

    /**
     *	<p> This method returns the <code>ComponentSystemEventListener</code>
     *	    instance which performs the work done by this class.</p>
     */
    @Override
    public ComponentSystemEventListener getComponentSystemEventListener() {
	if (compListener == null) {
	    compListener = new InsertComponent.PreRenderViewListener(this);
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
     *	<p> This method returns <code>true</code> if the insert should happen
     *	    <em>before</em> the target. <code>false</code> if it should occur
     *	    <i>after</i>.  Default: <code>false</code>.</p>
     */
    public boolean isBefore() {
	boolean result = false;
	Object val = getStateHelper().eval(InsertPropertyKeys.before);
	if (val != null) {
	    result = (Boolean) val;
	}
	return result;
    }

    /**
     *	<p> Set this to <code>true</code> if the insert should happen
     *	    <em>before</em> the target component. <code>false</code> if it
     *	    should insert after it.</p>
     */
    public void setBefore(boolean before) {
	getStateHelper().put(InsertPropertyKeys.before, before);
    }

    /**
     *	<p> Listener used to relocate the children.</p>
     */
    public static class PreRenderViewListener extends ModComponentBase.PreRenderViewListenerBase<InsertComponent> {
	/**
	 *  Constructor.  Do not use... for deserialization only.
	 */
	public PreRenderViewListener() {
	    this(null);
	}

	/**
	 *  Constructor.
	 */
	public PreRenderViewListener(InsertComponent comp) {
	    super(comp);
	}

	/**
	 *  <p>	Perform the move.</P>
	 */
	public void processEvent(ComponentSystemEvent event) throws AbortProcessingException {
	    InsertComponent insComp = getModComponent();
	    if (insComp == null) {
		// Here due to deserialization, ignore...
		return;
	    }

	    // Get the component & children (copy so removing events from
	    // original does not break our iteration)
	    List<UIComponent> srcKids = getSourceComponents();
	    if (!srcKids.isEmpty()) {
		// Find the target component to insert before/after
		UIComponent targetComp = getTargetComponent();
		if (targetComp == null) {
		    throw new IllegalArgumentException(
			    "No 'target' property was specified on component '"
			    + insComp.getClientId() + "'");
		}

		// Get the 1st child and insert it before or after (remaining
		// srcKids will be inserted after this)
		Iterator<UIComponent> iter = srcKids.iterator();
		UIComponent newKid = iter.next();
		COMP_COMMANDS.insertUIComponent(insComp.isBefore(), targetComp, newKid);

		// Loop through remaining srcKids and insert them after newKid
		while (iter.hasNext()) {
		    targetComp = newKid;
		    newKid = iter.next();
		    COMP_COMMANDS.insertUIComponentAfter(targetComp, newKid);
		}
	    }

	    // This will remove this insert component...
	    super.processEvent(event);
	}
    }

    enum InsertPropertyKeys {
	before
    }

    /**
     *	<p> Listener instance.</p>
     */
    private transient ComponentSystemEventListener compListener = null;

    /**
     *	<p> The component family.</p>
     */
    public static final String FAMILY	=   InsertComponent.class.getName();
}
