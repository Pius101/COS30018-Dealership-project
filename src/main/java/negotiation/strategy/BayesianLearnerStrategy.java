package negotiation.strategy;

/**
 * Bayesian Learner Strategy.
 *
 * Learns the opponent's negotiation style and reservation value from their
 * offer history, then adapts concessions to end the negotiation faster.
 *
 * Based on:
 *   - Lecture slide 55: Time-dependent concession formula
 *   - Lecture slide 57: Behaviour-dependent tactics
 *   - Lecture slide 60: Bayesian Reasoning in negotiation
 *
 * Key behaviour:
 *   - Maintains an OpponentModel that tracks dealer concessions
 *   - Adjusts the β parameter (from slide 55) based on detected dealer style:
 *       Dealer is Boulware → β decreases → buyer increases offers faster (more aggressive)
 *       Dealer is Conceder → β increases → buyer holds back (let them come to us)
 *   - Accepts when offer is near estimated dealer floor OR deadline is close
 *   - Uses multi-attribute utility for acceptance if available
 */
public class BayesianLearnerStrategy implements NegotiationStrategy {

    private final OpponentModel model = new OpponentModel();

    // β controls the shape of the concession curve (from lecture slide 55):
    //   β < 1 → Boulware (slow at first, big jump at deadline)
    //   β = 1 → Linear (constant concession rate)
    //   β > 1 → Conceder (fast concessions early, slow near deadline)
    private double beta = 1.0;

    private NegotiationContext ctx;
    private String lastReasoning = "Waiting for opponent's first offer.";

    // ─────────────────────────────────────────────────────────────────────────

    @Override public String getKey()         { return "BAYESIAN"; }
    @Override public String getDisplayName() { return "Bayesian Learner"; }
    @Override public String getDescription() {
        return "Learns the opponent's concession style and adapts to end negotiation faster.";
    }

    @Override
    public void initialise(NegotiationContext ctx) {
        this.ctx   = ctx;
        this.beta  = 1.0;  // start neutral
        lastReasoning = String.format(
                "Initialised | Asking: RM %.0f | My RV: RM %.0f | First offer: RM %.0f | Deadline: %d rounds",
                ctx.askingPrice, ctx.reservationPrice, ctx.firstOffer, ctx.maxRounds);
    }

    @Override
    public void onOpponentOffer(double price, int round) {
        model.update(price);

        // Adjust β based on what we just learned about the opponent
        switch (model.getDetectedStyle()) {
            case BOULWARE -> {
                // Opponent is stubborn — we need to move more aggressively
                // Otherwise we'll never meet. Reduce β so our curve steepens.
                beta = Math.max(0.3, beta - 0.15);
            }
            case CONCEDER -> {
                // Opponent is flexible — hold back, let them come to us
                // Increase β so we concede slowly
                beta = Math.min(3.0, beta + 0.15);
            }
            case LINEAR -> {
                // Steady opponent — match their pace roughly
                beta = 1.0;
            }
            case UNKNOWN -> {
                // First offer — no data yet, stay neutral
            }
        }
    }

    @Override
    public double generateOffer(NegotiationContext ctx) {
        double t  = ctx.currentRound;
        double T  = ctx.maxRounds;
        double P0 = ctx.firstOffer;         // our opening bid (lowest we start with)
        double RV = ctx.reservationPrice;   // our walk-away (highest we'll pay)

        // Time-dependent concession formula from lecture slide 55:
        // offer(t) = P0 + (RV - P0) × (t/T)^(1/β)
        // As t → T, offer → RV (we move to our reservation price by the deadline)
        double fraction = Math.pow(t / Math.max(T, 1), 1.0 / Math.max(beta, 0.01));
        double offer    = P0 + (RV - P0) * fraction;

        // BATNA adjustment: if we have a better deal elsewhere, be more stubborn
        if (ctx.hasBATNA() && ctx.batna < ctx.lastOpponentOffer) {
            // Our BATNA is better than the current offer — reduce offer by 1%
            offer = Math.max(P0, offer * 0.99);
        }

        // Clamp to valid range
        offer = Math.min(offer, RV);
        offer = Math.max(offer, P0);

        // Round to nearest RM 100
        offer = Math.round(offer / 100.0) * 100.0;

        lastReasoning = buildReasoning(ctx, offer, false);
        return offer;
    }

    @Override
    public boolean shouldAccept(double opponentOffer, NegotiationContext ctx) {
        // Condition 1: Opponent's offer is within budget
        boolean withinBudget = opponentOffer <= ctx.reservationPrice;
        if (!withinBudget) {
            lastReasoning = String.format(
                    "REJECT: Offer RM %.0f exceeds reservation price RM %.0f",
                    opponentOffer, ctx.reservationPrice);
            return false;
        }

        // Condition 2: Near our estimated opponent floor (they can't go lower)
        double estFloor = model.getEstimatedRV();
        boolean nearFloor = estFloor > 0 && opponentOffer <= estFloor * 1.03;

        // Condition 3: Deadline pressure
        boolean lastRound = ctx.isLastRound();

        // Condition 4: Multi-attribute utility threshold (Extension 2)
        boolean goodUtility = false;
        if (ctx.utilityFn != null) {
            double u = ctx.utilityFn.evaluate(opponentOffer);
            goodUtility = u >= ctx.utilityFn.getAcceptanceThreshold();
        }

        // Condition 5: BATNA — don't accept if we have a better deal elsewhere
        boolean betterElsewhere = ctx.hasBATNA() && ctx.batna < opponentOffer;

        boolean accept = withinBudget && !betterElsewhere
                && (lastRound || nearFloor || goodUtility);

        lastReasoning = buildReasoning(ctx, opponentOffer, accept);
        return accept;
    }

    @Override
    public String getLastReasoning() { return lastReasoning; }

    // ─────────────────────────────────────────────────────────────────────────

    private String buildReasoning(NegotiationContext ctx, double price, boolean accepting) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Round %d/%d", ctx.currentRound, ctx.maxRounds));
        sb.append(String.format(" | β=%.2f", beta));
        sb.append(String.format(" | %s", model.summary()));

        if (ctx.hasBATNA()) {
            sb.append(String.format(" | BATNA=RM%.0f", ctx.batna));
        }

        if (ctx.utilityFn != null) {
            sb.append(String.format(" | Utility=%.2f", ctx.utilityFn.evaluate(price)));
        }

        if (accepting) {
            sb.append(String.format(" → ACCEPT RM%.0f", price));
        } else {
            sb.append(String.format(" → OFFER RM%.0f", price));
        }
        return sb.toString();
    }
}