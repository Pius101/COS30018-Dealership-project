package negotiation.strategy;

/**
 * Fixed Increment Strategy — a custom strategy template.
 *
 * ─── FOR GROUPMATES: USE THIS AS A TEMPLATE ─────────────────────────────────
 *
 * This is the simplest possible custom strategy:
 * every round, increase the offer by a fixed % of the current offer.
 *
 * To add your OWN strategy:
 *   1. Copy this file and rename it (e.g. MyAggressiveStrategy.java)
 *   2. Change getKey(), getDisplayName(), getDescription()
 *   3. Implement generateOffer() and shouldAccept() with your logic
 *   4. Register it in StrategyRegistry.java:
 *        register("MY_STRATEGY", MyAggressiveStrategy::new);
 *   5. Done — it appears in the GUI dropdown automatically
 *
 * ─── What this strategy does ─────────────────────────────────────────────────
 *
 *   Round 1: offer = firstOffer
 *   Round 2: offer = firstOffer × (1 + incrementPct/100)
 *   Round 3: offer = Round2 offer × (1 + incrementPct/100)
 *   ...and so on until we hit the reservation price or the deadline.
 *
 * Variations your groupmate could try:
 *   - Increase by a fixed AMOUNT (RM 500 per round) instead of a percentage
 *   - Increase by more early on and less later (reverse Conceder)
 *   - Increase only if the opponent also moved (conditional increment)
 */
public class FixedIncrementStrategy implements NegotiationStrategy {

    /** How much to increase the offer each round, as a percentage. e.g. 2.0 = 2% per round. */
    private final double incrementPct;

    private NegotiationContext ctx;
    private String lastReasoning = "";
    private double currentOffer;

    /**
     * Create a fixed increment strategy.
     *
     * @param incrementPct  percentage increase per round (e.g. 2.0 for 2%)
     */
    public FixedIncrementStrategy(double incrementPct) {
        this.incrementPct = incrementPct;
    }

    @Override public String getKey()         { return "FIXED_INCREMENT"; }
    @Override public String getDisplayName() { return String.format("Fixed Increment (%.0f%% per round)", incrementPct); }
    @Override public String getDescription() {
        return String.format("Increases offer by %.1f%% each round until reaching reservation price.", incrementPct);
    }

    @Override
    public void initialise(NegotiationContext ctx) {
        this.ctx           = ctx;
        this.currentOffer  = ctx.firstOffer;
        lastReasoning = String.format(
                "Fixed Increment initialised | Increment=%.1f%% per round | Start=RM%.0f | RV=RM%.0f",
                incrementPct, ctx.firstOffer, ctx.reservationPrice);
    }

    // This strategy doesn't adapt to the opponent — increment is always fixed
    @Override
    public void onOpponentOffer(double price, int round) { /* stateless */ }

    @Override
    public double generateOffer(NegotiationContext ctx) {
        // Increase current offer by the fixed percentage
        currentOffer = currentOffer * (1.0 + incrementPct / 100.0);

        // Never exceed reservation price
        currentOffer = Math.min(currentOffer, ctx.reservationPrice);

        // Round to nearest RM 100
        double offer = Math.round(currentOffer / 100.0) * 100.0;

        lastReasoning = String.format(
                "Round %d/%d | +%.1f%% increment | Offer RM%.0f → RM%.0f | RV ceiling: RM%.0f",
                ctx.currentRound, ctx.maxRounds, incrementPct,
                ctx.lastMyOffer, offer, ctx.reservationPrice);

        return offer;
    }

    @Override
    public boolean shouldAccept(double opponentOffer, NegotiationContext ctx) {
        boolean withinBudget = opponentOffer <= ctx.reservationPrice;
        boolean lastRound    = ctx.isLastRound();

        // Simple rule: accept if within budget and at deadline
        boolean accept = withinBudget && lastRound;

        lastReasoning = String.format(
                "Round %d/%d | Offer RM%.0f vs RV RM%.0f | lastRound=%s → %s",
                ctx.currentRound, ctx.maxRounds, opponentOffer,
                ctx.reservationPrice, lastRound, accept ? "ACCEPT" : "COUNTER");

        return accept;
    }

    @Override
    public String getLastReasoning() { return lastReasoning; }

    // ─────────────────────────────────────────────────────────────────────────
    // Example variations your groupmates could implement:
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Alternative: fixed AMOUNT per round instead of percentage.
     * Example usage:
     *   StrategyRegistry.register("FIXED_RM500", () -> new FixedAmountVariant(500));
     */
    public static class FixedAmountVariant implements NegotiationStrategy {
        private final double amountPerRound;
        private NegotiationContext ctx;
        private String lastReasoning = "";

        public FixedAmountVariant(double amountPerRound) {
            this.amountPerRound = amountPerRound;
        }

        @Override public String getKey()         { return "FIXED_AMOUNT"; }
        @Override public String getDisplayName() { return String.format("Fixed Amount (RM%.0f/round)", amountPerRound); }
        @Override public String getDescription() { return String.format("Increases offer by RM%.0f each round.", amountPerRound); }
        @Override public void initialise(NegotiationContext ctx) { this.ctx = ctx; }
        @Override public void onOpponentOffer(double price, int round) {}

        @Override
        public double generateOffer(NegotiationContext ctx) {
            double base  = ctx.lastMyOffer > 0 ? ctx.lastMyOffer : ctx.firstOffer;
            double offer = Math.min(base + amountPerRound, ctx.reservationPrice);
            offer = Math.round(offer / 100.0) * 100.0;
            lastReasoning = String.format("Round %d | +RM%.0f → Offer RM%.0f", ctx.currentRound, amountPerRound, offer);
            return offer;
        }

        @Override
        public boolean shouldAccept(double opponentOffer, NegotiationContext ctx) {
            return opponentOffer <= ctx.reservationPrice && ctx.isLastRound();
        }

        @Override public String getLastReasoning() { return lastReasoning; }
    }
}