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

/*
 *  UtilCommands.java
 *
 *  Created  April 2, 2011
 *  @author  Ken Paulsen (kenapaulsen@gmail.com)
 */
package com.sun.jsft.commands;

import com.sun.jsft.event.Command;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.ResponseWriter;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 *  <p> This class contains methods that perform common utility-type
 *      functionality.</p>
 *
 *  @author  Ken Paulsen (kenapaulsen@gmail.com)
 */
@Named("jsft")
@ApplicationScoped
public class JSFTCommands {
    /**
     * <p> This is application scoped, so it is not safe to change.  Use caution.</p>
     */
    private final long nanoStartTime = System.nanoTime();

    @PostConstruct
    public void init() {
        // Executes once and only once for an ApplicationScoped bean.
    }

    /**
     * <p> This command conditionally executes its child commands.</p>
     */
    public void ifCommand(final boolean condition) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (isComplete(ctx)) {
            return;
        }
        final Command command = (Command) ctx.getExternalContext().getRequestMap().get(Command.COMMAND_KEY);
        if (condition) {
            command.invokeChildCommands();
        } else {
            final Command elseCommand = command.getElseCommand();
            if (elseCommand != null) {
                elseCommand.invoke();
            }
        }
    }

    public void foreach(final String var, final Object[] list) {
        foreach(var, Arrays.asList(list));
    }

    /**
     * <p> This command iterates over the given List and sets given
     */
    public void foreach(final String var, final Iterable<?> list) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (isComplete(ctx)) {
            return;
        }
        // Get the Request Map
        final Map<String, Object> reqMap = ctx.getExternalContext().getRequestMap();

        // Get the Current Command...
        final Command command = (Command) reqMap.get(Command.COMMAND_KEY);

        // Iterate over each item in the List
        if (list != null) {
            for (Object item : list) {
                // Set the item in the request scope under the given key
                reqMap.put(var, item);

                // Invoke all the child commands
                final List<Command> childCommands = command.getChildCommands();
                if (childCommands != null) {
                    for (Command childCommand : childCommands) {
                        childCommand.invoke();
                    }
                }
            }
        }
    }

    /**
     * If a commands for an event are declared, this command can retrieve and execute them. For example:
     * <pre>
     *     <jsft:event id="foo">
     *         println("hello world");
     *     </jsft:event>
     *     <h:outputText value="#{jsft.event('foo')}" />
     * </pre>
     * This is useful for invoking commands where no event is available to trigger it properly, but EL may be evaluated.
     *
     * @param commandId The id of the command to invoke.
     */
    @SuppressWarnings("unchecked")
    public void event(final String commandId) {
        final FacesContext facesCtx = FacesContext.getCurrentInstance();
        final Object value = facesCtx.getAttributes().get(commandId);
        if (value instanceof List) {
            for (final Command cmd : (List<Command>) facesCtx.getAttributes().get(commandId)) {
                cmd.invoke();
            }
        }
    }

    /**
     * <p> This command sets a requestScope attribute with the given
     *     <code>key</code> and <code>value</code>.</p>
     */
    public void setAttribute(final String key, final Object value) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (!isComplete(ctx)) {
            ctx.getExternalContext().getRequestMap().put(key, value);
        }
    }

    /**
     * <p> This command writes output using <code>System.out.println</code>.
     *     It requires <code>value</code> to be supplied.</p>
     */
    public void println(final String value) {
        System.out.println(value);
    }

    /**
     * <p> This command writes using
     *     <code>FacesContext.getResponseWriter()</code>.</p>
     *
     *        @param        text        The text to write.
     */
    public void write(final String text) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (isComplete(ctx)) {
            return;
        }
        final ResponseWriter writer = ctx.getResponseWriter();
        if (writer == null) {
            throw new IllegalStateException("The ResponseWriter is currently (null).  This typically "
                    + "means you are attempting to write before the RenderResponse phase!");
        }
        try {
            writer.write((text == null) ? "" : text);
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * <p> This command marks the response complete.  This means that no
     *     additional response will be sent.  This is useful if you've
     *     provided a response already and you don't want JSF to do it again
     *     (it may cause problems to do it 2x).</p>
     */
    public void responseComplete() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (!isComplete(ctx)) {
            FacesContext.getCurrentInstance().responseComplete();
        }
    }

    /**
     * <p> This command indicates to JSF that the request should proceed
     *     immediately to the render response phase.  It will be ignored if
     *     rendering has already begun.  This is useful if you want to stop
     *     processing and jump to the response.  This is often the case when
     *     an error occurs or validation fails.  Typically the page the user
     *     is on will be reshown (although if navigation has already
     *     occurred, the new page will be shown.</p>
     */
    public void renderResponse() {
        FacesContext.getCurrentInstance().renderResponse();
    }

    /**
     * <p> This command provides a way to see the call stack by printing a
     *     stack trace.  The output will go to stderr and will also be
     *     returned in the output value "stackTrace".  An optional message
     *     may be provided to be included in the trace.</p>
     */
    public void printStackTrace(final String msg) {
        // Get the StackTrace
        final StringWriter strWriter = new StringWriter();
        new RuntimeException((msg == null) ? "" : msg).printStackTrace(new PrintWriter(strWriter));
        // Print it to stderr and return it
        System.err.println(strWriter.toString());
    }

    /**
     * <p> Returns the nano seconds since some point in time.  This is only useful for relative measurements.</p>
     */
    public long getNanoTime() {
        return System.nanoTime() - nanoStartTime;
    }

    /**
     * <p> This handler redirects to the given page.</p>
     *
     * @param page The page to redirect to.
     */
    public void redirect(final String page) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (isComplete(ctx)) {
            return;
        }
        try {
            ctx.getExternalContext().redirect(page);
            ctx.responseComplete();
        } catch (final IOException ex) {
            throw new RuntimeException("Unable to navigate to page '" + page + "'!", ex);
        }
    }

    /**
     * <p> This returns a <code>UIViewRoot</code>.  If the
     *     <code>pageName</code> is supplied it will return the requested
     *     <code>UIViewRoot</code> (if found). If the <code>id</code> is
     *     <code>null</code>, it will return the current
     *     <code>UIViewRoot</code>.</p>
     */
    public UIViewRoot getUIViewRoot(final String pageName) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (isComplete(ctx)) {
            return null;
        }
        final UIViewRoot root;
        if (pageName == null) {
            root = ctx.getViewRoot();
        } else {
            root = ctx.getApplication().getViewHandler().
                    // Ensure we start w/ a '/'
                    createView(ctx, (pageName.charAt(0) == '/') ? pageName : "" + pageName);
        }
        return root;
    }

    /**
     *  <p> This handler navigates to the given page.  <code>page</code> may
     *      either be a <code>UIViewRoot</code> or a <code>String</code>
     *      representing a <code>UIViewRoot</code>.  Passing in a
     *      <code>String</code> name of a <code>UIViewRoot</code> will always
     *      create a new <code>UIViewRoot</code>.</p>
     *
     *  <p> {@link #getUIViewRoot(String)} provides a way to obtain a
     *      <code>UIViewRoot</code>.</p>
     *
     *  @param page <code>UIViewRoot</code> or page name in which to navigate to.
     */
    public void navigate(final Object page) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (isComplete(ctx)) {
            return;
        }
        final UIViewRoot root;
        if (page instanceof String) {
            // Get the UIViewRoot by name...
            root = getUIViewRoot((String) page);
        } else if (page instanceof UIViewRoot) {
            // We received a UIViewRoot, use it...
            root = (UIViewRoot) page;
        } else {
            throw new IllegalArgumentException("Type '" + page.getClass().getName()
                    + "' is not valid.  It must be a String or UIViewRoot.");
        }
        // Set the UIViewRoot so that it will be displayed
        ctx.setViewRoot(root);
    }

    public static boolean isComplete(final FacesContext ctx) {
        return (ctx == null || ctx.getResponseComplete());
    }
}
