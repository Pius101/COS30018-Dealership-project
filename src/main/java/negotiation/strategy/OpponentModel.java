package negotiation.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks and learns the opponent's negotiation behaviour.
 *
 * This is the "learning" component that the lecturer asked for.
 * Based on lecture slide 60: Bayesian Reasoning for negotiation.
 *
 * What it learns:
 *   - How much the opponent concedes each round (concession rate)
 *   - Whether they are Boulware (stubborn), Linear, or Conceder (flexible)
 *   - An estimated reservation value (their walk-away / floor price)
 *
 * How the Bayesian estimate works:
 *   We model the opponent's remaining concessions as a geometric series.
 *   After each observed concession, we update our belief about their floor.
 *   The smaller their concessions become, the closer we believe they are to their limit.
 */
public class OpponentModel {

    /** How the opponent is behaving based on observed concessions. */
    public enum Style {
        UNKNOWN,    // not enough data yet (< 2 offers seen)
        BOULWARE,   // concessions shrinking — opponent is near their limit
        LINEAR,     // concessions are roughly constant — steady movement
        CONCEDER    // concessions growing — opponent is flexible, has more room
    }

    private final List<Double>  observedOffers     = new ArrayList<>();
    private final List<Double>  concessions        = new ArrayList<>(); // positive = moved toward us
    private       double        estimatedRV        = 0.0;
    private       Style         detectedStyle      = Style.UNKNOWN;
    private       double        avgConcessionRate  = 0.0; // as % of first offer

    // ─────────────────────────────────────────────────────────────────────────
    // Update — called every time the opponent sends a new offer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Record a new opponent offer and update all estimates.
     *
     * @param newOffer  the price the opponent just offered
     */
    public void update(double newOffer) {
        if (!observedOffers.isEmpty()) {
            double prev = observedOffers.get(observedOffers.size() - 1);
            double concession = prev - newOffer;  // positive = they moved toward us (as dealer dropping price)
            concessions.add(concession);
            updateStyle();
            updateEstimatedRV(newOffer);
        }
        observedOffers.add(newOffer);
    }

    // ── Style detection ───────────────────────────────────────────────────────

    private void updateStyle() {
        if (concessions.size() < 2) {
            detectedStyle = Style.UNKNOWN;
            return;
        }

        // Compare average of first half vs average of second half of concessions
        int mid   = concessions.size() / 2;
        double firstHalfAvg  = concessions.subList(0, mid).stream()
                .mapToDouble(d -> d).average().orElse(0);
        double secondHalfAvg = concessions.subList(mid, concessions.size()).stream()
                .mapToDouble(d -> d).average().orElse(0);

        if (firstHalfAvg <= 0) { detectedStyle = Style.UNKNOWN; return; }

        double trend = secondHalfAvg / firstHalfAvg; // < 1 = shrinking, > 1 = growing

        if (trend < 0.6)       detectedStyle = Style.BOULWARE;   // concessions shrinking fast
        else if (trend > 1.4)  detectedStyle = Style.CONCEDER;   // concessions growing
        else                   detectedStyle = Style.LINEAR;

        // Average concession rate as % of first offer
        if (!observedOffers.isEmpty() && observedOffers.get(0) > 0) {
            double totalConcession = observedOffers.get(0) - observedOffers.get(observedOffers.size() - 1);
            avgConcessionRate = totalConcession / observedOffers.get(0) * 100.0;
        }
    }

    // ── Reservation value estimation ──────────────────────────────────────────

    /**
     * Bayesian update of the estimated reservation value.
     *
     * Intuition: the opponent's RV is somewhere below their current offer.
     * If their concessions are shrinking, they are close to their RV.
     * If concessions are large and steady, they have more room to go.
     *
     * We model expected remaining concessions as:
     *   remaining = avgConcession × decayFactor × expectedRemainingRounds
     *
     * And subtract that from the current offer to estimate RV.
     */
    private void updateEstimatedRV(double currentOffer) {
        if (concessions.isEmpty()) {
            estimatedRV = currentOffer * 0.85; // naive prior: 15% below asking
            return;
        }

        double avgConcession = concessions.stream().mapToDouble(d -> d).average().orElse(0);

        // Decay factor: Boulware opponents have low decay (they stop conceding soon)
        double decayFactor = switch (detectedStyle) {
            case BOULWARE -> 0.3;   // they have little room left
            case LINEAR   -> 0.6;   // moderate room
            case CONCEDER -> 1.0;   // they still have room to go
            case UNKNOWN  -> 0.5;   // neutral prior
        };

        // Assume about 2 more rounds of concessions at current rate
        double expectedRemainingConcession = avgConcession * decayFactor * 2.0;
        estimatedRV = currentOffer - expectedRemainingConcession;
        estimatedRV = Math.max(0, estimatedRV); // can't be negative
    }

    // ── Prediction ────────────────────────────────────────────────────────────

    /**
     * Predict what the opponent will offer in the next round.
     * Based on their detected style and average concession.
     */
    public double predictNextOffer() {
        if (observedOffers.isEmpty()) return 0;
        double current = observedOffers.get(observedOffers.size() - 1);
        if (concessions.isEmpty()) return current;

        double avgConcession = concessions.stream().mapToDouble(d -> d).average().orElse(0);
        double decay = switch (detectedStyle) {
            case BOULWARE -> 0.4;
            case LINEAR   -> 0.9;
            case CONCEDER -> 1.1;
            case UNKNOWN  -> 0.7;
        };
        return current - (avgConcession * decay);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Style  getDetectedStyle()       { return detectedStyle; }
    public double getEstimatedRV()         { return estimatedRV; }
    public double getAvgConcessionRate()   { return avgConcessionRate; }
    public int    getOfferCount()          { return observedOffers.size(); }

    public double getLatestOffer() {
        return observedOffers.isEmpty() ? 0
                : observedOffers.get(observedOffers.size() - 1);
    }

    public double getLatestConcession() {
        return concessions.isEmpty() ? 0
                : concessions.get(concessions.size() - 1);
    }

    /** Summary string for logging and report. */
    public String summary() {
        return String.format(
                "Style=%s | AvgConcession=RM%.0f (%.1f%%) | EstimatedFloor≈RM%.0f | Offers seen=%d",
                detectedStyle,
                concessions.stream().mapToDouble(d -> d).average().orElse(0),
                avgConcessionRate,
                estimatedRV,
                observedOffers.size());
    }
}