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

import jakarta.faces.event.SystemEvent;
import jakarta.faces.event.SystemEventListener;
import java.util.List;
import java.util.StringTokenizer;

/**
 * <p> This is the default {@link DependencyManager} implementation.</p>
 */
public class DefaultDependencyManager extends DependencyManager {
    /**
     * <p> Default constructor.</p>
     */
    protected DefaultDependencyManager() {
        super();
    }

    /**
     * <p> This method is responsible for parsing the given dependency String. In this implementation, it expects
     *     dependencies to be separated by semi-colons (;), and they can be further qualified by providing an
     *     event type after a colon (:).  For example:
     *
     *     <blockquote>
     *         <code>
     *             dependency1:anEventType;dep2:dependencyComplete
     *         </code>
     *     </blockquote></p>
     *
     *     It then invokes {@link DependencyManager#addDependency(String, String, SystemEventListener... )} for each
     *     of the derived dependencies and returns a count of them.</p>
     */
    public int addDependencies(final String depString, final SystemEventListener... newListeners) {
        final StringTokenizer tok = new StringTokenizer(depString, ";");
        int dependencyCnt = 0;
        while (tok.hasMoreTokens()) {
            final String tokStr = tok.nextToken().trim();

            // Check to see if we have dependency:listenerType
            int idx = tokStr.indexOf(':');
            final String type = (idx == -1) ? null : tokStr.substring(idx + 1);
            final String name = (idx == -1) ? tokStr : tokStr.substring(0, idx);

            // Register the Dependency...
            addDependency(name, type, newListeners);

            // Count the dependencies we depend on...
            dependencyCnt++;
        }
        // Return the # of dependencies so the caller knows how many were registered.
        return dependencyCnt;
    }

    /**
     * <p> This method is responsible for executing the queued Dependencies.  It is possible this method may be
     *     called more than once (not common), so care should be taken to ensure this is handled appropriately. This
     *     method is normally executed after the page (excluding DefferedFragments, of course) have been rendered.</p>
     */
    public void start() {
        // Loop through the dependencies and execute them...
        for (Dependency dependency : getDependencies()) {
// FIXME: This implementation is a no-op, it just loops through the dependencies and fires the DEPENDENCY_COMPLETE event.
// FIXME: A real implementation would aggregate & dispatch the dependencies and register listeners with the "backend dispatcher" which would fire the DEPENDENCY_COMPLETE event.
// FIXME: This method should not block.
            final SystemEvent event = new DependencyEvent(dependency);
            final List<SystemEventListener> listeners = dependency.getListeners(DependencyEvent.DEPENDENCY_COMPLETE);
            if (listeners != null) {
                for (SystemEventListener listener : listeners) {
                    listener.processEvent(event);
                }
            }
        }
    }
}
