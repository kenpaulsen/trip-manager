package com.sun.jsft.component;

import java.io.IOException;
import java.util.Locale;

import jakarta.faces.FacesException;
import jakarta.faces.application.ViewHandler;
import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewDeclarationLanguage;


/**
 *  <p>	This class stores the viewId for later access. It overrides
 *  	{@link #createView(FacesContext, String)} and
 *  	{@link #restoreView(FacesContext, String)} for the sole purpose of
 *  	saving the viewId, it delegates to the original ViewHandler after
 *  	doing so.  All other methods also delegate.</p>
 * 
 *  @author Ken Paulsen (kenapaulsen@gmail.com)
 */
public class ViewScopeViewHandler extends ViewHandler {

    /**
     *	<p> Constructor.</p>
     *
     *	@param	oldViewHandler	The old <code>ViewHandler</code>.
     */
    public ViewScopeViewHandler(ViewHandler oldViewHandler) {
        _oldViewHandler = oldViewHandler;
    }

    /**
     *	<p> Store viewId and delegate.</p>
     */
    @Override
    public UIViewRoot createView(FacesContext context, String viewId) {
        // Save the viewId... needed for UIViewRoot work-a-round
        context.getAttributes().put(ViewScopeViewRoot.CURR_VIEW_ID, viewId);
        UIViewRoot view = _oldViewHandler.createView(context, viewId);
        return view;
    }

    /**
     *	<p> Store viewId and delegate.</p>
     */
    @Override
    public UIViewRoot restoreView(FacesContext context, String viewId) {
        // Save the viewId... needed for UIViewRoot work-a-round
        context.getAttributes().put(ViewScopeViewRoot.CURR_VIEW_ID, viewId);
        UIViewRoot root = _oldViewHandler.restoreView(context, viewId);
        return root;
    }

    ///////////////////////////////////////////////////////////////
    //  The following methods are overrided because they are
    //  required.  They simply delegate to the "old" view handler
    //  to achieve unaltered behavior.
    ///////////////////////////////////////////////////////////////

    /**
     *	<p> Return a URL suitable for rendering (after optional encoding
     *	    performed by the <code>encodeResourceURL()</code> method of
     *	    <code>ExternalContext<code> that selects the specified web
     *	    application resource.  If the specified path starts with a slash,
     *	    it must be treated as context relative; otherwise, it must be
     *	    treated as relative to the action URL of the current view.</p>
     *
     *	@param context	<code>FacesContext</code> for the current request
     *	@param path	Resource path to convert to a URL
     *
     *	@exception  IllegalArgumentException	If <code>viewId</code> is not
     *	    valid for this <code>ViewHandler</code>.
     */
    @Override
    public String getResourceURL(FacesContext context, String path) {
        return _oldViewHandler.getResourceURL(context, path);
    }

    @Override
    public String getWebsocketURL(final FacesContext ctx, final String str) {
        return _oldViewHandler.getWebsocketURL(ctx, str);
    }

    /**
     *	<p> Return a URL suitable for rendering (after optional encoding
     *	    performed by the <code>encodeActionURL()</code> method of
     *	    <code>ExternalContext</code> that selects the specified view
     *	    identifier.</p>
     *
     *	@param	context	<code>FacesContext</code> for this request
     *	@param	viewId	View identifier of the desired view
     *
     *	@exception  IllegalArgumentException	If <code>viewId</code> is not
     *				valid for this <code>ViewHandler</code>.
     */
    @Override
    public String getActionURL(FacesContext context, String viewId) {
        return _oldViewHandler.getActionURL(context, viewId);
    }

    @Override
    public Locale calculateLocale(FacesContext context) {
        return _oldViewHandler.calculateLocale(context);
    }

    @Override
    public String calculateRenderKitId(FacesContext context) {
        return _oldViewHandler.calculateRenderKitId(context);
    }

    @Override
    public void renderView(FacesContext context, UIViewRoot viewToRender) throws IOException, FacesException {
        _oldViewHandler.renderView(context, viewToRender);
    }

    @Override
    public void writeState(FacesContext context) throws IOException {
        _oldViewHandler.writeState(context);
    }

    @Override
    public ViewDeclarationLanguage getViewDeclarationLanguage(FacesContext context, String viewId) {
        return _oldViewHandler.getViewDeclarationLanguage(context, viewId);
    }

    private ViewHandler _oldViewHandler			= null;
}
