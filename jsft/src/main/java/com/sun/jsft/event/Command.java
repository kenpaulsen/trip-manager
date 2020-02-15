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

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;

import javax.faces.event.AbortProcessingException;


/**
 *  <p>	This class represents a Command.</p>
 *
 *  Created March 31, 2011
 *  @author Ken Paulsen kenapaulsen@gmail.com
 */
public abstract class Command implements Serializable {

    /**
     *	<p> Default constructor needed for serialization.</p>
     */
    public Command() {
        super();
    }

    /**
     *	<p> Constructor which sets the child commands.</p>
     */
    public Command(List<Command> children, Command elseCommand) {
	setChildCommands(children);
	setElseCommand(elseCommand);
    }

    /**
     *	<p> This is the method responsible for performing the action.  It is
     *	    also responsible for invoking any of its child commands.</p>
     */
    public abstract Object invoke() throws AbortProcessingException;

    /**
     *	<p> This getter method retrieves the command to be invoked if this
     *	    command has an "else" clause.  In most cases this will return
     *	    <code>null</code>.</p>
     */
    public Command getElseCommand() {
	return this.elseCommand;
    }

    /**
     *	<p> Returns a reference to list of child commands.  Note, there is
     *	    nothing to stop you from modifying this list... however, it is
     *	    strongly discouraged and may lead to problems.</p>
     */
    public List<Command> getChildCommands() {
	return childCommands;
    }

    /**
     *	<p> This method is a useful helper utility for invoking the child
     *	    {@link Command}s.</p>
     */
    public void invokeChildCommands() {
	if (this.childCommands != null) {
	    for (Command childCommand : this.childCommands) {
		childCommand.invoke();
	    }
	}
    }

    /**
     *	<p> Print out the <code>ELCommand</code>.</p>
     */
    @Override
    public String toString() {
	StringBuilder buf = new StringBuilder("");
	if (childCommands != null) {
	    buf.append("{\n");
	    Iterator<Command> it = childCommands.iterator();
	    while (it.hasNext()) {
		buf.append(it.next().toString());
	    }
	    buf.append("}\n");
	} else {
	    buf.append(";\n");
	}
	return buf.toString();
    }

    /**
     *
     */
    private void setChildCommands(List<Command> commands) {
	this.childCommands = commands;
    }

    /**
     *	<p> This setter method stores the command to be invoked if this
     *	    command has an "else" clause.</p>
     */
    private void setElseCommand(Command command) {
	this.elseCommand = command;
    }


    /**
     *	<p> This is the request scoped key which will store the child
     *	    {@link Command} for the currently executing {@link Command}.</p>
     */
    public static final String COMMAND_KEY = "jsftCommand";

    private static final long serialVersionUID = 6945415932011238909L;

    private List<Command> childCommands = null;
    private Command elseCommand = null;
}
