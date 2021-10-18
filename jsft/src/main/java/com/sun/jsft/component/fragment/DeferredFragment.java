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

import jakarta.faces.component.UIComponent;
import jakarta.faces.component.UIComponentBase;
import jakarta.faces.component.UIOutput;
import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.ResponseWriter;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ComponentSystemEvent;
import jakarta.faces.event.ComponentSystemEventListener;
import jakarta.faces.event.PostAddToViewEvent;
import jakarta.faces.event.SystemEvent;
import jakarta.faces.event.SystemEventListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeferredFragment extends UIComponentBase {

    /**
     * <p> Default constructor.</p>
     */
    public DeferredFragment() {
        subscribeToEvent(
            PostAddToViewEvent.class,
            new DeferredFragment.AfterCreateListener());
    }

    public String getFamily() {
        return FAMILY;
    }

    public boolean getRendersChildren() {
        return true;
    }

    public void encodeBegin(FacesContext context) throws IOException {
        context.getResponseWriter().write("<span id=\"" + getClientId(context)
                + "\" style=\"display:none;\">");
    }

    public void encodeEnd(FacesContext ctx) throws IOException {
        // Close span
        ResponseWriter respWriter = ctx.getResponseWriter();
        respWriter.write("</span>");

        // Write JS
        respWriter.write("<script type=\"text/javascript\">var on=document."
                + "getElementById('" + getFacetKey(ctx) + "');var nn=document."
                + "getElementById('" + getClientId() + "');on.parentNode."
                + "replaceChild(nn,on);nn.style.display='inline';</script>");
    }

    /**
     * <p> This method returns <code>true</code> when all dependencies this deferred
     *     fragment depends on are complete.  This method is not intended to
     *     be used to poll this dependency for completion, you should instead
     *     register for the ready event that it fires.</p>
    private boolean isReady() {
        return (dependencyCount == 0);
    }
     */

    /**
     * <p> This method gets the id of the "place-holder" component for this <code>DeferredFragment</code>.</p>
     */
    public String getPlaceHolderId() {
        return placeHolderId;
    }

    /**
     * <p> This method sets the id of the "place-holder" component for this <code>DeferredFragment</code>.</p>
     */
    public void setPlaceHolderId(String id) {
        placeHolderId = id;
    }

    /**
     * <p> This method returns the number of dependencies this DeferredFragment is waiting for.</p>
     */
    public int getDependencyCount() {
        return dependencyCount;
    }

    /**
     * <p> This method sets the number of dependencies this <code>DeferredFragment</code> must wait for.</p>
     */
    public void setDependencyCount(int count) {
        dependencyCount = count;
    }

    /**
     * <p> This method registers the given <code>ComponentSystemEventListener</code> that should be notified
     *     when this <code>DeferredFragment</code> is ready to be rendered.</p>
     */
    public void addReadyListener(ComponentSystemEventListener listener) {
        listeners.add(listener);
    }

    /**
     * <p> This method is responsible for firing the {@link FragmentReadyEvent} to signal to listeners that the
     *     {@link Dependency}s needed by this <code>DeferredFragment</code> have completed and it is now ready to
     *     be processed.</p>
     */
    protected void fireFragmentReadyEvent() {
        ComponentSystemEvent event = new FragmentReadyEvent(this);
//System.out.println("listeners" + listeners);
        for (ComponentSystemEventListener listener : listeners) {
            listener.processEvent(event);
        }
    }


    /**
     * <p> Listener used to handle DependencyEvents.</p>
     */
    public static class DeferredFragmentDependencyListener implements SystemEventListener {

        /**
         * <p> Default Constructor.</p>
         */
        public DeferredFragmentDependencyListener(DeferredFragment df) {
            super();
            this.df = df;
        }

        /**
         * <p> The event passed in will be a {@link DependencyEvent}.</p>
         */
        public void processEvent(SystemEvent event) throws AbortProcessingException {
//System.out.println("DeferredFragmentDependencyListener.processEvent()!");
            //Dependency dependency = (Dependency) event.getSource();
            //String eventType = ((DependencyEvent) event).getType();
            int count = 0;
            synchronized (df) {
                // Synch to ensure we don't change it during this time.
                count = df.getDependencyCount() - 1;
                df.setDependencyCount(count);
            }
            if (count == 0) {
                // We're done!
                df.fireFragmentReadyEvent();
            }
        }

        public boolean isListenerForSource(Object source) {
            // We only dispatch this correctly... this method is not needed.
            return true;
        }

        // A reference to the DeferredFragment
        private DeferredFragment df = null;
    }

    /**
     * <p> This method returns the key under which this component should be stored, when moved to the
     *     <code>UIViewRoot</code> as a facet. This key is also used when rendering the temporary markup
     *     (&lt;span&gt; tag) id attribute -- this allows JS to locate it and replace it with the contents
     *     of the facet.</p>
     *
     * @param ctx The <code>FacesContext</code>.
     *
     * @return The facet key.
     */
    public String getFacetKey(FacesContext ctx) {
        if (facetKey == null) {
            facetKey = "jsft_" + getClientId(ctx);
        }
        return facetKey;
    }

    /**
     * <p> Listener used to relocate the children to a facet on the UIViewRoot.</p>
     */
    public static class AfterCreateListener implements ComponentSystemEventListener {
        AfterCreateListener() {
        }

        /**
         * <p> This method is responsible for setting up this <code>DeferredFragment</code>.  This includes the
         *     following steps:</p>
         *
         * <ul><li>Put a "place-holder" component at the location of the
         *         fragment so it can be swapped out by the client at a later time.</li>
         *     <li>Move this component to a facet in the UIViewRoot.</li>
         *     <li>Register any dependencies that need to be executed, add a listener
         *         for each dependency or the specified event within the dependency.</li>
         *     <li>Ensure a FragmentRenderer component exists at the end of the page.</li>
         *     </ul>
         */
        public void processEvent(ComponentSystemEvent event) throws AbortProcessingException {
            // Ensure we only do this once... NOTE: I tried to unsubscribe from
            // the event, but ran into a ConcurrentModificationException... the
            // list of listeners is probably still being looped through while I
            // am attempting to remove this Listener.  So I'll do this instead:
            if (done) {
                return;
            }
            done = true;

            // Get the component
            DeferredFragment comp = (DeferredFragment) event.getComponent();

            // Get the UIViewRoot Facet Map
            FacesContext ctx = FacesContext.getCurrentInstance();
            UIViewRoot viewRoot = ctx.getViewRoot();
            Map<String, UIComponent> facetMap = viewRoot.getFacets();

            // Create a place holder...
            String key = comp.getFacetKey(ctx);
            UIComponent placeHolder = new UIOutput();
            placeHolder.getAttributes().put(
                    "value", "<span id='" + key + "'></span>");

            // Swap comp with the placeHolder...
            List<UIComponent> peers = comp.getParent().getChildren();
            int index = peers.indexOf(comp);
            peers.set(index, placeHolder);
            comp.setPlaceHolderId(placeHolder.getClientId(ctx));

            // Move this component to the FacetMap
            facetMap.put(key, comp);

            // Register dependency(s)
            String dependency = (String) comp.getAttributes().get("dependency");
            int dependencyCnt = 0;
            if (dependency != null) {
                dependencyCnt = DependencyManager.getInstance().addDependencies(
                    dependency, new DeferredFragmentDependencyListener(comp));
            }

            // Store the dependency count...
            comp.setDependencyCount(dependencyCnt);

            // Ensure we have a FragmentRenderer component...
            FragmentRenderer fr = FragmentRenderer.getInstance(viewRoot);

            // Increment fragment count on FragmentRenderer component...
            fr.addDeferredFragment(comp);
            comp.addReadyListener(fr);
        }

        private boolean done = false;
    }

    /**
     * <p> The component family.</p>
     */
    public static final String FAMILY        =   DeferredFragment.class.getName();


    /**
     * <p> The number of dependencies that need to be complete before this <code>DeferredFragment</code>
     *     can be rendered.  It is initialized to a postiive value (1) so that {@link #isReady()} will return
     *     <code>false</code> -- important since the dependencies have not yet been counted.</p>
     */
    private int dependencyCount = 1;

    /**
     * <p> The id of the placeholder for this component so we can find it later.</p>
     */
    private String placeHolderId = "";

    /**
     * <p> The facet id for this component, also used as the placeholder HTML id (to avoid a naming conflict).</p>
     */
    private transient String facetKey = null;

    /**
     * <p> This <code>List</code> will hold the list of listeners interested in being notified with this
     *     <code>DeferredFragment</code> is ready to be rendered.</p>
     */
    private List<ComponentSystemEventListener> listeners =
            new ArrayList<ComponentSystemEventListener>(2);
}
