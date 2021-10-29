package com.sun.jsft.util;

import com.sun.jsft.commands.JSFTCommands;
import jakarta.el.ValueExpression;
import jakarta.faces.context.FacesContext;

/**
 * <p> This class provides methods to help work with EL expressions.</p>
 */
public class ELUtil {
    private static final String TMP_BEAN            = "_tBn";
    private static ELUtil       instance            = new ELUtil();

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
}
