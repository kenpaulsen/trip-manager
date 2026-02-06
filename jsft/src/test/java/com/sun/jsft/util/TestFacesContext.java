package com.sun.jsft.util;

import com.sun.faces.application.ApplicationImpl;
import jakarta.el.ELContext;
import jakarta.faces.application.Application;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.ResponseStream;
import jakarta.faces.context.ResponseWriter;
import jakarta.faces.lifecycle.Lifecycle;
import jakarta.faces.render.RenderKit;
import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.Principal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

public class TestFacesContext extends FacesContext {
    private final ExternalContext externalContext;
    private final Application app;
    @Getter @Setter
    private ELContext elCtx;
    private ResponseWriter writer;

    public TestFacesContext(final ExternalContext extCtx) {
        setCurrentInstance(this);
        this.externalContext = extCtx;
        this.app = new ApplicationImpl();
    }

    @Override
    public Application getApplication() {
        return app;
    }

    @Override
    public Iterator<String> getClientIdsWithMessages() {
        return null;
    }

    @Override
    public ExternalContext getExternalContext() {
        return externalContext;
    }

    @Override
    public FacesMessage.Severity getMaximumSeverity() {
        return null;
    }

    @Override
    public Iterator<FacesMessage> getMessages() {
        return null;
    }

    @Override
    public Iterator<FacesMessage> getMessages(String clientId) {
        return null;
    }

    @Override
    public RenderKit getRenderKit() {
        return null;
    }

    @Override
    public boolean getRenderResponse() {
        return false;
    }

    @Override
    public boolean getResponseComplete() {
        return false;
    }

    @Override
    public ResponseStream getResponseStream() {
        return null;
    }

    @Override
    public void setResponseStream(ResponseStream responseStream) {

    }

    @Override
    public ResponseWriter getResponseWriter() {
        return writer;
    }

    @Override
    public void setResponseWriter(ResponseWriter responseWriter) {
        writer = responseWriter;
    }

    @Override
    public UIViewRoot getViewRoot() {
        return null;
    }

    @Override
    public void setViewRoot(UIViewRoot root) {

    }

    @Override
    public void addMessage(String clientId, FacesMessage message) {

    }

    @Override
    public void release() {

    }

    @Override
    public void renderResponse() {

    }

    @Override
    public void responseComplete() {

    }

    @Override
    public ELContext getELContext() {
        return elCtx;
    }

    @Override
    public Lifecycle getLifecycle() {
        return null;
    }

    public static class TestExternalContext extends ExternalContext {
        @Getter @Setter
        private Map<String, Object> requestMap = new HashMap<>();
        @Getter @Setter
        private Map<String, Object> sessionMap = new HashMap<>();
        @Getter @Setter
        private Map<String, Object> applicationMap = new HashMap<>();
        @Getter @Setter
        private Object context = null; // FIXME: e.g. ServletContext
        @Getter @Setter
        private Map<String, String> initParameterMap = new HashMap<>();
        @Getter @Setter
        private Object request = null; // FIXME: e.g. HttpServletRequest
        @Getter @Setter
        private String requestContextPath = "/";
        @Getter @Setter
        private Map<String, Object> requestCookieMap = new HashMap<>();
        @Getter @Setter
        private Map<String, String> requestHeaderMap = new HashMap<>();
        @Getter @Setter
        private Map<String, String[]> requestHeaderValuesMap = new HashMap<>();
        @Getter @Setter
        private Map<String, String> requestParameterMap = new HashMap<>();
        @Getter @Setter
        private Map<String, String[]> requestParameterValuesMap = new HashMap<>();
        @Getter @Setter
        private String requestPathInfo = null;
        @Getter @Setter
        private String requestServletPath = null;

        @Getter @Setter
        private String authType = null;
        @Getter @Setter
        private String remoteUser = null;
        @Getter @Setter
        private Object response = null;                  // FIXME: Should be HttpServletResponse
        @Getter @Setter
        private Principal userPrincipal = null;

        @Override
        public void dispatch(String path) {
        }

        @Override
        public String encodeActionURL(String url) {
            return url;
        }

        @Override
        public String encodeNamespace(String name) {
            return name;
        }

        @Override
        public String encodeResourceURL(String url) {
            return url;
        }

        @Override
        public String encodeWebsocketURL(String url) {
            return url;
        }

        @Override
        public String getInitParameter(String name) {
            return getInitParameterMap().get(name);
        }

        @Override
        public Locale getRequestLocale() {
            return Locale.getDefault();
        }

        @Override
        public Iterator<Locale> getRequestLocales() {
            return List.of(Locale.getDefault()).iterator();
        }

        @Override
        public Iterator<String> getRequestParameterNames() {
            return requestParameterMap.keySet().iterator();
        }

        @Override
        public URL getResource(String path) throws MalformedURLException {
            return new File(path).toURI().toURL();
        }

        @Override
        public InputStream getResourceAsStream(String path) {
            return path.getClass().getClassLoader().getResourceAsStream(path);
        }

        @Override
        public Set<String> getResourcePaths(String path) {
            return null;
        }

        @Override
        public Object getSession(boolean create) {
            return null;
        }

        @Override
        public boolean isUserInRole(String role) {
            return false;
        }

        @Override
        public void log(String message) {
            System.out.println(message);
        }

        @Override
        public void log(String message, Throwable exception) {
            System.out.println(message);
            exception.printStackTrace();
        }

        @Override
        public void redirect(String url) {
        }

        @Override
        public void release() {
        }
    }
}
