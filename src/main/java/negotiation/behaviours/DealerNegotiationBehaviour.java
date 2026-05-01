package negotiation.behaviours;

import jade.core.behaviours.CyclicBehaviour;
import negotiation.agents.DealerAgent;
import negotiation.models.Assignment;
import negotiation.models.NegotiationMessage;
import negotiation.strategy.*;

import java.util.Queue;

/**
 * DealerNegotiationBehaviour — drives automated dealer negotiation.
 *
 * Mirrors AutoNegotiationBehaviour but from the dealer's perspective:
 *   - Dealer starts at the listing's retail price (askingPrice)
 *   - Dealer concedes DOWNWARD toward their reservation price (minimum acceptable)
 *   - Dealer accepts when the buyer's offer meets or exceeds the dealer's current offer
 *
 * The dealer WAITS for the buyer's first offer before responding.
 * (In a real negotiation, buyers approach dealers, not the other way around.)
 *
 * Impasse detection: if neither side moves >0.5% for 3 rounds, the dealer
 * either accepts the buyer's current offer (if above reservation) or rejects.
 *
 * Strategy note:
 *   The strategy interface is the same — but for a dealer, "generating an offer"
 *   means generating a price to come DOWN to, not up. The context is set up
 *   so firstOffer = asking price and reservationPrice = dealer's minimum,
 *   and the strategy curve runs from asking price DOWN to the minimum.
 *   The NegotiationContext.Role is set to DEALER so strategies can adapt if needed.
 */
public class DealerNegotiationBehaviour extends CyclicBehaviour {

    private final DealerAgent         dealer;
    private final String              negotiationId;
    private final NegotiationStrategy strategy;
    private final NegotiationContext  ctx;
    private final Queue<NegotiationMessage> queue;

    private int     round  = 0;
    private boolean active = true;

    private static final long POLL_INTERVAL_MS = 300;

    // ─────────────────────────────────────────────────────────────────────────

    public DealerNegotiationBehaviour(DealerAgent dealer,
                                      Assignment assignment,
                                      AutoDealerParams params,
                                      Queue<NegotiationMessage> queue) {
        super(dealer);
        this.dealer        = dealer;
        this.negotiationId = assignment.getNegotiationId();
        this.queue         = queue;

        // Build context — dealer perspective
        // firstOffer     = asking/retail price  (dealer starts here)
        // reservationPrice = dealer's minimum   (won't go below this)
        this.ctx = new NegotiationContext();
        ctx.negotiationId    = negotiationId;
        ctx.role             = NegotiationContext.Role.DEALER;
        ctx.askingPrice      = assignment.getListing().getRetailPrice();
        ctx.firstOffer       = ctx.askingPrice; // dealer starts at retail price
        ctx.reservationPrice = params.reservationPrice > 0
                ? params.reservationPrice
                : ctx.askingPrice * 0.88; // default: won't go below 88% of retail
        ctx.maxRounds        = params.maxRounds > 0 ? params.maxRounds : 10;
        ctx.carDescription   = assignment.getListing().toString();
        ctx.opponentName     = assignment.getBuyerName();
        ctx.lastMyOffer      = ctx.firstOffer;

        // Instantiate strategy
        NegotiationStrategy s;
        try {
            s = StrategyRegistry.create(
                    params.strategyKey != null ? params.strategyKey : "TIME_DEPENDENT_BOULWARE");
        } catch (IllegalArgumentException e) {
            dealer.log.error("[AUTO-DEALER] Unknown strategy '" + params.strategyKey
                    + "' — falling back to TIME_DEPENDENT_BOULWARE");
            s = new TimeDependentStrategy(TimeDependentStrategy.Curve.BOULWARE);
        }
        this.strategy = s;
        this.strategy.initialise(ctx);

        dealer.log.info("[AUTO-DEALER] Negotiation [" + negotiationId + "] ready"
                + " | Strategy: " + strategy.getDisplayName()
                + " | Starting price: RM " + String.format("%.0f", ctx.firstOffer)
                + " | Minimum: RM " + String.format("%.0f", ctx.reservationPrice)
                + " | Max rounds: " + ctx.maxRounds);

        // Update strategy info in the conversation log (dealer side now known)
        // We call this again to overwrite the "Unknown" dealer strategy set by buyer
        negotiation.util.ConversationLogger.logStrategyInfo(
                negotiationId,
                "Buyer strategy (see buyer log)",
                strategy.getDisplayName(),
                ctx.firstOffer,
                ctx.reservationPrice,
                ctx.maxRounds);

        // Show auto banner in dealer GUI
        if (dealer.getGui() != null) {
            javax.swing.SwingUtilities.invokeLater(() ->
                    dealer.getGui().showAutoModeBanner(negotiationId,
                            strategy.getDisplayName(),
                            ctx.firstOffer,
                            ctx.reservationPrice,
                            ctx.maxRounds));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JADE Behaviour loop
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void action() {
        if (!active) { block(); return; }

        NegotiationMessage incoming = queue.poll();
        if (incoming == null) {
            block(POLL_INTERVAL_MS);
            return;
        }

        handleIncoming(incoming);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handlers
    // ─────────────────────────────────────────────────────────────────────────

    private void handleIncoming(NegotiationMessage incoming) {

        // Buyer ended the negotiation
        if (incoming.getType() == NegotiationMessage.Type.REJECT) {
            dealer.log.info("[AUTO-DEALER] Buyer rejected the negotiation.");
            appendReasoning("Buyer ended the negotiation — no deal.");
            active = false;
            return;
        }

        round++;
        ctx.currentRound = round;

        // For dealer: opponent offers are buyer's bids (moving upward)
        ctx.recordOpponentOffer(incoming.getPrice());
        strategy.onOpponentOffer(incoming.getPrice(), round);

        dealer.log.info("[AUTO-DEALER] Round " + round + " | Buyer offered RM "
                + String.format("%.0f", incoming.getPrice()));

        // ── Decision point ────────────────────────────────────────────────────

        // Buyer's offer meets or exceeds our current asking — accept immediately
        if (incoming.getPrice() >= ctx.lastMyOffer) {
            String reason = "Buyer's offer RM " + String.format("%.0f", incoming.getPrice())
                    + " meets our asking RM " + String.format("%.0f", ctx.lastMyOffer)
                    + " — accepting.";
            dealer.log.info("[AUTO-DEALER] " + reason);
            appendReasoning("✅ ACCEPT — " + reason);
            dealer.acceptOffer(negotiationId, incoming.getPrice(), "Auto-accepted.");
            active = false;
            return;
        }

        // Buyer's offer is below our reservation price — can't accept
        // Check if strategy says accept anyway (e.g. near deadline)
        if (strategy.shouldAccept(incoming.getPrice(), ctx)) {
            // Only accept if buyer is above our reservation price
            if (incoming.getPrice() >= ctx.reservationPrice) {
                String reason = strategy.getLastReasoning();
                dealer.log.info("[AUTO-DEALER] ACCEPT (strategy) — " + reason);
                appendReasoning("✅ ACCEPT — " + reason);
                dealer.acceptOffer(negotiationId, incoming.getPrice(),
                        "Auto-accepted. " + reason);
                active = false;
                return;
            }
        }

        // Deadline
        if (round >= ctx.maxRounds) {
            if (incoming.getPrice() >= ctx.reservationPrice) {
                String reason = "Deadline round " + round + " — buyer at RM "
                        + String.format("%.0f", incoming.getPrice())
                        + " is above our minimum RM "
                        + String.format("%.0f", ctx.reservationPrice) + " — accepting.";
                dealer.log.info("[AUTO-DEALER] " + reason);
                appendReasoning(reason);
                dealer.acceptOffer(negotiationId, incoming.getPrice(), "Auto-accepted at deadline.");
            } else {
                String reason = "Deadline reached — buyer RM "
                        + String.format("%.0f", incoming.getPrice())
                        + " still below our minimum RM "
                        + String.format("%.0f", ctx.reservationPrice) + " — rejecting.";
                dealer.log.info("[AUTO-DEALER] " + reason);
                appendReasoning(reason);
                dealer.rejectOffer(negotiationId, "Auto-rejected: below minimum at deadline.");
            }
            active = false;
            return;
        }

        // Impasse detection
        if (ctx.isImpasse()) {
            if (incoming.getPrice() >= ctx.reservationPrice) {
                String reason = "Impasse detected — buyer at RM "
                        + String.format("%.0f", incoming.getPrice())
                        + " is above our floor — accepting to close deal.";
                dealer.log.info("[AUTO-DEALER] " + reason);
                appendReasoning("⚠ " + reason);
                dealer.acceptOffer(negotiationId, incoming.getPrice(),
                        "Auto-accepted: impasse detected.");
            } else {
                String reason = "Impasse detected — buyer at RM "
                        + String.format("%.0f", incoming.getPrice())
                        + " is still below our minimum RM "
                        + String.format("%.0f", ctx.reservationPrice) + " — ending.";
                dealer.log.info("[AUTO-DEALER] " + reason);
                appendReasoning("⚠ " + reason);
                dealer.rejectOffer(negotiationId, "Auto-rejected: impasse, below minimum.");
            }
            active = false;
            return;
        }

        // Generate counter-offer — dealer moves DOWNWARD
        // The strategy generates a value between firstOffer (high) and reservationPrice (low)
        double myOffer = generateDealerOffer();
        ctx.recordMyOffer(myOffer);

        String reasoning = strategy.getLastReasoning();
        dealer.log.info("[AUTO-DEALER] COUNTER RM " + String.format("%.0f", myOffer)
                + " — " + reasoning);
        appendReasoning("Round " + round + " → Counter RM "
                + String.format("%.0f", myOffer) + " | " + reasoning);

        dealer.sendOffer(negotiationId, myOffer, "");

        // Brief pause so negotiation is observable
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
    }

    /**
     * Generate the dealer's counter-offer.
     *
     * The strategy's generateOffer() is designed to move from firstOffer TOWARD
     * reservationPrice. For a buyer that means upward; for a dealer that means downward.
     *
     * We use the same formula — the strategy returns a value in [firstOffer, reservationPrice]
     * but for the dealer these are [askingPrice, minimumPrice], so the output is
     * already moving downward correctly.
     */
    private double generateDealerOffer() {
        double raw = strategy.generateOffer(ctx);
        // Clamp: never below reservation (dealer's minimum), never above asking
        raw = Math.max(raw, ctx.reservationPrice);
        raw = Math.min(raw, ctx.askingPrice);
        // Round to nearest RM 100
        return Math.round(raw / 100.0) * 100.0;
    }

    private void appendReasoning(String text) {
        if (dealer.getGui() != null) {
            javax.swing.SwingUtilities.invokeLater(() ->
                    dealer.getGui().appendAutoReasoning(negotiationId, text));
        }
        // Also write to the conversation log file
        negotiation.util.ConversationLogger.logReasoning(negotiationId, text);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parameters data class
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parameters for one automated dealer negotiation session.
     *
     * reservationPrice = the minimum price the dealer will accept (their floor).
     *   If not set, defaults to 88% of the listing's retail price.
     *   This simulates a ~12% negotiation margin, which is realistic for used cars.
     *
     * strategyKey = which strategy the dealer uses. Boulware is the default
     *   because dealers typically hold firm and only concede near the deadline.
     */
    public static class AutoDealerParams {
        public String strategyKey;
        public double reservationPrice; // dealer's minimum acceptable price
        public int    maxRounds;

        public AutoDealerParams(String strategyKey, double reservationPrice, int maxRounds) {
            this.strategyKey      = strategyKey;
            this.reservationPrice = reservationPrice;
            this.maxRounds        = maxRounds;
        }

        /**
         * Convenience: derive reservation price automatically as a % of retail.
         * e.g. marginPct=0.12 means dealer won't go below 88% of asking price.
         */
        public static AutoDealerParams withMargin(String strategyKey, double retailPrice,
                                                  double marginPct, int maxRounds) {
            double minPrice = retailPrice * (1.0 - marginPct);
            return new AutoDealerParams(strategyKey, minPrice, maxRounds);
        }
    }
}