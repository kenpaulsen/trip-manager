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
package com.sun.jsft.facelets;

import com.sun.jsft.event.Command;
import com.sun.jsft.event.ELCommand;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> This class is responsible for reading in all the commands for the
 *     given String.  The String typically is passed in from the body
 *     content of event.</p>
 *
 *  @author  Ken Paulsen (kenapaulsen@gmail.com)
 */
public class CommandReader {
    // Map to hold shortcut mappings (e.g. keywords)
    private static final char OPEN_BRACKET = '[';
    private static final char CLOSE_BRACKET = ']';
    private static final char OPEN_PAREN = '(';
    private static final char CLOSE_PAREN = ')';
    private static final String OPEN_CDATA = "<![CDATA[";
    private static final String CLOSE_CDATA = "]]>";
    private static final Map<String, String> _reservedMappings = new HashMap<>(16);
    static {
        _reservedMappings.put("foreach", "jsft.foreach");
        _reservedMappings.put("for", "jsft._for");
        _reservedMappings.put("println", "jsft.println");
        _reservedMappings.put("write", "jsft.write");
        _reservedMappings.put("setAttribute", "jsft.setAttribute");
        _reservedMappings.put("responseComplete", "jsft.responseComplete");
        _reservedMappings.put("renderResponse", "jsft.renderResponse");
        _reservedMappings.put("printStackTrace", "jsft.printStackTrace");
        _reservedMappings.put("getNanoTime", "jsft.getNanoTime");
    }

    private final CommandParser parser;

    /**
     * <p> Constructor.</p>
     */
    public CommandReader(String str) {
        this(new ByteArrayInputStream(
                ("{" + CommandReader.unwrap(str) + "\n}").getBytes()));
    }

    /**
     * <p> Constructor.</p>
     *
     * @param stream The <code>InputStream</code> for the {@link Command}.
     */
    protected CommandReader(InputStream stream) {
        parser = new CommandParser(stream);
    }

    /**
     * <p> The read method uses the {@link CommandParser} to parses the
     *     template.
     *
     * @return The list of {@link Command}.
     *
     * @throws IOException if it cannot read the commands.
     */
    public List<Command> read() throws IOException {
        // Start...
        parser.open();

        try {
            // Populate the LayoutDefinition from the Document
            return readCommandList();
        } finally {
            parser.close();
        }
    }

// FIXME: Parenthesis are not well supported.  When convertKeywords is called,
// FIXME: it receivces "(foo...)".  First foo() is not recognized because of
// FIXME: the leading '('.  Second, there is a good possibility that other
// FIXME: keywords may exist in the middle of the string.  Need to rethink how
// FIXME: this conversion is done.
    private Command readCommand() throws IOException {
        // Skip White Space...
        parser.skipCommentsAndWhiteSpace(CommandParser.SIMPLE_WHITE_SPACE);

        // Read the next Command
        String commandLine = parser.readUntil(new int[] {';', '{', '}'}, true);

        // Read the children
        int ch = parser.nextChar();
        List<Command> commandChildren = null;
        if (ch == '{') {
            // Read the Command Children 
            commandChildren = readCommandList();
        } else if (ch == '}') {
            parser.unread(ch);
        }
        // Check to see if there is a variable to store the result...
        String variable = null;
        int idx = indexOf((byte) '=', commandLine);
        if (idx != -1) {
            // We have a result variable, store it separately...
            variable = commandLine.substring(0, idx).trim();
            commandLine = commandLine.substring(++idx).trim();
        }
        // If "if" handle "else" if present
        Command elseCommand = null;
        if (commandLine.startsWith("if")) {
            // First convert "if" to the real if handler...
            commandLine = "jsft.ifCommand" + commandLine.substring(2);

            // Check the next few characters to see if they are "else"...
            parser.skipCommentsAndWhiteSpace(CommandParser.SIMPLE_WHITE_SPACE);
            int[] next = new int[] {
                parser.nextChar(),
                parser.nextChar(),
                parser.nextChar(),
                parser.nextChar(),
                parser.nextChar()
            };
            if ((next[0] == 'e')
                    && (next[1] == 'l')
                    && (next[2] == 's')
                    && (next[3] == 'e')
                    && (Character.isWhitespace((char) next[4]))) {
                // This is an else case, parse it...
                elseCommand = readCommand();
            } else {
                // Not an else, restore the parser state
                for (idx=4; idx > -1; idx--) {
                    if (next[idx] != -1) {
                        parser.unread(next[idx]);
                    }
                }
            }
        }
        // Create the Command
        Command command = null;
        if ((commandLine.length() > 0) || (commandChildren != null)) {
            command = new ELCommand(
                    variable,
                    convertKeywords(commandLine),
                    commandChildren,
                    elseCommand);
        }
        // Return the LayoutElement
        return command;
    }

    /**
     * <p> This method replaces keywords with the "real" syntax for developer convenience.</p>
     */
    private String convertKeywords(final String exp) {
        if (exp == null) {
            return null;
        }
        // Get the key to lookup
        String key = exp;
        int paren = exp.indexOf(OPEN_PAREN);
        if (paren != -1) {
            key = exp.substring(0, paren);
            if (key.indexOf('.') != -1) {
                // '.' found, this is not a keyword...
                return exp;
            }
        }
        key = key.trim();

        // Check for mapping...
        final String value = _reservedMappings.get(key);
        return (value == null) ? exp : value + exp.substring(key.length());
    }

    /**
     * <p> This method looks for the given <code>char</code> in the given
     *     <code>String</code>.  It will not match any values that are found
     *     within parenthesis or quotes.</p>
     */
    private int indexOf(final byte ch, final String str) {
        byte[] bytes = str.getBytes();
        
        int idx = 0;
        int insideChar = -1;
        for (byte curr : bytes) {
            if (insideChar == -1) {
                // Not inside anything...
                if (ch == curr) {
                    break;
                } else if (('\'' == curr) || ('"' == curr)) {
                    insideChar = curr;
                } else if (OPEN_PAREN == curr) {
                    insideChar = CLOSE_PAREN;
                } else if (OPEN_BRACKET == curr) {
                    insideChar = CLOSE_BRACKET;
                }
            } else if (insideChar == curr) {
                // Was inside something, ending now...
                insideChar = -1;
            }
            idx++;
        }

        // If we found it return it, otherwise return -1
        if (idx >= bytes.length) {
            idx = -1;
        }
        return idx;
    }

    /**
     * <p> This method reads Commands until a closing '}' is encountered.</p>
     */
    private List<Command> readCommandList() throws IOException {
        int ch = parser.nextChar();
        List<Command> commands = new ArrayList<>();
        Command command;
        while (ch != '}') {
            // Make sure readCommand gets the full command line...
            if (ch != '{') {
                // We want to throw this char away...
                parser.unread(ch);
            }

            // Read a Command
            command = readCommand();
            if (command != null) {
                commands.add(command);
            }

            // Skip White Space...
            parser.skipCommentsAndWhiteSpace(CommandParser.SIMPLE_WHITE_SPACE);

            // Get the next char...
            ch = parser.nextChar();
            if (ch == -1)  {
                throw new IOException(
                    "Unexpected end of stream! Expected to find '}'.");
            }
        }

        // Return the Commands
        return commands;
    }

    /**
     * <p> This function removes the containing CDATA tags, if found.</p>
     */
    private static String unwrap(String str) {
        str = str.trim();
        if (str.startsWith(OPEN_CDATA)) {
            int endingIdx = str.lastIndexOf(CLOSE_CDATA);
            if (endingIdx != -1) {
                // Remove the CDATA wrapper
                str = str.substring(OPEN_CDATA.length(), endingIdx);
            }
        }
        return str;
    }
}
