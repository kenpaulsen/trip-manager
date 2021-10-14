package com.sun.jsft.event;

import jakarta.faces.component.UIComponent;


/**
 *  <p> This event is fired near the beginning of each request for a given
 *		page.  It occurs during the "Restore View" phase, if the request is
 *		a "POST" (i.e. someone clicked a button, or made an ajax POST
 *		request).</p>
 */
public class InitPostEvent extends InitPageEvent {

    /**
     *  <p> Constructor.</p>
     */
    public InitPostEvent(UIComponent src) {
        super(src);
    }
}
