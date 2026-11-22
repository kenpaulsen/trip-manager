package org.paulsens.trip.action;

import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.HtmlFragmentValidator;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;

/**
 * The offer dialog's refusals: what {@link LodgingCommands#saveOffer} checks before it writes anything, kept
 * out of that bean for its size alone. Pure questions about one option and its form; the bean decides who may
 * ask and what happens after.
 */
final class OfferRules {

    private OfferRules() {
    }

    /** Why the option cannot be saved as the form says; null when it can. */
    static String problem(final String tripId, final ReservationOffer offer, final OfferForm form) {
        // A linked option was added FROM a plan of one hotel; the plan's cost is that hotel's quote.
        if (offer.isLinked() && offer.getAccommodationId() != null
                && !offer.getAccommodationId().getValue().equals(form.getAccommodationId())) {
            return Text.get("feedback.offerRules.linkedStaysAtHotel");
        }
        if (offer.isPriced() && !form.isPriceKnown()) {
            final long stays = activeStaysOn(tripId, offer);
            if (stays > 0) {
                return Text.get("feedback.offerRules.hasActiveStays", offer.getName(), stays);
            }
        }
        if (form.getValidFrom() != null && form.getValidUntil() != null
                && !form.getValidUntil().isAfter(form.getValidFrom())) {
            return Text.get("feedback.offerRules.lastAfterFirst");
        }
        if (form.getDefaultStart() == null || form.getDefaultEnd() == null
                || !form.getDefaultEnd().isAfter(form.getDefaultStart())) {
            return Text.get("feedback.offerRules.departureAfterArrival");
        }
        if ((form.getValidFrom() != null && form.getDefaultStart().isBefore(form.getValidFrom()))
                || (form.getValidUntil() != null && form.getDefaultEnd().isAfter(form.getValidUntil()))) {
            return Text.get("feedback.offerRules.stayWithinRange");
        }
        if (form.getCancelFeeAmount() != null && form.getCancelFeeAmount() < 0) {
            return Text.get("feedback.offerRules.feeNegative");
        }
        if ("PERCENT".equals(form.getCancelFeeKind()) && form.getCancelFeeAmount() != null
                && form.getCancelFeeAmount() > 100) {
            return Text.get("feedback.offerRules.feeOver100");
        }
        final String money = OfferSchedules.problem(form);
        if (money != null) {
            return money;
        }
        final String policy = HtmlFragmentValidator.validate(LodgingCommands.nullSafe(form.getPolicyHtml()));
        return (policy == null) ? null : Text.get("feedback.offerRules.policyProblem", policy);
    }

    private static long activeStaysOn(final String tripId, final ReservationOffer offer) {
        long stays = 0L;
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
            if (res.isActive() && offer.getId().equals(res.getOfferId())) {
                stays++;
            }
        }
        return stays;
    }
}
