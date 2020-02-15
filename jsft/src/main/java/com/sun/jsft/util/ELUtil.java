package com.sun.jsft.util;

import javax.el.ELContext;
import javax.el.ExpressionFactory;
import javax.el.ValueExpression;
import javax.faces.context.FacesContext;


/**
 *  <p>	This class provides methods to help work with EL expressions.</p>
 */
public class ELUtil {

    /**
     *	<p> Constructor.</p>
     */
    protected ELUtil() {
		// Prevent direct instantiation.  Use getInstance()
    }

    /**
     *	<p> Provides access to the <code>ELUtil</code> singleton instance.</p>
     */
    public static ELUtil getInstance() {
	return instance;
    }
    
    /**
     *	<p> Set ELUtil instance.</p>
     */
    public static void setInstance(ELUtil elUtil) {
	instance =  elUtil;
    }


    /**
     *	<p> This method evalutes the given EL expression w/i the current JSF
     *	    environment.  If it fails, it will return <code>null</code>.
     *	    The EL should contain "<code>#{}</code>".</p>
     */
    public Object eval(String el) {
	Object value = null;
	FacesContext ctx = FacesContext.getCurrentInstance();
	if (ctx != null) {
	    value = getValueExpression(ctx, el).getValue(ctx.getELContext());
	}
	return value;
    }

    /**
     *	<p> This method uses the given EL expression w/i the current JSF
     *	    environment to set the given <code>value</code>.  The el string
     *	    should contain "<code>#{}</code>".</p>
     */
    public void setELValue(String el, Object value) {
	FacesContext ctx = FacesContext.getCurrentInstance();
	if (ctx != null) {
	    getValueExpression(ctx, el).setValue(ctx.getELContext(), value);
	}
    }

    /**
     *	<p> This method evalutes the given EL expression relative to the given
     *	    bean.  The given <code>el</code> String should <em>not</em> include
     *	    "#{}" characters around itself.</p>
     */
    public Object eval(Object bean, String el) {
	FacesContext ctx = FacesContext.getCurrentInstance();
	ctx.getExternalContext().getRequestMap().put(TMP_BEAN, bean);
	return eval("#{requestScope." + TMP_BEAN + "." + el + "}");
    }

    /**
     *	<p> This method uses the given EL expression relative to the given
     *	    bean to set the given <code>value</code>.  The given
     *	    <code>el</code> String should <em>not</em> include "#{}" characters
     *	    around itself.</p>
     */
    public void setELValue(Object bean, String el, Object value) {
	FacesContext ctx = FacesContext.getCurrentInstance();
	ctx.getExternalContext().getRequestMap().put(TMP_BEAN, bean);
	setELValue("#{requestScope." + TMP_BEAN + "." + el + "}", value);
    }

    /**
     *	<p> This method produces a <code>ValueExpression</code> from the given
     *	    <code>el</code> String.</p>
     */
    public ValueExpression getValueExpression(FacesContext ctx, String el) {
	// Get the EL Context
	ELContext elCtx = ctx.getELContext();

	// Create expression
	ExpressionFactory fact = ctx.getApplication().getExpressionFactory();
	return fact.createValueExpression(elCtx, el, Object.class);
    }

    private static final String	    TMP_BEAN	    = "_tBn";
    private static ELUtil	    instance	    = new ELUtil();
}
