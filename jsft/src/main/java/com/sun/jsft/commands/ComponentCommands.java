/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2011 Ken Paulsen
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License"). You
 * may not use this file except in compliance with the License. You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt. See the License for the specific
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
 * its licensees as provided above. However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 *  UtilCommands.java
 *
 *  Created March 29, 2011
 *  @author Ken Paulsen kenapaulsen@gmail.com
 */
package com.sun.jsft.commands;

import com.sun.jsft.util.ELUtil;
import jakarta.el.ValueExpression;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 *  <p> This class contains methods that perform common utility-type functionality.</p>
 *
 *  @author  Ken Paulsen (kenapaulsen@gmail.com)
 */
@Slf4j
@Named("jsftComp")
@ApplicationScoped
public class ComponentCommands {
    private static ComponentCommands instance = new ComponentCommands();

    /**
     * <p> When these utilities are used via code, this method can be used to obtain an instance.</p>
     */
    public static ComponentCommands getInstance() {
        return instance;
    }

    /**
     * <p> For testing purposes, this setInstance() may be used to set a "mock" instance.</p>
     */
    public static void setInstance(final ComponentCommands newinst) {
        instance = newinst;
    }

    /**
     * <p> This inserts <code>newComp</code> before the given <code>existingComp</code> component. If
     *     <code>existingComp</code> is not found, <code>newComp</code> will be added <em>at the beginning</em> of
     *     the component list.</p>
     *
     * @param existingComp  The reference UIComponent relative to which the new component will be added.
     * @param newComp       The new component to add.
     */
    public void insertUIComponentBefore(final UIComponent existingComp, final UIComponent newComp) {
        insertUIComponent(true, existingComp, newComp);
    }

    /**
     * <p> This inserts <code>newComp</code> after the given <code>existingComp</code> component. If
     *     <code>existingComp</code> is not found, <code>newComp</code> will be added <em>at the beginning</em> of
     *     the component list.</p>
     *
     * @param existingComp  The reference UIComponent relative to which the new component will be added.
     * @param newComp       The new component to add.
     */
    public void insertUIComponentAfter(final UIComponent existingComp, final UIComponent newComp) {
        insertUIComponent(false, existingComp, newComp);
    }

    /**
     * <p> This method provides a way to insert <code>newComp</code> before (<code>before == true</code>) or after
     *     (<code>before == false</code>) the given <code>existingComp</code> component. If <code>existingComp</code>
     *     is not found, <code>newComp</code> will be added <em>at the beginning</em> of the component list
     *     (regardless of the <code>before</code> flag).</p>
     *
     * @param existingComp  The reference UIComponent relative to which the new component will be added.
     * @param newComp       The new component to add.
     */
    public void insertUIComponent(final boolean before, final UIComponent existingComp, final UIComponent newComp) {
        if (existingComp == null) {
            throw new IllegalArgumentException("You must provide a non-null 'existingComp' component.");
        }
        if (newComp == null) {
            // Nothing to insert...
            return;
        }
        // NOTE: JSF Doesn't provide a way to "insert"... we have to modify
        // NOTE: current list to make room then add the new element in the
        // NOTE: correct spot using set(index, comp).

        // Find the parent and its children
        final UIComponent parent = existingComp.getParent();
        final List<UIComponent> children = parent.getChildren();

        // Store the "inView" flag and change it to false temporarily (in
        // an attempt to avoid our set() calls from triggering events)
        // Manfred suggested not doing this, instead I am skipping repeated
        // event calls to PostAddToView
// FIXME: Try removing this for Mojarra... not having luck w/o doing this in MyFaces
final boolean inview = parent.isInView();
parent.setInView(false);

        // Add component to the end to increase the component list size
        //
        // Note: allow postAddToView to be called here b/c no remove event
        // will be called at this time (vs. the final set(index, newComp)
        // where it might)
        children.add(newComp);

        // Create a temp component as a placeholder (otherwise the comp list
        // will shrink when set() is called)
        UIComponent tempComp = createComponent(null, "jakarta.faces.HtmlOutputText", null);

        // Get the starting index (size-1)
        int currIdx = children.size()-1;

        // Loop through children backwards, shifting each making room for insert
        UIComponent currComp;
        String insertId = existingComp.getId();
        while (currIdx != 0) {
            // Remove component w/o shrinking list, returns comp to shift
            currComp = children.set(currIdx-1, tempComp);

            // See if this is the place to stop... inserting "after" case
            if (!before && insertId.equals(currComp.getId())) {
                // Undo set
                children.set(currIdx-1, currComp);

                // stop...
                break;
            }

            // Shift-right 1 (whatever we displace is now our 'tempComp')
            tempComp = children.set(currIdx, currComp);

            // Keep shifting...
            currIdx--;

            // See if this is the place to stop... inserting "before" case
            if (before && insertId.equals(currComp.getId())) {
                // Found it... stop
                break;
            }
        }

        // Insert newComp
        if (newComp.getParent() != null) {
            // Set it to tempComp so it will be null, we need to re-add
            // w/ setInView restored
            children.set(currIdx, tempComp);
        }

        // Restore the "inView" flag
// FIXME: Try removing this for Mojarra... not having luck w/o doing this in MyFaces
parent.setInView(inview);

        // Add the component in the correct location...
        children.set(currIdx, newComp);

        // FIXME: This should not need to be done... MyFaces 2.0.9 retained
        // the old parent id in its clientId, this ensures this is cleaned up.
        myfaces209IdHack(newComp);
    }

    /**
     * <p> This handler adds the given <code>newComp UIComponent</code> tree to the given
     *     <code>parent UIComponent</code>.</p>
     *
     * @param parent    The parent <code>UIComponent</code>.
     * @param newComp   The new component to add to the parent.
     */
    public void addUIComponent(final UIComponent parent, final UIComponent newComp) {
        if (parent == null) {
            throw new IllegalArgumentException("You must provide a non-null 'parent' component.");
        }
        if (newComp == null) {
            throw new IllegalArgumentException("You must provide a non-null 'newComp' component.");
        }
        // Add the new component
        parent.getChildren().add(newComp);
        // FIXME: This should not need to be done... MyFaces 2.0.9 retained
        // the old parent id in its clientId, this ensures this is cleaned up.
        myfaces209IdHack(newComp);
    }

    /**
     * <p> This handler replaces the given <code>old UIComponent</code> in the <code>UIComponent</code> tree with the
     *     given <code>newComp UIComponent</code>. If <code>newComp</code> is not specified or is <code>null</code>,
     *     the old <code>UIComponent</code> will simply be removed.</p>
     *
     * @param old       The component to replace.
     * @param newComp   The replacement component.
     */
    public void replaceUIComponent(final UIComponent old, final UIComponent newComp) {
        // Verify that we have the old component...
        if (old == null) {
            throw new IllegalArgumentException("You must provide a non-null 'old' component.");
        }
        // Get the parent
        final UIComponent parent = old.getParent();
        if (parent == null) {
            // Not part of a child list... nothing to do.
            return;
        }
        // Get the child UIComponent list...
        final List<UIComponent> list = parent.getChildren();
        if (newComp == null) {
            // Nothing to replace it with, just do a remove...
            list.remove(old);
        } else {
            // Find the index to put the new UIComponent in the right place
            final int index = list.indexOf(old);
            if (index >= 0) {
                list.set(index, newComp);
                myfaces209IdHack(newComp);
            }
        }
    }

    /**
     * <p> This was added because MyFaces version 2.0.9 does not reset the clientId when a component is added to a
     *     new parent. This causes it to output its old client id, which contains references to the previous naming
     *     container in which it was a child.</p>
     */
    private void myfaces209IdHack(final UIComponent comp) {
        // FIXME: This should not need to be done... MyFaces 2.0.9 retained
        // the old parent id in its clientId, this ensures this is cleaned up.
        comp.setId(comp.getId());
        for (UIComponent kid : comp.getChildren()) {
            myfaces209IdHack(kid);
        }
    }

    /**
     * <p> This handler creates a <code>UIComponent</code>. It requires the <code>componentType</code>. Optionally
     *     you can provide the <code>parent</code> <code>UIComponent</code> and <code>id</code>. The parent, if
     *     supplied will be used to contain the newly created component. Returns the new <code>UIComponent</code>.</p>
     *
     * @param id            The 'id' to assign to the new component.
     * @param componentType The Component type to create.
     * @param parent        The UIComponent parent.
     *
     * @return The newly created <code>UIComponent</code>.
     */
    public UIComponent createComponent(final String id, final String componentType, final UIComponent parent) {
        // Create the component...
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final UIComponent comp = ctx.getApplication().createComponent(componentType);
        log.warn("Unable to find UIComponent for: '{}'.", componentType);
        if ((id != null) && !id.trim().equals("")) {
            comp.setId(id);
        }
        if (parent != null) {
            parent.getChildren().add(comp);
        }
        // Return the result...
        return comp;
    }

    /**
     * <p> This handler finds the requested <code>UIComponent</code> by <code>clientId</code>. It takes
     *     <code>clientId</code> as an input parameter, and returns <code>component</code> as an output parameter.</p>
     *
     * @param clientId  The JSF clientId for the component to find.
     *
     * @return The component found, or <code>null</code>.
     */
    public UIComponent getUIComponent(final String clientId) {
        return FacesContext.getCurrentInstance().getViewRoot().findComponent(clientId);
    }

    /**
     * <p> Return a child with the specified component id (or facetName) from the specified component. If not found,
     *     return <code>null</code>. <code>facetName</code> or <code>id</code> may be null to avoid searching the
     *     facet Map or the <code>parent</code>'s children.</p>
     *
     * <p> This method does not recurse.</p>
     *
     * @param parent    <code>UIComponent</code> to be searched.
     * @param id        id to search for (or null to not search).
     * @param facetName Facet name to search for (or null to not search).
     *
     * @return The child <code>UIComponent</code> if it exists, null otherwise.
     */
    public UIComponent getChild(final UIComponent parent, final String id, final String facetName) {
        // Sanity Check
        if (parent == null) {
            return null;
        }
        // First search for facet
        if (facetName != null) {
            final UIComponent child = parent.getFacets().get(facetName);
            if (child != null) {
                return child;
            }
        }
        // Search for component by id
        if (id != null) {
            for (final UIComponent child : parent.getChildren()) {
                if (id.equals(child.getId())) {
                    return child;
                }
            }
        }
        // Not found, return null
        return null;
    }

    /**
     * <p> Searches for a <coode>UIComponent</code> with the specified component id, starting from the
     *     <code>UIViewRoot</code>. It will search all children (depth first) for the 1st matching id. If not found,
     *     return <code>null</code>.</p>
     *
     * @param id    id to search for (or null to not search).
     *
     * @return The child <code>UIComponent</code> if it exists, null otherwise.
     */
    public UIComponent findUIComponent(final String id) {
        return findUIComponent(FacesContext.getCurrentInstance().getViewRoot(), id);
    }

    /**
     * <p> Searches for a <coode>UIComponent</code> with the specified component id, starting from the specified
     *     component. It will search all children (depth first) for the 1st matching id. If not found, return
     *     <code>null</code>.</p>
     *
     * @param comp  <code>UIComponent</code> to be searched.
     * @param id    id to search for (or null to not search).
     *
     * @return The child <code>UIComponent</code> if it exists, null otherwise.
     */
    public UIComponent findUIComponent(final UIComponent comp, final String id) {
        if (comp == null) {
            return null;
        }

        // The component
        UIComponent result = null;

        // See if this is it...
        String compId = comp.getId();
        if ((compId != null) && compId.equals(id)) {
            // Found it...
            result = comp;
        } else {
            // Loop through the children...
            Iterator<UIComponent> it = comp.getFacetsAndChildren();
            while ((result == null) && it.hasNext()) {
                result = findUIComponent(it.next(), id);
            }
        }
        return result;
    }

    /**
     * <p> This handler will print out the structure of a <code>UIComponent</code> tree from the given UIComponent.</p>
     */
    public String dumpUIComponentTree(final UIComponent comp) {
// FIXME: Add flag to dump attributes also, perhaps facets should be optional as well?
        // Find the root UIComponent to use...
        final UIComponent compToUse = (comp == null) ? FacesContext.getCurrentInstance().getViewRoot() : comp;
        if (compToUse == null) {
            throw new IllegalArgumentException("Unable to determine UIComponent to dump!");
        }
        // Create the buffer and populate it...
        return dumpTree(comp, new StringBuilder("UIComponent Tree:\n"), "    ").toString();
    }

    /**
     * <p> This method replaces all the children (recursively) of the given <code>UIComponenent</code> which are of
     *     type "*facelets.compiler.UIInstructions" with a component of <code>ComponentType</code>
     *     "jakarta.faces.HtmlOutputText". This is because <code>UIInstructions</code> components are transient
     *     (and additinonally don't work correctly if set to !transient). This causes them to be lost on POST requests
     *     back to the page. Due to the dynamic nature of our "mod" components, they cannot be restored correctly.</p>
     *
     * <p> This is related to this issue: https://java.net/jira/browse/JAVASERVERFACES-3332.</p>
     *
     * @param comp      The component to start from.
     * @param replace   <code>true</code> to replace each <code>UIInstructions</code> with an output text.
     *                  <code>false</code> to remove each <code>UIInstructions</code> from the tree.
     */
    public void fixUIInstructions(final UIComponent comp, final boolean replace) {
        UIComponent kid, newComp;
        FacesContext ctx = FacesContext.getCurrentInstance();
        ELUtil elutil = ELUtil.getInstance();

        // HACK due to https://java.net/jira/browse/JAVASERVERFACES-3333:
        // Replace all UIInstructions components with HtmlOutputText
        List<UIComponent> kids = comp.getChildren();
        int numKids = kids.size();
        // Note: can't use for (x : List) -- concurrent modification
        for (int idx = 0; idx<numKids; idx++) {
            // Get the current child...
            kid = kids.get(idx);
            // Known possible values to search for:
            //  "org.apache.myfaces.view.facelets.compiler.UIInstructions"
            //  "com.sun.faces.facelets.compiler.UIInstructions"
            if (kid.getClass().getName().endsWith("facelets.compiler.UIInstructions")) {
                // Perform Hack...
                if (replace) {
                    newComp = createComponent(null, "jakarta.faces.HtmlOutputText", null);
                    // Enable ValueExpresssions...
                    newComp.setValueExpression("value",
                            elutil.getValueExpression(ctx, kid.toString()));
                    newComp.getAttributes().putAll(kid.getAttributes());
                    newComp.getAttributes().put("escape", false);
                    // Swap UIInstructions out w/ new UIOutput
                    replaceUIComponent(kid, newComp);
                } else {
                    // Just remove it...
                    kid.getParent().getChildren().remove(kid);

                    // We have one less kid now...
                    numKids--;
                }
            } else {
                // Recurse...
                fixUIInstructions(kid, replace);
            }
        }
    }

    public ValueExpression createValueExpression(final String el) {
        return ELUtil.getInstance().getValueExpression(FacesContext.getCurrentInstance(), el);
    }

    /**
     * <p> This method recurses through the <code>UIComponent</code> tree to generate a String representation of its
     *     structure.</p>
     */
    private StringBuilder dumpTree(final UIComponent comp, final StringBuilder buf, final String indent) {
        // First add the current UIComponent
        buf.append(indent)
            .append(comp.getId()).append(" (").append(comp.getClass().getName())
            .append(") = (").append(comp.getAttributes().get("value")).append(")\n");

        // Children...
        if (comp.getChildren().size() > 0) {
            buf.append(indent).append("  Children:\n");
            for (final UIComponent child : comp.getChildren()) {
                dumpTree(child, buf, indent + "    ");
            }
        }

        // Facets...
        for (Map.Entry<String, UIComponent> entry : comp.getFacets().entrySet()) {
            buf.append(indent).append("  Facet (").append(entry.getKey()).append("):\n");
            dumpTree(entry.getValue(), buf, indent + "    ");
        }
        return buf;
    }
}
