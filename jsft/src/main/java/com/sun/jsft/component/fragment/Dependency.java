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

package com.sun.jsft.component.fragment;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import javax.faces.event.SystemEventListener;


/**
 *  <p>	This class holds the representation of a single dependency.</p>
 */
public class Dependency {

    /**
     *	<p> Default constructor.</p>
     */
    public Dependency() {
	super();
    }

    /**
     *	<p> Constructor w/ <code>name</code>.</p>
     */
    public Dependency(String name) {
	this();
	this.name = name;
    }


    /**
     *	<p> The name used to identify the dependency.  In some instances, the name
     *	    may define the dependency (i.e. EL).</p>
     */
    public String getName() {
	return name;
    }

    /**
     *
     */
    public void setName(String name) {
	this.name = name;
    }

    /**
     *
     */
    public List<SystemEventListener> getListeners(String type) {
	if (type == null) {
	    type = DEFAULT_EVENT_TYPE;
	}
	return listenersByType.get(type);
    }

    /**
     *
     */
    public void setListeners(String type, List<SystemEventListener> listeners) {
	if (type == null) {
	    type = DEFAULT_EVENT_TYPE;
	}
	listenersByType.put(type, listeners);
    }


    // The identifier for this Dependency
    private String name = "";

    // Map of List to store the events by type
    private Map<String, List<SystemEventListener>> listenersByType =
	    new HashMap<String, List<SystemEventListener>>(2);

    /**
     *	<p> The default event type.</p>
     */
    public static final String DEFAULT_EVENT_TYPE   = DependencyEvent.DEPENDENCY_COMPLETE;
}
