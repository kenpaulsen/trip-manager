package org.paulsens.trip.api.dto;

import java.util.List;

/**
 * A person's profile pictures: THE picture ({@code url}, the selected slot or the lowest occupied one) and
 * every occupied slot. Every photo endpoint answers this same shape so a client replaces its state with the
 * response rather than patching it; {@code storedSlot} is set only by an upload, naming where it landed.
 */
public record ProfilePhotoDto(
        boolean hasPhoto,
        String url,
        int selectedSlot,
        List<SlotDto> slots,
        Integer storedSlot) {

    public record SlotDto(int slot, String url) {
    }
}
