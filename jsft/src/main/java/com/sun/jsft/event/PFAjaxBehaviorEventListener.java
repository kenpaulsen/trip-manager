package com.sun.jsft.event;

import java.util.Map;

import javax.faces.event.AbortProcessingException;
import javax.faces.event.AjaxBehaviorEvent;

import org.primefaces.behavior.ajax.AjaxBehaviorListenerImpl;


/**
 *  <p> This class is used to handle an p:ajax event.</p>
 *
 *  @author Ken Paulsen (kenapaulsen@gmail.com)
 */
public class PFAjaxBehaviorEventListener extends AjaxBehaviorListenerImpl {

// FIXME: I think this class may need to implement stateholder.... see superclass and ensure "type" is persisted across serialization!!
    /**
     *  <p> This constructor requires the f:ajax event type so that it can be
     *      used later to distinguish between different Ajax events.</p>
     */
    public PFAjaxBehaviorEventListener(String type) {
        super();
        delegate = new AjaxBehaviorEventListener(type);
    }

    /**
     *  <p> This method processes the <code>AjaxBehaviorEvent</code>.</p>
     *
     *  @param event the <code>AjaxBehaviorEvent</code> instance that is being processed.
     *
     *  @throws AbortProcessingException if lifecycle processing should cease for this request.
     */
    public void processAjaxBehavior(AjaxBehaviorEvent event) throws AbortProcessingException {
        delegate.processAjaxBehavior(event);
    }

    private AjaxBehaviorEventListener delegate = null;
}
