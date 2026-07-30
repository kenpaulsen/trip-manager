package com.sun.jsft.util;

import com.sun.jsft.commands.JSFTCommands;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.el.ValueExpression;
import jakarta.faces.context.FacesContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * <p> This class provides methods to help work with EL expressions.</p>
 */
public class ELUtil {
    private static final String TMP_BEAN            = "_tBn";
    private static ELUtil       instance            = new ELUtil();
    /** Discovered once; see {@link #expressionFactory()}. */
    private static volatile ExpressionFactory sharedFactory;

    protected ELUtil() {
        // Prevent direct instantiation. Use getInstance()
    }

    /**
     * <p> Provides access to the <code>ELUtil</code> singleton instance.</p>
     */
    public static ELUtil getInstance() {
        return instance;
    }
    
    /**
     * <p> Set ELUtil instance.</p>
     */
    public static void setInstance(final ELUtil elUtil) {
        instance =  elUtil;
    }

    /**
     * <p> This method evalutes the given EL expression w/i the current JSF environment. If it fails, it will return
     *     <code>null</code>. The EL should contain "<code>#{}</code>".</p>
     */
    public Object eval(final String el) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return (ctx == null) ? null : getValueExpression(ctx, el).getValue(ctx.getELContext());
    }

    /**
     * <p> This method uses the given EL expression w/i the current JSF environment to set the given
     *     <code>value</code>. The el string should contain "<code>#{}</code>".</p>
     */
    public void setELValue(final String el, final Object value) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            getValueExpression(ctx, el).setValue(ctx.getELContext(), value);
        }
    }

    /**
     * <p> This method evalutes the given EL expression relative to the given bean. The given <code>el</code> String
     *     should <em>not</em> include "#{}" characters around itself.</p>
     */
    public Object eval(final Object bean, final String el) {
        FacesContext.getCurrentInstance().getExternalContext().getRequestMap().put(TMP_BEAN, bean);
        return eval("#{requestScope." + TMP_BEAN + "." + el + "}");
    }

    /**
     * <p> This method uses the given EL expression relative to the given bean to set the given <code>value</code>.
     *     The given <code>el</code> String should <em>not</em> include "#{}" characters around itself.</p>
     */
    public void setELValue(final Object bean, final String el, final Object value) {
        FacesContext.getCurrentInstance().getExternalContext().getRequestMap().put(TMP_BEAN, bean);
        setELValue("#{requestScope." + TMP_BEAN + "." + el + "}", value);
    }

    /**
     * <p> This method produces a <code>ValueExpression</code> from the given <code>el</code> String.</p>
     */
    public ValueExpression getValueExpression(final FacesContext ctx, final String el) {
        // Create expression if we aren't done already
        return JSFTCommands.isComplete(ctx) ? null :
                ctx.getApplication().getExpressionFactory().createValueExpression(ctx.getELContext(), el, Object.class);
    }

    /**
     * <p> Renders a template <em>outside</em> JSF: literal text with any number of embedded
     *     "<code>#{}</code>" expressions, resolved against the supplied <code>vars</code>.</p>
     *
     * <p><b>Why this exists.</b> {@link #eval(String)} reads {@link FacesContext#getCurrentInstance()} and returns
     *     <code>null</code> when there is none. Anything running off a request thread -- a scheduled job, an
     *     {@code ExecutorService}, an SDK completion callback -- therefore rendered a template to the literal
     *     four-character String "null" and sent it, with no exception and nothing in the log. This method needs no
     *     <code>FacesContext</code>: Jakarta EL supplies its own {@link StandardELContext}, whose standard resolver
     *     chain already handles beans, records, maps, lists and arrays.</p>
     *
     * <p><b>It does NOT escape anything.</b> EL has no notion of an output encoding, so a value containing
     *     <code>&lt;script&gt;</code> is emitted verbatim. Callers rendering user-supplied text into HTML must
     *     escape the values <em>before</em> passing them in -- see {@code MailTemplates.escapeHtml}. Escaping here
     *     instead would be wrong: the same method renders plain-text bodies, where entities would be visible junk.</p>
     *
     * <p><b>A missing variable or property throws</b> {@code jakarta.el.PropertyNotFoundException}, deliberately not
     *     caught here. A typo in a template is a bug that should be loud at the first send, and a caller sending to
     *     many people must contain it per-recipient so one bad template cannot abort an entire run.</p>
     *
     * @param template  The template text. May contain literal text and any number of "<code>#{}</code>" expressions.
     * @param vars      Named values the expressions may reference. A <code>null</code> or empty map is allowed --
     *                  a template with no expressions is returned unchanged.
     * @return The rendered text, never <code>null</code>.
     */
    public String renderWithoutJsf(final String template, final Map<String, Object> vars) {
        if (template == null) {
            return "";
        }
        final ExpressionFactory factory = expressionFactory();
        final StandardELContext ctx = new StandardELContext(factory);
        if (vars != null) {
            vars.forEach((name, value) -> defineVariable(factory, ctx, name, value));
        }
        final Object rendered = factory.createValueExpression(ctx, template, String.class).getValue(ctx);
        return rendered == null ? "" : rendered.toString();
    }

    /**
     * <p> Evaluates a single EL expression outside JSF. Same contract as
     *     {@link #renderWithoutJsf(String, Map)}; use that for whole templates.</p>
     */
    public Object evalWithoutJsf(final String el, final Map<String, Object> vars) {
        if (el == null) {
            return null;
        }
        final ExpressionFactory factory = expressionFactory();
        final StandardELContext ctx = new StandardELContext(factory);
        if (vars != null) {
            vars.forEach((name, value) -> defineVariable(factory, ctx, name, value));
        }
        return factory.createValueExpression(ctx, el, Object.class).getValue(ctx);
    }

    /**
     * <p> Binds one name so "<code>#{name...}</code>" resolves to <code>value</code>.</p>
     *
     * <p>The declared type is taken from the value rather than being fixed to <code>Object</code>, because the
     *    resolver chain dispatches on it -- declaring a record as <code>Object</code> stops
     *    <code>RecordELResolver</code> from matching, and property access silently fails to resolve.</p>
     */
    private static void defineVariable(
            final ExpressionFactory factory,
            final StandardELContext ctx,
            final String name,
            final Object value) {
        final Class<?> type = value == null ? Object.class : value.getClass();
        ctx.getVariableMapper().setVariable(name, factory.createValueExpression(value, type));
    }

    /**
     * <p> The EL implementation, discovered once and reused.</p>
     *
     * <p>{@code newInstance()} does a service lookup and compiles nothing, but it is far from free, and a digest
     *    run renders one template per recipient. Note the implementation comes from the container at runtime
     *    (Tomcat supplies one); a module that calls this in a unit test needs an EL implementation on its own test
     *    classpath or the lookup fails outright.</p>
     */
    private static ExpressionFactory expressionFactory() {
        ExpressionFactory local = sharedFactory;
        if (local == null) {
            synchronized (ELUtil.class) {
                local = sharedFactory;
                if (local == null) {
                    local = ExpressionFactory.newInstance();
                    sharedFactory = local;
                }
            }
        }
        return local;
    }

    public String readFile(final String filename) {
        final InputStream in = this.getClass().getClassLoader().getResourceAsStream(filename);
        if (in == null) {
            return null;
        }
        final StringBuilder templateBuilder = new StringBuilder();
        try (final BufferedReader templateReader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = templateReader.readLine()) != null) {
                templateBuilder.append(line).append("\n");
            }
        } catch (final IOException ex) {
            return null;
        }
        return templateBuilder.toString();
    }
}
