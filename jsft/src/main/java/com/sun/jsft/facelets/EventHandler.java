/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2011 Ken Paulsen
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.jsft.facelets;

import com.sun.jsft.event.AjaxBehaviorEventListener;
import com.sun.jsft.event.AjaxEvent;
import com.sun.jsft.event.Command;
import com.sun.jsft.event.CommandActionEvent;
import com.sun.jsft.event.CommandActionListener;
import com.sun.jsft.event.CommandEventListener;
import com.sun.jsft.event.InitGetEvent;
import com.sun.jsft.event.InitPageEvent;
import com.sun.jsft.event.InitPostEvent;
import com.sun.jsft.event.PFAjaxBehaviorEventListener;
import com.sun.jsft.util.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.faces.FacesException;
import javax.faces.component.ActionSource;
import javax.faces.component.UIComponent;
import javax.faces.component.behavior.AjaxBehavior;
import javax.faces.component.behavior.ClientBehavior;
import javax.faces.component.behavior.ClientBehaviorHolder;
import javax.faces.context.FacesContext;
import javax.faces.event.PostAddToViewEvent;
import javax.faces.event.PostConstructViewMapEvent;
import javax.faces.event.PostRestoreStateEvent;
import javax.faces.event.PostValidateEvent;
import javax.faces.event.PreDestroyViewMapEvent;
import javax.faces.event.PreRemoveFromViewEvent;
import javax.faces.event.PreRenderComponentEvent;
import javax.faces.event.PreRenderViewEvent;
import javax.faces.event.PreValidateEvent;
import javax.faces.event.SystemEvent;
import javax.faces.view.facelets.ComponentHandler;
import javax.faces.view.facelets.FaceletContext;
import javax.faces.view.facelets.TagAttribute;
import javax.faces.view.facelets.TagConfig;
import javax.faces.view.facelets.TagHandler;


/**
 *  <p>	This is the TagHandler for the jsft:event tag.</p>
 *
 *  Created  March 29, 2011
 *  @author  Ken Paulsen (kenapaulsen@gmail.com)
 */
public class EventHandler extends TagHandler {

    /**
     *	<p> Constructor.</p>
     */
    public EventHandler(TagConfig config) {
	super(config);
	
	// Get the tag attributes
	type = getAttribute("type");
	id = getAttribute("id");
	insert = getAttribute("insert");

	if ((type == null) && (id == null)) {
	    throw new FacesException(
		"Attribute 'type' or 'id' must be specified on jsft:event!");
	}

	// Possible values depending on JSF impl:
	// "org.apache.myfaces.view.facelets.compiler.UIInstructionHandler"
	// "com.sun.faces.facelets.compiler.UIInstructionHandler"
	if (!(config.getNextHandler().getClass().getName().endsWith(
		"UIInstructionHandler"))) {
	    // This occurs when an empty jsft:event tag is used... ignore
	    return;
	}

	// Create a CommandReader
	CommandReader reader = new CommandReader(config.getNextHandler().toString());

	// Read the Commands
	try {
	    commands = reader.read();
	} catch (IOException ex) {
	    throw new RuntimeException(
		    "Unable to parse Commands for event type '" + type + "'.",
		    ex);
	}
    }

    /**
     *	<p> This method is responsible for queueing up the EL that should be
     *	    invoked when the event is fired.</p>
     */
    public void apply(FaceletContext ctx, UIComponent parent) throws IOException {
	if (!ComponentHandler.isNew(parent)) {
	    // Has already been done...
	    return;
	}

	// NOTE: JSF "sometimes" executes apply() 2x... which causes listeners
	// to be dual registered. Be sure to remove them before adding them.

	FacesContext facesCtx = ctx.getFacesContext();

	// Removed because there were too many edge cases (navigating to same
	// view, view created multiple times, viewScope getting cleared, etc.)
	// Instead, we will remove each event listenter before adding it to
	// prevent duplicates.
	// Map<String, Boolean> map = getAlreadyProcessedMap(facesCtx);
	// String uid = getUID(parent);
	// if (map.containsKey(uid)) {
	//     // Has already been done...
	//     return;
	// } else {
	//     map.put(uid, true);
	// }

	// Find the commands... (normally just use the ones we have set)
	List<Command> instCmds = commands;
	if (insert != null) {
	    // We need to insert other commands...
	    String cmdId = (String) this.insert.getValueExpression(ctx, String.class).getValue(ctx);
	    if (cmdId != null) {
		// Retrieve...
		instCmds = (List<Command>) facesCtx.getAttributes().get(cmdId);
		if (instCmds == null) {
		    // No commands found, ignore...
		    instCmds = commands;
		} else {
		    // Need to make a copy to not stomp on the original
		    instCmds = new ArrayList<Command>(instCmds);
		    // Keep the existing local commands too
		    instCmds.addAll(commands);
		}
	    }
	}

	// Store the commands if an "id" has been set
	if (id != null) {
	    String cmdId = (String) this.id.getValueExpression(ctx, String.class).getValue(ctx);
	    if (cmdId != null) {
		// Store...
		facesCtx.getAttributes().put(cmdId, instCmds);
	    }
	}

	// Determine the correct parent and handle any special cases...
	Class<? extends SystemEvent> eventClass = getEventClass(ctx);
	if (eventClass == null) {
	    return;
	}

	if (InitPageEvent.class.isAssignableFrom(eventClass)) {
	    // These need special handling... these need to be invoked here
	    // (since this is the init-page time).  For now, we'll immediately
	    // execute them... perhaps in the future we'll find a good way to
	    // queue them up and invoke them at the end of the facelets tag
	    // processing. We want this *before* any UIComponent "afterCreate"
	    // events are fired.
// FIXME: Verify cases for all 3 events:
// 1) Navigate
// 2) Refresh
	    // Create the event
	    InitPageEvent event = getInitPageEvent(
		    eventClass.asSubclass(InitPageEvent.class),
		    facesCtx);

	    // Only fire if we have an event (won't have an event if it was a
	    // POST event on a GET request, for example)
	    if (event != null) {
		// Process the handlers here
		new CommandEventListener(instCmds).processEvent(event);
	    }

	    // Unset the parent, this will ensure it doesn't attempt to
	    // register this event w/ the parent (we already processed it).
	    parent = null;
	    return;
	} else if ((PreRenderViewEvent.class == eventClass)
		|| (PostConstructViewMapEvent.class == eventClass)
		|| (PreDestroyViewMapEvent.class == eventClass)) {
	    // ensure that f:event can be used anywhere on the page for
	    // these events, not just as a direct child of the viewRoot
// FIXME: Make sure these events work!  It might be too late by this
// FIXME: point... in which case we need to figure out something else.
	    parent = facesCtx.getViewRoot();
	} else if (CommandActionEvent.class == eventClass) {
	    // This needs to be registered differently because JSF
	    // ActionSource does not fire events.... so we have to make
	    // them fire events.
	    // First verify that we have an ActionSource
	    if (!(parent instanceof ActionSource)) {
		throw new IllegalArgumentException(
		    "You must put a 'command' event on an ActionSource. '"
		    + parent.getId() + "' is not an ActionSource.");
	    }

	    // Now register an ActionListener that will fire the event w/
	    // the Action Source
	    CommandActionListener listener = new CommandActionListener();
	    // Remove first, then re-add (to be safe not to add it 2x)
	    ((ActionSource) parent).removeActionListener(listener);
	    ((ActionSource) parent).addActionListener(listener);
	} else if (AjaxEvent.class == eventClass) {
	    if (!(parent instanceof ClientBehaviorHolder)) {
		// Parent component is not able to hold AjaxBehavior!
		throw new IllegalArgumentException("Component ('"
			+ parent.getClientId()
			+ "') is not able to have ajax events (not a "
			+ "ClientBehaviorHolder).");
	    }

	    // Ajax Events are fired from a Behavior attached to a UIComponent.
	    // Find the AjaxBehavior on the parent and add a listener.

	    // Loop through the event types on this component
	    boolean found = false;
	    for (Map.Entry<String, List<ClientBehavior>> entry :
		    ((ClientBehaviorHolder) parent).getClientBehaviors().entrySet()) {
		// Loop through the behaviors for this eventType
		for (ClientBehavior behave : entry.getValue()) {
		    if (behave instanceof AjaxBehavior) {
			// Add AjaxBehaviorListener to the AjaxBehavior
			AjaxBehavior ajaxBehavior = null;
			ajaxBehavior = (AjaxBehavior) behave;
			AjaxBehaviorEventListener listener = new AjaxBehaviorEventListener(entry.getKey());
			// Remove first, then re-add (to be safe not to add it 2x)
			ajaxBehavior.removeAjaxBehaviorListener(listener);
			ajaxBehavior.addAjaxBehaviorListener(listener);
			found = true;
		    } else if (behave instanceof org.primefaces.behavior.ajax.AjaxBehavior) {
			// Add AjaxBehaviorListener to the PF AjaxBehavior
			org.primefaces.behavior.ajax.AjaxBehavior pfAjaxBehave =
				(org.primefaces.behavior.ajax.AjaxBehavior) behave;
			PFAjaxBehaviorEventListener listener = new PFAjaxBehaviorEventListener(entry.getKey());
			// Remove first, then re-add (to be safe not to add it 2x)
			pfAjaxBehave.removeAjaxBehaviorListener(listener);
			pfAjaxBehave.addAjaxBehaviorListener(listener);
			found = true;
		    }
		}
	    }

	    if (!found) {
		// No Ajax behaviors found, something is wrong...
		throw new IllegalArgumentException("No Ajax Behavior for: "
			+ parent.getClientId());
	    }
	}

// FIXME: Add support for PrimeFaces FileUploadEvent.

	if (parent != null) {
// TODO: Add support for <jsft:event type="ajax" subType="keyup" />??
//       Currently all <f:ajax type="keyup|keydown|etc" execute same instCmds

	    // Associate the instCmds w/ the event
	    CommandEventListener listener = new CommandEventListener(instCmds);
	    // Remove first, then re-add (to be safe not to add it 2x)
	    parent.unsubscribeFromEvent(eventClass, listener);
	    parent.subscribeToEvent(eventClass, listener);
	}
    }

    /**
     *	<p> This method returns a unique ID for this "event" instance.  This is
     *	    needed for things such as ensuring we do not add events multiple
     *	    times for the same instance (JSF may sometimes create the tree 2x,
     *	    causing this code to get executed twice).  The id returns is a
     *	    composite of the parent <code>UIComponent</code> id + the tagId of
     *	    this event.  Since a composite component or included file may
     *	    contain an event instance, it is not enough to use the tagId.
     *	    Since the parent component may have multiple events, it is not
     *	    sufficient to use the parent id.</p>
    private String getUID(UIComponent parent) {
	return parent.getId() + this.tagId;
    }
     */

    /**
     *	<p> The <code>Map</code> returned is a mutable <code>Map</code>
     *	    containing all the EventHandler's ids that have been processed so
     *	    far for the current UIViewRoot.</p>
    private Map<String, Boolean> getAlreadyProcessedMap(FacesContext ctx) {
	// Events should be processed per page.  If we navigate to the same
	// page, we need to do the events for the "new" page. So it's not good
	// enough to see if we've processed the events for a given page, we
	// must detect if it's the same instance of the page.
	// Case 1: viewRoot == null... assume very early, don't worry about it for now
	// Case 2: viewRoot has no "key"... assume new viewRoot, increment key and assign to viewRoot (check for "null" events case?)
	// Case 3: viewRoot has a "key"... exising viewRoot, use same Map
	//
	// During "RestoreView" FacesContext.getCurrentPhase() == PhaseId.RESTORE_VIEW, the UIViewRoot (and therefor viewMap) is unreliable
	// Maybe add a PhaseListener to "move" map after RestoreView?

	// FacesContext Attributes Map
	Map<Object, Object> attMap = ctx.getAttributes();

	// Find the viewRoot key
	Integer key = (Integer) attMap.get(VIEW_ROOT_EVENT_KEY);
	if (key == null) {
	    // Not yet set... will be set when incremented
	    key = 0;
	}

	// Check the viewRoot for a key...
	Map<String, Object> viewMap = null;
	UIViewRoot root = ctx.getViewRoot();
	if (root != null) {
	    viewMap = root.getViewMap();
	    key = key + 1;
	}

	Map<String, Boolean> map = (Map<String, Boolean>)
		attMap.get(PROCESSED_EVENT_MAP_KEY);
	if (map == null) {
	    map = new HashMap<String, Boolean>();
	    attMap.put(PROCESSED_EVENT_MAP_KEY, map);
	}
	return map;
    }
     */

    /**
     *	<p> This method creates an instance of an {@link InitPageEvent}.  The
     *	    given <code>Class</code> specifies the specific type of
     *	    <code>InitPageEvent</code> to create.</p>
     */
    private InitPageEvent getInitPageEvent(Class<? extends InitPageEvent> type, FacesContext facesCtx) {
	InitPageEvent event = null;
	if (InitPostEvent.class.isAssignableFrom(type)) {
	    if (facesCtx.isPostback()) {
		// Event only for POST requests...
		// Use the UIViewRoot as the parent
		event = new InitPostEvent(facesCtx.getViewRoot());
	    }
	} else if (InitGetEvent.class.isAssignableFrom(type)) {
	    if (!facesCtx.isPostback()) {
		// Event only for GET requests...
		// Use the UIViewRoot as the parent
		event = new InitGetEvent(facesCtx.getViewRoot());
	    }
	} else {
	    // Event only for all requests...
	    // Use the UIViewRoot as the parent
	    event = new InitPageEvent(facesCtx.getViewRoot());
	}

	return event;
    }

    /**
     *	<p> This method returns the event <code>Class</code>.  Many event types
     *	    have short aliases that are recognized by this method, others may
     *	    need the fully qualified classname.  The supported types are:</p>
     *
     *	    <ul><li>afterCreate</li>
     *		<li>afterCreateView</li>
     *		<li>afterValidate</li>
     *		<li>ajax</li>
     *		<li>beforeEncode</li>
     *		<li>beforeEncodeView<li>
     *		<li>beforeValidate</li>
     *		<li>command</li>
     *		<li>initPage</li>
     *		<li>initGet</li>
     *		<li>initPost</li>
     *		<li>preRenderComponent</li>
     *		<li>javax.faces.event.PreRenderComponent</li>
     *		<li>preRenderView</li>
     *		<li>javax.faces.event.PreRenderView</li>
     *		<li>postAddToView</li>
     *		<li>javax.faces.event.PostAddToView</li>
     *		<li>preValidate</li>
     *		<li>javax.faces.event.PreValidate</li>
     *		<li>postValidate</li>
     *		<li>javax.faces.event.PostValidate</li>
     *		<li>preRemoveFromView</li>
     *		<li>javax.faces.event.PreRemoveFromViewEvent</li>
     *		<li>postRestoreState</li>
     *		<li>javax.faces.event.PostRestoreStateEvent</li>
     *		<li>postConstructViewMap</li>
     *		<li>javax.faces.event.PostConstructViewMapEvent</li>
     *		<li>preDestroyViewMap</li>
     *		<li>javax.faces.event.PreDestroyViewMapEvent</li>
     *	    </ul>
     *
     *	    <p>	The type may be <code>null</code> if the current event is
     *		defining handlers to be used by another event.</p>
     *
     *	@param	ctx	The <code>FaceletContext</code>.
     *
     *	@return	    The <code>SystemEvent</code> class associated with the
     *		    event type.
     */
    @SuppressWarnings("unchecked")
    protected Class<? extends SystemEvent> getEventClass(FaceletContext ctx) {
	Class<? extends SystemEvent> cls = null;

	// Only attempt to resolve the type if the attribute was specified...
	if (type != null) {
	    String eventType = (String) this.type.getValueExpression(ctx, String.class).getValue(ctx);
	    if (eventType == null) {
	    	final String msg = "Attribute 'type' resolved to null!";
		throw new FacesException(msg, new IllegalStateException(msg));
	    }

	    // Check the pre-defined types / aliases
	    cls = eventAliases.get(eventType);

	    if (cls == null) {
		// Not found, try reflection...
		try {
		    cls = (Class<? extends SystemEvent>) Util.loadClass(eventType, eventType);
		} catch (ClassNotFoundException ex) {
		    throw new FacesException("Invalid event type: " + eventType, ex);
		}
	    }
	}

	// Return the result...
	return cls;
    }

    /**
     *	<p> Print out the <code>Command</code>.</p>
     */
    @Override
    public String toString() {
	StringBuilder buf = new StringBuilder("");
	for (Command command : commands) {
	    buf.append(command.toString());
	}
	return buf.toString();
    }

    private static final Map<String, Class<? extends SystemEvent>> eventAliases = new HashMap<>(20);
    static {
	eventAliases.put("beforeEncode", PreRenderComponentEvent.class);
	eventAliases.put("preRenderComponent", PreRenderComponentEvent.class);
	eventAliases.put("javax.faces.event.PreRenderComponent", PreRenderComponentEvent.class);

	eventAliases.put("beforeEncodeView", PreRenderViewEvent.class);
	eventAliases.put("preRenderView", PreRenderViewEvent.class);
	eventAliases.put("javax.faces.event.PreRenderView", PreRenderViewEvent.class);

	eventAliases.put("afterCreate", PostAddToViewEvent.class);
	eventAliases.put("postAddToView", PostAddToViewEvent.class);
	eventAliases.put("javax.faces.event.PostAddToView", PostAddToViewEvent.class);

	eventAliases.put("afterCreateView", PostRestoreStateEvent.class);
	eventAliases.put("postRestoreState", PostRestoreStateEvent.class);
	eventAliases.put("javax.faces.event.PostRestoreStateEvent", PostRestoreStateEvent.class);

	eventAliases.put("beforeValidate", PreValidateEvent.class);
	eventAliases.put("preValidate", PreValidateEvent.class);
	eventAliases.put("javax.faces.event.PreValidate", PreValidateEvent.class);

	eventAliases.put("afterValidate", PostValidateEvent.class);
	eventAliases.put("postValidate", PostValidateEvent.class);
	eventAliases.put("javax.faces.event.PostValidate", PostValidateEvent.class);

	eventAliases.put("preRemoveFromView", PreRemoveFromViewEvent.class);
	eventAliases.put("javax.faces.event.PreRemoveFromViewEvent", PreRemoveFromViewEvent.class);
	eventAliases.put("postConstructViewMap", PostConstructViewMapEvent.class);
	eventAliases.put("javax.faces.event.PostConstructViewMapEvent", PostConstructViewMapEvent.class);
	eventAliases.put("preDestroyViewMap", PreDestroyViewMapEvent.class);
	eventAliases.put("javax.faces.event.PreDestroyViewMapEvent", PreDestroyViewMapEvent.class);

	// Action events... (i.e. buttons)
	eventAliases.put("command", CommandActionEvent.class);
	eventAliases.put("com.sun.jsft.event.CommandActionEvent", CommandActionEvent.class);

	// Ajax events
	eventAliases.put("ajax", AjaxEvent.class);
	eventAliases.put("com.sun.jsft.event.AjaxEvent", AjaxEvent.class);

	// Init events
	eventAliases.put("initPage", InitPageEvent.class);
	eventAliases.put("com.sun.jsft.event.InitPageEvent", InitPageEvent.class);
	eventAliases.put("initGet", InitGetEvent.class);
	eventAliases.put("com.sun.jsft.event.InitGetEvent", InitGetEvent.class);
	eventAliases.put("initPost", InitPostEvent.class);
	eventAliases.put("com.sun.jsft.event.InitPostEvent", InitPostEvent.class);

/*
  FIXME: Look at supporting these too...
	Non component, system events:
	  postConstructApplication
	  ValueChangedEvent
	  exceptionQueued
	  BehaviorEvent
	    - AjaxBehaviorEvent
*/
    }

    private final TagAttribute type;
    private final TagAttribute id;
    private final TagAttribute insert;
    private List<Command> commands = new ArrayList<Command>(5);

//    private static final String PROCESSED_EVENT_MAP_KEY = "_pemk";
//    private static final String VIEW_ROOT_EVENT_KEY = "_vrek";
}
