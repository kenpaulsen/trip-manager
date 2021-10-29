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

import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.ValueExpression;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.view.facelets.FaceletContext;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * <p> This class represents a Command that is processed via Unified EL.</p>
 * Created  March 31, 2011
 * @author  Ken Paulsen (kenapaulsen@gmail.com)
 */
@Slf4j
@EqualsAndHashCode(callSuper = true)
public class ELCommand extends Command {
    private static final long serialVersionUID = 6201115935174238909L;

    private String resultVar = null;
    private String el = null;

    /**
     * <p> Default constructor needed for serialization.</p>
     */
    public ELCommand() {
        super();
    }

    /**
     * <p> This constructor should be used to create a new
     *     <code>ELCommand</code> instance.  It expects to be passed an
     *     expression, and optionally a List&lt;Command&gt; that represent
     *     <i>child</i> commands.</p>
     *
     * FIXME: Add more documentation on how this works...
     */
    public ELCommand(String resultVar, String el, List<Command> childCommands, Command elseCommand) {
        super(childCommands, elseCommand);
        this.resultVar = resultVar;
        this.el = el;
    }

    /**
     * <p> This method is responsible for dispatching the event to the various
     *     EL expressions that are listening to this event.  It also stores
     *     the Event object in request scope under the key "theEvent" so that
     *     it can be accessed easiliy via EL.  For example:
     *     <code>util.println(theEvent);</code></p>
     */
    public Object invoke() throws AbortProcessingException {
        // Get the FacesContext
        final FacesContext ctx = FacesContext.getCurrentInstance();

        // This is needed in order to recognize ui:param's.  However,
        // ui:param's are not available after the view is created, so you
        // can't access them in beforeEncode events or other events which
        // occur later.
        ELContext elCtx = (ELContext) ctx.getAttributes().get(FaceletContext.FACELET_CONTEXT_KEY);
        if (elCtx == null) {
            // Just in case...
            elCtx = ctx.getELContext();
        }

        // Store the Command for access inside the expression.
        // This is useful for loops or other commands which need access to
        // their child Commands.
        ctx.getExternalContext().getRequestMap().put(COMMAND_KEY, this);

        // Create expression
        final ExpressionFactory fact = ctx.getApplication().getExpressionFactory();
        Object result = null;
        if (this.el.length() > 0) {
            ValueExpression ve;
            ve = fact.createValueExpression(
                    elCtx, "#{" + this.el + "}", Object.class);
            // Execute expression
            result = ve.getValue(elCtx);

            // If we should store the result... do it.
            if (this.resultVar != null) {
                ve = fact.createValueExpression(
                        elCtx, "#{" + this.resultVar + "}", Object.class);
                try {
                    ve.setValue(elCtx, result);
                } catch (final RuntimeException ex) {
                    log.warn("Unable to set '" + resultVar + "' to '" + result + "'", ex);
                }
            }
        } else {
            // Do this since we have no command to execute (which is normally
            // responsible for doing this)
            invokeChildCommands();
        }
        return result;
    }

    /**
     * <p> Print out the <code>ELCommand</code>.</p>
     */
    @Override
    public String toString() {
        return el + super.toString();
    }
}
