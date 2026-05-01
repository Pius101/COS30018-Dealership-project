package negotiation.strategy;

/**
 * Multi-Attribute Utility Function — Extension 2.
 *
 * Evaluates offers on multiple dimensions, not just price.
 * Directly implements the formula from lecture slide 48.
 *
 * U_total = Σ (weight_i × u_i(value_i))
 *
 * For V2 (single attribute): only price weight is non-zero.
 * For Extension 2: year, mileage, condition weights are also set.
 *
 * Attribute utility functions (u_i) normalise each attribute to [0, 1]:
 *   u_price(p)     → 1.0 at first offer, 0.0 at reservation price
 *   u_year(y)      → 1.0 for 2023, 0.0 for 2017
 *   u_mileage(km)  → 1.0 for 0 km, 0.0 for 120,000+ km
 *   u_condition(c) → 1.0 for New, 0.65 for Used
 *   u_warranty(m)  → 1.0 for 60 months, 0.0 for no warranty (Extension 2)
 */
public class MultiAttributeUtility {

    // Weights — must sum to 1.0
    private double weightPrice;
    private double weightYear;
    private double weightMileage;
    private double weightCondition;
    private double weightWarranty;   // only used in Extension 2

    // Reference prices for the price utility function
    private double firstOffer;
    private double reservationPrice;

    // Accept if overall utility >= this threshold
    private double acceptanceThreshold = 0.65;

    // ── Preset configurations ─────────────────────────────────────────────────

    /** V2 preset: only price matters. */
    public static MultiAttributeUtility priceOnly(double firstOffer, double reservationPrice) {
        MultiAttributeUtility u = new MultiAttributeUtility();
        u.weightPrice     = 1.0;
        u.weightYear      = 0.0;
        u.weightMileage   = 0.0;
        u.weightCondition = 0.0;
        u.weightWarranty  = 0.0;
        u.firstOffer      = firstOffer;
        u.reservationPrice = reservationPrice;
        return u;
    }

    /** Extension 2 preset: balanced weights across price, year, mileage, condition. */
    public static MultiAttributeUtility balanced(double firstOffer, double reservationPrice) {
        MultiAttributeUtility u = new MultiAttributeUtility();
        u.weightPrice     = 0.55;
        u.weightYear      = 0.20;
        u.weightMileage   = 0.15;
        u.weightCondition = 0.10;
        u.weightWarranty  = 0.0;
        u.firstOffer      = firstOffer;
        u.reservationPrice = reservationPrice;
        return u;
    }

    /** Extension 2 preset: price-sensitive buyer. */
    public static MultiAttributeUtility priceSensitive(double firstOffer, double reservationPrice) {
        MultiAttributeUtility u = new MultiAttributeUtility();
        u.weightPrice     = 0.75;
        u.weightYear      = 0.10;
        u.weightMileage   = 0.10;
        u.weightCondition = 0.05;
        u.weightWarranty  = 0.0;
        u.firstOffer      = firstOffer;
        u.reservationPrice = reservationPrice;
        return u;
    }

    // ── Evaluation ────────────────────────────────────────────────────────────

    /**
     * Evaluate utility for a price-only offer.
     * Used in V2 automated negotiation.
     */
    public double evaluate(double offeredPrice) {
        return evaluate(offeredPrice, 2020, 30000, "Used", 0);
    }

    /**
     * Full multi-attribute evaluation.
     * Used in Extension 2 when car attributes are also part of the offer.
     *
     * @param offeredPrice  negotiated price in RM
     * @param year          car model year
     * @param mileageKm     odometer reading in km
     * @param condition     "New" or "Used"
     * @param warrantyMonths  warranty months being offered (0 = none)
     */
    public double evaluate(double offeredPrice, int year, int mileageKm,
                           String condition, int warrantyMonths) {
        double uPrice     = uPrice(offeredPrice);
        double uYear      = uYear(year);
        double uMileage   = uMileage(mileageKm);
        double uCondition = uCondition(condition);
        double uWarranty  = uWarranty(warrantyMonths);

        double total = weightPrice    * uPrice
                + weightYear     * uYear
                + weightMileage  * uMileage
                + weightCondition * uCondition
                + weightWarranty * uWarranty;

        // Normalise if weights don't sum to exactly 1.0
        double weightSum = weightPrice + weightYear + weightMileage + weightCondition + weightWarranty;
        if (weightSum > 0) total /= weightSum;

        return Math.max(0.0, Math.min(1.0, total));
    }

    // ── Individual attribute utility functions ────────────────────────────────

    /** u_price: 1.0 at first offer, 0.0 at reservation price, linear in between. */
    private double uPrice(double price) {
        if (price <= firstOffer)       return 1.0;
        if (price >= reservationPrice) return 0.0;
        return (reservationPrice - price) / (reservationPrice - firstOffer);
    }

    /** u_year: 2023 = 1.0, 2017 = 0.0, linear scale over 7 years. */
    private double uYear(int year) {
        return Math.max(0.0, Math.min(1.0, (year - 2017) / 6.0));
    }

    /** u_mileage: 0 km = 1.0, 120,000 km = 0.0, linear. */
    private double uMileage(int km) {
        if (km <= 0) return 1.0;
        return Math.max(0.0, 1.0 - (km / 120_000.0));
    }

    /** u_condition: New = 1.0, Used = 0.65. */
    private double uCondition(String condition) {
        return "New".equalsIgnoreCase(condition) ? 1.0 : 0.65;
    }

    /** u_warranty: 0 months = 0.0, 60 months = 1.0. */
    private double uWarranty(int months) {
        return Math.min(1.0, months / 60.0);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public double getAcceptanceThreshold()              { return acceptanceThreshold; }
    public void   setAcceptanceThreshold(double v)      { this.acceptanceThreshold = v; }
    public void   setFirstOffer(double v)               { this.firstOffer = v; }
    public void   setReservationPrice(double v)         { this.reservationPrice = v; }

    public String weightsDescription() {
        return String.format("Price=%.0f%% Year=%.0f%% Mileage=%.0f%% Condition=%.0f%% Warranty=%.0f%%",
                weightPrice*100, weightYear*100, weightMileage*100, weightCondition*100, weightWarranty*100);
    }
}