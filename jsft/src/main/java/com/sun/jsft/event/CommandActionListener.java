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

import java.util.List;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ActionEvent;
import jakarta.faces.event.ActionListener;


/**
 *  <p> This class is used to handle an event when an <code>AcitonSource</code>
 *      is activated.</p>
 */
public class CommandActionListener implements ActionListener {

    /**
     * <p>Invoked when the action described by the specified
     * <code>ActionEvent</code> occurs.</p>
     *
     * @param event The <code>ActionEvent</code> that has occurred
     *
     * @throws AbortProcessingException Signal the JavaServer Faces
     *  implementation that no further processing on the current event
     *  should be performed
     */
    public void processAction(ActionEvent event) throws AbortProcessingException {
        FacesContext ctx = FacesContext.getCurrentInstance();
        ctx.getApplication().publishEvent(ctx, CommandActionEvent.class, event.getComponent());
    }

    @Override
    public boolean equals(Object obj) {
	if (obj == null) {
	    // Not if null
	    return false;
	}
	if (obj == this) {
	    // Same object
	    return true;
	}

	// Compare class names, no state... if match, then true.
	return obj.getClass().equals(this.getClass());
    }

    @Override
    public int hashCode() {
	return HASH;
    }

    public static final int HASH    = 13;
}
