package negotiation.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * All information a strategy needs to make decisions in a given round.
 *
 * Passed to every strategy call. Strategies must NOT modify this object —
 * it is owned by the AutoNegotiationBehaviour and updated between rounds.
 */
public class NegotiationContext {

    // ── Fixed at negotiation start ────────────────────────────────────────────

    /** The other party's opening price (dealer's retail price or buyer's budget). */
    public double askingPrice;

    /** Our walk-away price — we will NEVER exceed this as buyer / go below this as dealer. */
    public double reservationPrice;

    /** Our opening bid — the most aggressive offer we'll start with. */
    public double firstOffer;

    /** Maximum number of rounds before we must accept or walk away. */
    public int maxRounds;

    /** Our role in this negotiation. */
    public Role role;

    // ── Updated each round ────────────────────────────────────────────────────

    public int currentRound;

    /** The opponent's price in the most recent message. */
    public double lastOpponentOffer;

    /** The price we sent in our most recent offer. */
    public double lastMyOffer;

    /** Full history of opponent's offers, in order (index 0 = first offer). */
    private final List<Double> opponentOffers = new ArrayList<>();

    /** Full history of our own offers, in order. */
    private final List<Double> myOffers = new ArrayList<>();

    // ── BATNA (for Extension 1: concurrent negotiations) ──────────────────────

    /**
     * Best Alternative To a Negotiated Agreement.
     * The best offer we currently have from OTHER dealers.
     * Double.MAX_VALUE means we have no other offer yet.
     *
     * A strategy should be more stubborn when BATNA is low (we have a good alternative).
     * See lecture slide 56: "If negotiating with more agents, less pressure to reach agreement"
     */
    public double batna = Double.MAX_VALUE;

    // ── Multi-attribute utility (for Extension 2) ─────────────────────────────

    /**
     * Optional: if set, the strategy can use utility() to evaluate offers
     * on multiple attributes (price, year, mileage, condition) rather than price alone.
     */
    public MultiAttributeUtility utilityFn;

    // ── Car details (for reference in reasoning) ──────────────────────────────

    public String carDescription;    // e.g. "2022 Toyota Camry"
    public String opponentName;      // e.g. "ToyotaDealer"
    public String negotiationId;

    // ─────────────────────────────────────────────────────────────────────────

    public enum Role { BUYER, DEALER }

    // ── History accessors ─────────────────────────────────────────────────────

    public void recordOpponentOffer(double price) {
        opponentOffers.add(price);
        lastOpponentOffer = price;
    }

    public void recordMyOffer(double price) {
        myOffers.add(price);
        lastMyOffer = price;
    }

    public List<Double> getOpponentOffers() { return Collections.unmodifiableList(opponentOffers); }
    public List<Double> getMyOffers()       { return Collections.unmodifiableList(myOffers); }

    /** How many rounds have opponent offers been recorded so far. */
    public int opponentOfferCount() { return opponentOffers.size(); }

    /** True if we are past the halfway point of our deadline. */
    public boolean isPastHalfway() { return currentRound >= maxRounds / 2; }

    /** True if this is the last round — accept or walk away. */
    public boolean isLastRound() { return currentRound >= maxRounds; }

    /** Fraction of deadline consumed: 0.0 = start, 1.0 = deadline reached. */
    public double timeProgress() {
        return maxRounds > 0 ? (double) currentRound / maxRounds : 1.0;
    }

    /**
     * Impasse detection — returns true when the last N rounds show almost no
     * movement from either party, meaning neither side will budge further.
     *
     * "Too long" = both parties' last 3 offers moved less than 0.5% each.
     * When detected, the agent should accept the current best offer or walk away.
     *
     * @param windowSize  how many recent rounds to look at (recommend 3)
     * @param threshold   minimum % movement to NOT be considered stuck (recommend 0.5)
     */
    public boolean isImpasse(int windowSize, double threshold) {
        // Need at least windowSize rounds of data from both sides
        if (opponentOffers.size() < windowSize || myOffers.size() < windowSize) return false;

        // Check opponent's movement in last windowSize rounds
        double opponentMovement = totalMovement(opponentOffers, windowSize);
        // Check our movement in last windowSize rounds
        double ourMovement = totalMovement(myOffers, windowSize);

        // If the first offer is 0, we can't calculate percentages
        double opponentBase = opponentOffers.get(0);
        double ourBase      = myOffers.get(0);
        if (opponentBase == 0 || ourBase == 0) return false;

        double opponentMovePct = (opponentMovement / opponentBase) * 100.0;
        double ourMovePct      = (ourMovement / ourBase) * 100.0;

        // Impasse: both sides barely moved
        return opponentMovePct < threshold && ourMovePct < threshold;
    }

    /** Convenience overload with sensible defaults: 3-round window, 0.5% threshold. */
    public boolean isImpasse() { return isImpasse(3, 0.5); }

    /** Sum of absolute movements in the last N entries of a list. */
    private double totalMovement(java.util.List<Double> offers, int window) {
        int start = offers.size() - window;
        double total = 0;
        for (int i = start; i < offers.size() - 1; i++) {
            total += Math.abs(offers.get(i) - offers.get(i + 1));
        }
        return total;
    }

    /** Whether BATNA is active (we have at least one other offer). */
    public boolean hasBATNA() { return batna < Double.MAX_VALUE; }
}