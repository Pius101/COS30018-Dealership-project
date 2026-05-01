package negotiation.strategy;

/**
 * Time-Dependent Strategy — Boulware and Conceder curves.
 *
 * Directly implements the time-dependent tactics from lecture slide 55.
 *
 * CONCEDER: makes large concessions early, small ones near deadline.
 *   Good when: you want to signal cooperation and reach a deal quickly.
 *
 * BOULWARE: makes small concessions early, only moves significantly near deadline.
 *   Good when: you believe the opponent will keep conceding without pressure.
 *
 * The β parameter controls the curve shape:
 *   β < 1 → Boulware  (concave curve)
 *   β = 1 → Linear
 *   β > 1 → Conceder  (convex curve)
 */
public class TimeDependentStrategy implements NegotiationStrategy {

    public enum Curve { BOULWARE, LINEAR, CONCEDER }

    private final Curve curve;
    private final double beta;

    private NegotiationContext ctx;
    private String lastReasoning = "";

    public TimeDependentStrategy(Curve curve) {
        this.curve = curve;
        this.beta  = switch (curve) {
            case BOULWARE -> 0.25;  // stubborn until near deadline
            case LINEAR   -> 1.0;   // constant concession rate
            case CONCEDER -> 4.0;   // concedes quickly early on
        };
    }

    @Override public String getKey()         { return "TIME_DEPENDENT_" + curve.name(); }
    @Override public String getDisplayName() { return "Time-Dependent (" + curve.name() + ")"; }
    @Override public String getDescription() {
        return switch (curve) {
            case BOULWARE -> "Stubborn early, large move near deadline (β=0.25).";
            case LINEAR   -> "Constant concession rate throughout negotiation (β=1.0).";
            case CONCEDER -> "Concedes quickly early, stabilises near deadline (β=4.0).";
        };
    }

    @Override
    public void initialise(NegotiationContext ctx) {
        this.ctx = ctx;
        lastReasoning = String.format("%s initialised | β=%.2f | RV=RM%.0f | First offer=RM%.0f",
                curve, beta, ctx.reservationPrice, ctx.firstOffer);
    }

    // Time-dependent strategy doesn't learn — opponent offers don't change β
    @Override
    public void onOpponentOffer(double price, int round) { /* stateless */ }

    @Override
    public double generateOffer(NegotiationContext ctx) {
        double t   = ctx.currentRound;
        double T   = ctx.maxRounds;
        double P0  = ctx.firstOffer;
        double RV  = ctx.reservationPrice;

        // Concession formula from lecture slide 55
        double fraction = Math.pow(t / Math.max(T, 1), 1.0 / beta);
        double offer    = P0 + (RV - P0) * fraction;

        // Clamp: ensure offer is between reservation and first offer
        // For buyer: P0 < RV, so max keeps it above P0, min keeps it below RV
        // For dealer: P0 > RV, so max keeps it above RV, min keeps it below P0
        offer = Math.max(offer, Math.min(P0, RV));
        offer = Math.min(offer, Math.max(P0, RV));
        offer = Math.round(offer / 100.0) * 100.0;

        lastReasoning = String.format(
                "Round %d/%d | Curve=%s β=%.2f | Progress=%.0f%% → Offer RM%.0f",
                ctx.currentRound, ctx.maxRounds, curve, beta,
                ctx.timeProgress() * 100, offer);

        return offer;
    }

    @Override
    public boolean shouldAccept(double opponentOffer, NegotiationContext ctx) {
        boolean withinBudget = opponentOffer <= ctx.reservationPrice;
        boolean lastRound    = ctx.isLastRound();
        boolean accept = withinBudget && lastRound;

        // Also accept if within 1% of reservation value (very close to our limit)
        if (withinBudget && opponentOffer >= ctx.reservationPrice * 0.99) accept = true;

        lastReasoning = String.format(
                "Round %d/%d | %s | Offer RM%.0f vs RV RM%.0f | lastRound=%s → %s",
                ctx.currentRound, ctx.maxRounds, curve, opponentOffer,
                ctx.reservationPrice, lastRound, accept ? "ACCEPT" : "COUNTER");

        return accept;
    }

    @Override
    public String getLastReasoning() { return lastReasoning; }
}