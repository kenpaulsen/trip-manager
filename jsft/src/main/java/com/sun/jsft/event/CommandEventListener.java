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

package com.sun.jsft.event;

import com.sun.jsft.commands.JSFTCommands;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ComponentSystemEvent;
import jakarta.faces.event.ComponentSystemEventListener;
import jakarta.faces.event.PostAddToViewEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;

/**
 * <p> This class handles the dispatching of events to commands. Currently Commands delegate execution to EL. In the
 *     future, other Command types may be supported.</p>
 *
 *  Created  March 29, 2011
 *  @author  Ken Paulsen (kenapaulsen@gmail.com)
 */
@EqualsAndHashCode(callSuper = true)
public class CommandEventListener extends Command implements ComponentSystemEventListener {
    private static final long serialVersionUID = 6945415935164238929L;

    /**
     * <p> Default constructor needed for serialization.</p>
     */
    public CommandEventListener() {
        super();
    }

    /**
     * <p> Primary constructor used.  It is neeeded in order to supply a list
     *     of commands.</p>
     */
    public CommandEventListener(List<Command> commands) {
        super(commands, null);
    }

    /**
     * <p> This method is responsible for dispatching the event to the various
     *     EL expressions that are listening to this event.  It also stores
     *     the Event object in request scope under the key "theEvent" so that
     *     it can be accessed easily via EL.  For example:
     *     <code>util.println(theEvent);</code></p>
     */
    @SuppressWarnings("unchecked")
    public void processEvent(ComponentSystemEvent event) throws AbortProcessingException {
        final FacesContext ctx = event.getFacesContext();
        if (JSFTCommands.isComplete(ctx)) {
            // If we redirect, processing for that same phase continues... we don't want that, just stop.
            return;
        }
        // Get the request map...
        final Map<String, Object> reqMap = ctx.getExternalContext().getRequestMap();

        // Need to ensure we don't fire the event too many times...
        if ((event instanceof PostAddToViewEvent) || (event instanceof InitPageEvent)) {
            // PostAddToView gets fired too many times b/c the impl may add it
            // multiple times!  Only handle the 1st time...
            Map<Integer, Integer> eventMap = (Map<Integer, Integer>) reqMap.get("jsftDupEvnts");
            if (eventMap == null) {
                eventMap = new HashMap<>();
                reqMap.put("jsftDupEvnts", eventMap);
            }
            // Hash based on source object...
            int code = (event instanceof InitPageEvent) ? event.hashCode() : event.getSource().hashCode();
// FIXME: Need to revisit code where I create the event and make sure I don't create it multiple times.  If I don't
// FIXME: already have a hashCode() impl on the event, I may have to create one so I can use it as the key.
            // Separate name space for each event type...
            code += event.getClass().getName().hashCode();
            Integer count = eventMap.get(code);
            if (count == null) {
                count = 1;
                eventMap.put(code, count);
            } else {
                eventMap.put(code, ++count);
                // Already processed once, don't do it again...
                return;
            }
        }

        // Store the event under the key "theEvent" in case we want to access
        // it for some reason.
        reqMap.put("theEvent", event);

        // Execute the child commands
        invoke();
    }

    /**
     * <p> This is the method responsible for performing the action.  It is
     *     also responsible for invoking any of its child commands.</p>
     */
    public Object invoke() throws AbortProcessingException {
        // Invoke the child commands...
        invokeChildCommands();
        return null;
    }
}
