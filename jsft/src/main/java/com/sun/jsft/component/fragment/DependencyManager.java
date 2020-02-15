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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.faces.context.FacesContext;
import javax.faces.event.SystemEventListener;


/**
 *  <p>	To get an instance of this class, use {@link #getInstance()}.  This
 *	will check the "<code>com.sun.jsft.DEPENDENCY_MANAGER</code>"
 *	<code>web.xml</code> <code>context-param</code> to find the
 *	correct implementation to use.  If not specified, it will use the
 *	{@link DefaultDependencyManager}.  Alternatively, you can invoke
 *	{@link setDependencyManager(DependencyManager)} directly to specify the desired
 *	implementation.</p>
 */
public abstract class DependencyManager {

    /**
     *	<p> This method is responsible for executing the queued Dependencies.  It is
     *	    possible this method may be called more than once (not common), so
     *	    care should be taken to ensure this is handled appropriately.  This
     *	    method is normally executed after the page (excluding
     *	    DefferedFragments, of course) have been rendered.</p>
     */
    public abstract void start();

    /**
     *	<p> This method locates or creates the DependencyManager instance associated
     *	    with this request.</p>
     */
    public static DependencyManager getInstance() {
	// See if we already calculated the DependencyManager for this request
	FacesContext ctx = FacesContext.getCurrentInstance();
	DependencyManager dependencyManager = null;
	Map<String, Object> requestMap = null;
	if (ctx != null) {
	    requestMap = ctx.getExternalContext().getRequestMap();
	    dependencyManager = (DependencyManager) requestMap.get(DEPENDENCY_MANAGER);
	}
	if (dependencyManager == null) {
	    Map initParams = ctx.getExternalContext().getInitParameterMap();
	    String className = (String) initParams.get(IMPL_CLASS);
	    if (className != null) {
		try {
		    dependencyManager = (DependencyManager) Class.forName(className).newInstance();
		} catch (Exception ex) {
		    throw new RuntimeException(ex);
		}
	    } else {
		dependencyManager = new DefaultDependencyManager();
	    }
	    if (requestMap != null) {
		requestMap.put(DEPENDENCY_MANAGER, dependencyManager);
	    }
	}
	return dependencyManager;
    }

    /**
     *	<p> This method is provided in case the developer would like to provide
     *	    their own way to calculate and create the <code>DependencyManager</code>
     *	    implementation to use.</p>
     */
    public static void setDependencyManager(DependencyManager dependencyManager) {
	FacesContext ctx = FacesContext.getCurrentInstance();
	if (ctx != null) {
	    ctx.getExternalContext().getRequestMap().put(
		    DEPENDENCY_MANAGER, dependencyManager);
	} else {
	    throw new RuntimeException(
		"Currently only JSF is supported!  FacesContext not found.");
	}
    }

    /**
     *	<p> This method is responsible for queuing up a <code>dependency</code> to
     *	    be performed.  The given <code>newListeners</code> will be fired
     *	    according to the requested event <code>type</code>.  If the
     *	    <code>type</code> is not specified, it will default to
     *	    {@link Dependency#DEFAULT_EVENT_TYPE} indicating that the given
     *	    <code>newListeners</code> should be fired when the dependency is
     *	    satisfied.</p>
     *
     *	<p> Note: If the <code>dependency</code> is already queued, it will NOT be
     *	    performed twice.  The <code>newListeners</code> will be added to
     *	    the already-queued <code>dependency</code>.</p>
     *
     *	@param	dependency	A unique string identifying a dependency to perform. This is
     *			implementation specific to the DependencyManager
     *			implementation.
     *
     *	@param	type	Optional String identifying the event name within the
     *			dependency in which the given Listeners are associated.  If
     *			no type is given, the listeners will be fired at the
     *			end of the dependency ({@link Dependency#DEFAULT_EVENT_TYPE}).
     *
     *	@param	newListeners	The SystemEventListener to be associated with this
     *			dependency and optional type if specified.
     */
    protected void addDependency(String dependencyName, String type, SystemEventListener ... newListeners) {
// FIXME: Do I want to accept priority too??  Or perhaps that is handled in
// FIXME: the implementation-specific way dependencies are registered?  Or is priority
// FIXME: only associated with DeferredFragments?
	Dependency dependency = dependencies.get(dependencyName);
	if (dependency == null) {
	    // New Dependency, create and add...
	    dependency = new Dependency(dependencyName);
	    dependency.setListeners(type, toArrayList(newListeners));
	    dependencies.put(dependencyName, dependency);
	} else {
	    // Dependency already created, add the listeners for this type...
	    List<SystemEventListener> dependencyListeners = dependency.getListeners(type);
	    if (dependencyListeners == null) {
		dependency.setListeners(type, toArrayList(newListeners));
	    } else {
		dependencyListeners.addAll(toArrayList(newListeners));
	    }
	}
    }

    /**
     *	<p> This method is responsible for parsing the given dependency String
     *	    according to the specific <code>DependencyManager</code> that is
     *	    being used.  It then invokes {@link #addDependency(
     *	    String dependency, String type, SystemEventListener ..
     *	    newListeners)} for each of the derived dependencies and returns a
     *	    count of them.</p>
     */
    public abstract int addDependencies(String dependencyString, SystemEventListener ... newListeners);

    /**
     *	<p> This method returns the <code>List&lt;Dependency&gt;</code>.</p>
     */
    public Collection<Dependency> getDependencies() {
	return dependencies.values();
    }

    /**
     *	<p> Convert an array of <code>T</code> to an
     *	    <code>ArrayList&lt;T&gt;</code>.</p>
     */
    private <T> List<T> toArrayList(T arr[]) {
	ArrayList<T> list = new ArrayList<T>(arr.length);
	for (T item : arr) {
	    list.add(item);
	}
	return list;
    }


    /**
     *	<p> This <code>Map</code> will hold all the {@link Dependencies}.</p>
     */
    private Map<String, Dependency> dependencies = new HashMap<String, Dependency>(2);

    /**
     *	<p> The request scope key for holding the DEPENDENCY_MANAGER instance to
     *	    make it easily obtained.</p>
     */
    private static final String	DEPENDENCY_MANAGER	= "_jsftTM";

    /**
     *	<p> The web.xml <code>context-param</code> for declaring the
     *	    implementation of this class to use.</p>
     */
    public static final String	IMPL_CLASS	= "com.sun.jsft.DEPENDENCY_MANAGER";
}
