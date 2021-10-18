package com.sun.jsft.event;

import jakarta.faces.component.UIComponent;
import jakarta.faces.event.ComponentSystemEvent;
import lombok.EqualsAndHashCode;

/**
 *  <p> This event is fired near the beginning of each request for a given
 *      page.  It occurs during the "Restore View" phase.  Special
 *      subclasses of this Event ({@link InitPostEvent} and
 *      {@link InitGetEvent}) will conditionally fire based on the request
 *      type.</p>
 */
@EqualsAndHashCode(callSuper = true)
public class InitPageEvent extends ComponentSystemEvent {
    /**
     *  <p> Constructor.</p>
     */
    public InitPageEvent(final UIComponent src) {
        super(src);
    }
}
