package negotiation.strategy;

/**
 * Tit-for-Tat Strategy.
 *
 * Directly implements the behaviour-dependent tactic from lecture slide 57:
 * "An agent cooperates if the opponent cooperates, and retaliates if they don't."
 *
 * In negotiation terms:
 *   - If the dealer dropped by 3%, we move up by 3%.
 *   - If the dealer barely moved (< 0.5%), we barely move too.
 *   - On the first round, we make a moderate opening concession to signal cooperation.
 *
 * Why this ends negotiation faster:
 *   - If both parties cooperate, concessions mirror each other and they converge.
 *   - Neither party feels they are being taken advantage of.
 *   - Based on slide 57: "TFT can reach successful agreement if it behaves cooperatively."
 */
public class TitForTatStrategy implements NegotiationStrategy {

    private double lastOpponentConcessionPct = 0.0;
    private double previousOpponentOffer     = 0.0;
    private NegotiationContext ctx;
    private String lastReasoning = "";

    // Minimum and maximum move we'll make in a single round (as % of gap)
    private static final double MIN_MOVE_PCT = 0.5;
    private static final double MAX_MOVE_PCT = 8.0;

    @Override public String getKey()         { return "TIT_FOR_TAT"; }
    @Override public String getDisplayName() { return "Tit-for-Tat"; }
    @Override public String getDescription() {
        return "Mirrors opponent's concession rate. Cooperative strategy that converges quickly.";
    }

    @Override
    public void initialise(NegotiationContext ctx) {
        this.ctx = ctx;
        this.previousOpponentOffer = ctx.askingPrice; // start from their opening position
        lastReasoning = "Tit-for-Tat initialised. Will mirror opponent's concession rate.";
    }

    @Override
    public void onOpponentOffer(double price, int round) {
        if (previousOpponentOffer > 0) {
            double concession = previousOpponentOffer - price;
            lastOpponentConcessionPct = (concession / previousOpponentOffer) * 100.0;
        }
        previousOpponentOffer = price;
    }

    @Override
    public double generateOffer(NegotiationContext ctx) {
        double lastMyOffer = ctx.lastMyOffer > 0 ? ctx.lastMyOffer : ctx.firstOffer;
        double gap         = ctx.reservationPrice - lastMyOffer;

        double movePct;
        if (ctx.currentRound == 1 || lastOpponentConcessionPct == 0) {
            // First round or no concession observed: make a cooperative opening move
            movePct = 2.0;
        } else {
            // Mirror the opponent's concession percentage
            movePct = lastOpponentConcessionPct;
        }

        // Clamp to sensible range
        movePct = Math.max(MIN_MOVE_PCT, Math.min(MAX_MOVE_PCT, movePct));

        double move  = gap * (movePct / 100.0);
        double offer = lastMyOffer + move;
        offer = Math.min(offer, ctx.reservationPrice);
        offer = Math.round(offer / 100.0) * 100.0;

        lastReasoning = String.format(
                "Round %d/%d | Opponent last conceded %.1f%% (RM%.0f) | Mirroring at %.1f%% → Offer RM%.0f",
                ctx.currentRound, ctx.maxRounds, lastOpponentConcessionPct,
                lastOpponentConcessionPct / 100.0 * previousOpponentOffer,
                movePct, offer);

        return offer;
    }

    @Override
    public boolean shouldAccept(double opponentOffer, NegotiationContext ctx) {
        boolean withinBudget = opponentOffer <= ctx.reservationPrice;
        boolean lastRound    = ctx.isLastRound();

        // Accept if within budget AND (deadline OR opponent barely moved = they're near their floor)
        boolean opponentStuck = lastOpponentConcessionPct < 0.3 && ctx.currentRound > 2;
        boolean accept = withinBudget && (lastRound || opponentStuck);

        lastReasoning = String.format(
                "Round %d/%d | OpponentConcession=%.1f%% | opponentStuck=%s | lastRound=%s → %s",
                ctx.currentRound, ctx.maxRounds, lastOpponentConcessionPct,
                opponentStuck, lastRound, accept ? "ACCEPT" : "COUNTER");

        return accept;
    }

    @Override
    public String getLastReasoning() { return lastReasoning; }
}