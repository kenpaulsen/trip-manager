package com.sun.jsft.event;

import jakarta.faces.component.UIComponent;
import jakarta.faces.event.ComponentSystemEvent;


/**
 *  <p> This event is fired near the beginning of each request for a given
 *		page.  It occurs during the "Restore View" phase, if the request is
 *		a "GET" (i.e. an initial request for a page).</p>
 */
public class InitGetEvent extends InitPageEvent {

    /**
     *  <p> Constructor.</p>
     */
    public InitGetEvent(UIComponent src) {
        super(src);
    }
}
