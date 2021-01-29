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

package com.sun.jsft.util;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.faces.component.UIViewRoot;
import javax.faces.context.FacesContext;



/**
 *  <p>	This class is for general purpose utility methods.</p>
 *
 *  Created  March 29, 2011
 *  @author  Ken Paulsen (kenapaulsen@gmail.com)
 */
public final class Util {

    /**
     *	<p> Prevent direct instantiation.</p>
     */
    private Util() {
	// Hide constructor
    }

    /**
     *	<p> This method returns the ContextClassLoader unless it is null, in
     *	    which case it returns the ClassLoader that loaded "obj".  Unless it
     *	    is null, in which it will return the system ClassLoader.</p>
     *
     * @param	obj May be null, if non-null when the Context ClassLoader is
     *		    null, then the Classloader used to load this Object will be
     *		    returned.
     */
    public static ClassLoader getClassLoader(Object obj) {
	// Get the ClassLoader
	ClassLoader loader = Thread.currentThread().getContextClassLoader();
	if ((loader == null) && (obj != null)) {
            loader = obj.getClass().getClassLoader();
	}

	// Wrap with custom ClassLoader if specified
	loader = getCustomClassLoader(loader);

	return loader;
    }
// NOTE: Maybe in addition to getClassLoader, we should have Iterator<ClassLoader> getClassLoaders() for cases where we want to attempt multiple ClassLoaders

    /**
     *	<p> Method to get the custom <code>ClassLoader</code> if one exists.
     *	    If one does not exist, it will return the
     *	    <code>ClassLoader</code> that is passed in.  If (null) is passed
     *	    in for the parent <code>ClassLoader</code>, it will get the
     *	    <b>System</b> <code>ClassLoader</code>, not the context
     *	    <code>ClassLoader</code> or any other one.</p>
     */
    private static ClassLoader getCustomClassLoader(ClassLoader parent) {
	// Figure out the parent ClassLoader
	parent = (parent == null) ? ClassLoader.getSystemClassLoader() : parent;

	// Check to see if we've calculated the ClassLoader for this parent
	FacesContext ctx = FacesContext.getCurrentInstance();
	Map<ClassLoader, ClassLoader> classLoaderCache =
		getClassLoaderCache(ctx);
	ClassLoader loader = classLoaderCache.get(parent);
	if (loader != null) {
	    return loader;
	}
	loader = parent;

	// Look to see if a custom ClassLoader was specified via an initParam
	String clsName = null;
	if (ctx != null) {
	    clsName = (String) ctx.getExternalContext().
		    getInitParameterMap().get(CUSTOM_CLASS_LOADER);
	}
	if (clsName != null) {
	    if (clsName.equals(loader.getClass().getName())) {
		// It has already been wrapped
		return loader;
	    }
	    try {
		// Intantiate the custom classloader w/ "loader" as its parent
		Class cls = Class.forName(clsName, true, parent);
		loader = (ClassLoader) cls.getConstructor(
			new Class[] {ClassLoader.class}).newInstance(parent);

		// Set custom classloader as the context-classloader... This
		// didn't work, JSF blew up... revisit this if necessary
//		Thread.currentThread().setContextClassLoader(loader);
	    } catch (ClassNotFoundException ex) {
		throw new IllegalArgumentException("Unable to load class ("
		    + clsName + ").  Make sure your context-param is "
		    + "specified correctly and that your custom ClassLoader "
		    + "is included in your application.", ex);
	    } catch (NoSuchMethodException ex) {
		throw new IllegalArgumentException("Unable to load class ("
		    + clsName + ").  You must have a constructor that "
		    + "allows the parent ClassLoader to be provided on your "
		    + "custom ClassLoader.", ex);
	    } catch (InstantiationException ex) {
		throw new RuntimeException("Unable to instantiate class ("
		    + clsName + ")!", ex);
	    } catch (IllegalAccessException ex) {
		throw new RuntimeException("Unable to access class ("
		    + clsName + ")!", ex);
	    } catch (java.lang.reflect.InvocationTargetException ex) {
		throw new RuntimeException("Unable to instantiate class ("
		    + clsName + ")!", ex);
	    }
	}

	// Cache for next time
	classLoaderCache.put(parent, loader);

	// Return the ClassLoader (may be the same one passed in)
	return loader;
    }

    /**
     *	<p> Provides access to the application-scoped Map which stores custom
     *	    ClassLoaders which may wrap a parent ClassLoader in this
     *	    application.</p>
     */
    private static Map<ClassLoader, ClassLoader> getClassLoaderCache(FacesContext ctx) {
	if (ctx == null) {
	    ctx = FacesContext.getCurrentInstance();
	}
	Map<ClassLoader, ClassLoader> map = null;
	if (ctx != null) {
	    map = (Map<ClassLoader, ClassLoader>) ctx.getExternalContext().
		    getApplicationMap().get(CLASSLOADER_CACHE);
	}
	if (map == null) {
	    // 1st time... initialize it
	    map = new HashMap<ClassLoader, ClassLoader>(4);
	    if (ctx != null) {
		ctx.getExternalContext().getApplicationMap().put(
			CLASSLOADER_CACHE, map);
	    }
	}

	// Return the map...
	return map;
    }

    /**
     *	<p> This method will attempt to load a Class from the context
     *	    ClassLoader.  If it fails, it will try from the ClassLoader used
     *	    to load the given object.  If that's null, or fails, it will try
     *	    using the System ClassLoader.</p>
     *
     *	@param	className   The full name of the class to load.
     *	@param	obj	    An optional Object used to help find the
     *			    ClassLoader to use.
     */
    public static Class loadClass(String className, Object obj) throws ClassNotFoundException {
	// Get the context ClassLoader
	ClassLoader loader = getClassLoader(obj);
	Class cls = null;
	if (loader != null) {
	    try {
		cls = Class.forName(className, false, loader);
	    } catch (ClassNotFoundException ex) {
		// Ignore
		if (LogUtil.finestEnabled()) {
		    LogUtil.finest("Unable to find class (" + className
			+ ") using the context ClassLoader: '" + loader
			+ "'.  I will keep looking.", ex);
		}
	    }
	}
	if (cls == null) {
	    // Still haven't found it... look for it somewhere else.
            loader = (obj == null) ? null : obj.getClass().getClassLoader();
            try {
                cls = (loader == null) ?
                        null : Class.forName(className, false, loader);
            } catch (ClassNotFoundException ex) {
                // Ignore
                if (LogUtil.finestEnabled()) {
                    LogUtil.finest("Unable to find class (" + className
                        + ") using ClassLoader: '" + loader
                        + "'.  I will try the System ClassLoader.", ex);
                }
            }

	    if (cls == null) {
		// Still haven't found it, use System ClassLoader
		loader = ClassLoader.getSystemClassLoader();

		// Allow this one to throw the Exception if not found
		cls = Class.forName(className, false, loader);
	    }
	}

	// Return the Class
	return cls;
    }

    /**
     *	<p> Method which returns the Class for the given class name, or null
     *	    if any exception occurs.  No exceptions are thrown.</p>
     */
    public static Class noExceptionLoadClass(String name) {
	Class cls = null;
	try {
	    cls = Util.loadClass(name, null);
	} catch (Exception ex) {
	    // Ignore...
            cls = null;
	}
	return cls;
    }

    /**
     *	<p> This method attempts load the requested Class.  If obj is a
     *	    String, it will use this value as the fully qualified class name.
     *	    If it is a Class, it will return it.  If it is anything else, it
     *	    will return the Class for the given Object.</p>
     *
     *	@param	obj The Object describing the requested Class
     */
    public static Class getClass(Object obj) throws ClassNotFoundException {
	if ((obj == null) || (obj instanceof Class)) {
	    return (Class) obj;
	}
	Class cls = null;
	if (obj instanceof String) {
	    cls = loadClass((String) obj, obj);
	} else {
	    cls = obj.getClass();
	}
	return cls;
    }

    /**
     *	<p> This method locates the requested <code>Method</code> on the
     *	    given <code>Class</code>, with the given <code>params</code>.  This
     *	    method does not throw any exceptions.  Instead it will return
     *	    <code>null</code> if unable to locate the method.</p>
     */
    public static Method getMethod(Class cls, String name, Class ... prms) {
	Method method = null;
	try {
	    method = cls.getMethod(name, prms);
	} catch (NoSuchMethodException ex) {
	    // Do nothing, we're eating the exception
            method = null;
	} catch (SecurityException ex) {
	    // Do nothing, we're eating the exception
            method = null;
	}
	return method;
    }

    /**
     *	<p> This method converts the given Map into a Properties Map (if it is
     *	    already one, then it simply returns the given Map).</p>
     */
    public static Properties mapToProperties(Map map) {
	if ((map == null) || (map instanceof Properties)) {
	    return (Properties) map;
	}

	// Create Properties and add all the values
	Properties props = new Properties();
	props.putAll(map);

	// Return the result
	return props;
    }

    /**
     *	<p> Help obtain the current <code>Locale</code>.</p>
     */
    public static Locale getLocale(FacesContext context) {
	Locale locale = null;
	if (context != null) {
	    // Attempt to obtain the locale from the UIViewRoot
	    UIViewRoot root = context.getViewRoot();
	    if (root != null) {
		locale = root.getLocale();
	    }
	}

	// Return the locale; if not found, return the system default Locale
	return (locale == null) ? Locale.getDefault() : locale;
    }

    /**
     *	<p> This method escapes text so that HTML tags and escape characters
     *	    can be shown in an HTML page without seeming to be parsed.</p>
     */
    public static String htmlEscape(String str) {
	if (str == null) {
	    return null;
	}
	StringBuffer buf = new StringBuffer("");
	for (char ch : str.toCharArray()) {
	    switch (ch) {
		case '&':
		    buf.append(AMPERSAND);
		    break;
		case '<':
		    buf.append(LESS_THAN);
		    break;
		case '>':
		    buf.append(GREATER_THAN);
		    break;
		case '"':
		    buf.append(DOUBLE_QUOTE);
		    break;
		case '\'':
		    buf.append(SINGLE_QUOTE);
		    break;
		default:
		    buf.append(ch);
		    break;
	    }
	}
	return buf.toString();
    }

    /**
     *	<p> This method reverses html-escaping in the given String.  It is
     *	    meant to be the converse of {@link #htmlEscape(String)}.  It does
     *	    not convert every entity, it only converts those supported by
     *	    <code>htmlEscape(String)</code>.  In other words:</p>
     *
     *	<ul><li>&amp;amp; to &amp;</li>
     *	    <li>&amp;lt; to &lt;</li>
     *	    <li>&amp;gt; to &gt;</li>
     *	    <li>&amp;quot; to &quot;</li>
     *	    <li>&amp;#39; to &#39;</li></ul>
     */
    public static String unHtmlEscape(String str) {
	// Ensure we have a string...
	if (str == null) {
	    return null;
	}

	// Store a lower-case version to avoid worrying about case
	char lower[] = str.toLowerCase().toCharArray();
	// Also preserve the original case to avoid changing values
	char orig[] = str.toCharArray();

	// Variables for indexes
	int len = lower.length;
	int stop = len - 3; // Smallest supported entity is 4 chars...
	char ch, next;
	int idx = 0;

	// Loop through chars and create the new string w/ converted entities
	StringBuffer buf = new StringBuffer("");
	for (idx = 0; idx<stop; idx++) {
	    ch = orig[idx];
	    switch (ch) {
		case '&':
		    // get char after & (don't check bounds, 3 extra for sure)
		    next = lower[idx + 1];
		    if (next == 'a') {
			// Maybe AMPERSAND entity
			if (((idx + 4) < len) && new String(lower, idx, 5).equals(AMPERSAND)) {
			    // Yes, &amp;!
			    buf.append('&');
			    idx += 4;
			} else {
			    // Unsupported entity, or plain '&' character
			    buf.append(ch);
			}
		    } else if (next =='l') {
			// Maybe LESS_THAN entity
			if (((idx + 3) < len) && new String(lower, idx, 4).equals(LESS_THAN)) {
			    // Yes, &lt;!
			    buf.append('<');
			    idx += 3;
			} else {
			    // Unsupported entity, or plain '&' character
			    buf.append(ch);
			}
		    } else if (next == 'g') {
			// Maybe GREATER_THAN entity
			if (((idx + 3) < len) && new String(lower, idx, 4).equals(GREATER_THAN)) {
			    // Yes, &gt;!
			    buf.append('>');
			    idx += 3;
			} else {
			    // Unsupported entity, or plain '&' character
			    buf.append(ch);
			}
		    } else if (next == 'q') {
			// Maybe DOUBLE_QUOTE entity
			if (((idx + 5) < len) && new String(lower, idx, 6).equals(DOUBLE_QUOTE)) {
			    // Yes, &quot;!
			    buf.append('"');
			    idx += 5;
			} else {
			    // Unsupported entity, or plain '&' character
			    buf.append(ch);
			}
		    } else if (next == '#') {
			// Maybe SINGLE_QUOTE entity
			if (((idx + 4) < len) && new String(lower, idx, 5).equals(SINGLE_QUOTE)) {
			    // Yes, &#39;!
			    buf.append('\'');
			    idx += 4;
			} else {
			    // Unsupported entity, or plain '&' character
			    buf.append(ch);
			}
		    } else {
			// Unsupported entity, or plain '&' character
			buf.append(ch);
		    }
		    break;
		default:
		    buf.append(ch);
		    break;
	    }
	}

	// We usually stop early, so we may have extra chars to add...
	for (; idx < len; idx++) {
	    buf.append(orig[idx]);
	}

	// Return the new String
	return buf.toString();
    }

    /**
     *	<p> This method strips leading delimeter. </p>
     *
     */
    protected static String stripLeadingDelimeter(String str, char ch) {
	if(str == null || str.equals("")) {
	    return str;
	}
	int j = 0;
	char[] strArr = str.toCharArray();
	for(int i=0; i < strArr.length; i++) {
	    j=i;
	    if(strArr[i] != ch) {
		break;
	    }
	}
	return str.substring(j);

    }

    /**
     * Closes an InputStream if it is non-null, throwing away any Exception
     * that may occur
     * @param is
     */
    public static void closeStream(InputStream is) {
	if (is != null) {
	    try {
		is.close();
	    } catch (Exception e) {
		// ignore
                is = null;
	    }
	}
    }


    /**
     *	<p> Application scope attribute name for storing custom
     *	    <code>ClassLoaders</code>.</p>
     */
    private static final String CLASSLOADER_CACHE   =	"__jsft_ClassLoaders";

    private static final String AMPERSAND	    =	"&amp;";
    private static final String LESS_THAN	    =	"&lt;";
    private static final String GREATER_THAN	    =	"&gt;";
    private static final String DOUBLE_QUOTE	    =	"&quot;";
    private static final String SINGLE_QUOTE	    =	"&#39;";

    /**
     *	<p> This is the context-param that specifies the JSFTemplating
     *	    custom <code>ClassLoader</code> to use.</p>
     */
    public static final String	CUSTOM_CLASS_LOADER = "com.sun.jsft.CLASSLOADER";
}
