package negotiation.behaviours;

import jade.core.behaviours.CyclicBehaviour;
import negotiation.agents.BuyerAgent;
import negotiation.models.Assignment;
import negotiation.models.NegotiationMessage;
import negotiation.strategy.*;

import java.util.Queue;

/**
 * AutoNegotiationBehaviour — drives automated buyer negotiation.
  * One instance is created per assignment when the buyer's config has
 * autoNegotiate=true. It runs alongside the existing BuyerMessageBehaviour
 * which keeps handling all JADE message receiving as normal.
 *
 * How messages flow:
 *   BuyerMessageBehaviour receives dealer offer via JADE
 *       → calls BuyerAgent.onNegotiationMessage()
 *           → if auto mode active: adds msg to autoQueue
 *               → this behaviour wakes up, runs strategy, sends response
 *           → if manual mode: routes to GUI as before
 * The strategy decides each round:
 *   shouldAccept() → true  → call buyer.acceptOffer()  → done
 *   shouldAccept() → false → call buyer.sendOffer()     → wait for next dealer msg
 *   dealer rejects         → negotiation ends
 *   maxRounds reached      → accept best available or walk away
 */
public class AutoNegotiationBehaviour extends CyclicBehaviour {

    private final BuyerAgent         buyer;
    private final String             negotiationId;
    private final NegotiationStrategy strategy;
    private final NegotiationContext  ctx;
    private final Queue<NegotiationMessage> queue; // fed by BuyerAgent.onNegotiationMessage()

    private int     round        = 0;
    private boolean active       = true;
    private boolean waitingFirst = true; // true until dealer sends their first offer

    // How long to wait between queue polls in ms
    private static final long POLL_INTERVAL_MS = 300;

    // ─────────────────────────────────────────────────────────────────────────

    public AutoNegotiationBehaviour(BuyerAgent buyer,
                                    Assignment assignment,
                                    AutoNegotiationParams params,
                                    Queue<NegotiationMessage> queue) {
        super(buyer);
        this.buyer         = buyer;
        this.negotiationId = assignment.getNegotiationId();
        this.queue         = queue;

        // Build the context from params
        this.ctx = new NegotiationContext();
        ctx.negotiationId      = negotiationId;
        ctx.askingPrice        = assignment.getListing().getRetailPrice();
        ctx.firstOffer         = params.firstOffer > 0
                ? params.firstOffer
                : ctx.askingPrice * 0.80; // default: 80% of asking
        ctx.reservationPrice   = params.reservationPrice > 0
                ? params.reservationPrice
                : ctx.askingPrice;        // default: don't exceed asking
        ctx.maxRounds          = params.maxRounds > 0 ? params.maxRounds : 10;
        ctx.role               = NegotiationContext.Role.BUYER;
        ctx.carDescription     = assignment.getListing().toString();
        ctx.opponentName       = assignment.getDealerName();
        ctx.lastMyOffer        = ctx.firstOffer;

        // Create strategy from registry
        NegotiationStrategy s;
        try {
            s = StrategyRegistry.create(
                    params.strategyKey != null ? params.strategyKey : "BAYESIAN");
        } catch (IllegalArgumentException e) {
            buyer.log.error("[AUTO] Unknown strategy '" + params.strategyKey
                    + "' — falling back to BAYESIAN");
            s = new BayesianLearnerStrategy();
        }
        this.strategy = s;
        this.strategy.initialise(ctx);

        buyer.log.info("[AUTO] Negotiation [" + negotiationId + "] started"
                + " | Strategy: " + strategy.getDisplayName()
                + " | First offer: RM " + String.format("%.0f", ctx.firstOffer)
                + " | Budget: RM " + String.format("%.0f", ctx.reservationPrice)
                + " | Max rounds: " + ctx.maxRounds);

        // Log strategy info to conversation file so it appears in the header
        negotiation.util.ConversationLogger.logStrategyInfo(
                negotiationId,
                strategy.getDisplayName(),
                "Unknown (manual or pending)",  // dealer strategy unknown from buyer's side
                ctx.firstOffer,
                ctx.reservationPrice,
                ctx.maxRounds);

        // Show auto mode banner in GUI
        if (buyer.getGui() != null) {
            javax.swing.SwingUtilities.invokeLater(() ->
                    buyer.getGui().showAutoModeBanner(negotiationId,
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
        if (!active) {
            block();
            return;
        }

        // Poll the queue for the next dealer message
        NegotiationMessage incoming = queue.poll();

        if (incoming == null) {
            // Nothing yet — if it's the very first round, send our opening offer
            if (waitingFirst && round == 0) {
                sendOpeningOffer();
                waitingFirst = false;
            }
            // Sleep briefly then check again
            block(POLL_INTERVAL_MS);
            return;
        }

        // Got a message from the dealer
        handleIncoming(incoming);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handlers
    // ─────────────────────────────────────────────────────────────────────────

    /** Send our opening offer to the dealer as soon as the negotiation starts. */
    private void sendOpeningOffer() {
        round = 1;
        ctx.currentRound = round;

        double offer = ctx.firstOffer;
        ctx.recordMyOffer(offer);

        buyer.sendOffer(negotiationId, offer,
                "Auto-negotiation started. My opening offer: RM "
                        + String.format("%.0f", offer));

        String reasoning = "Round 1 — Opening offer: RM " + String.format("%.0f", offer)
                + " | Strategy: " + strategy.getDisplayName()
                + " | Budget ceiling: RM " + String.format("%.0f", ctx.reservationPrice);

        buyer.log.info("[AUTO] " + reasoning);
        appendReasoning(reasoning);
    }

    /** Process an incoming dealer message and decide what to do. */
    private void handleIncoming(NegotiationMessage incoming) {

        // Dealer ended the negotiation
        if (incoming.getType() == NegotiationMessage.Type.REJECT) {
            buyer.log.info("[AUTO] Dealer rejected the negotiation. Session ended.");
            appendReasoning("Dealer ended the negotiation — no deal.");
            active = false;
            return;
        }

        // Update round counter and context
        round++;
        ctx.currentRound = round;
        ctx.recordOpponentOffer(incoming.getPrice());

        // Tell the strategy what the dealer just offered
        strategy.onOpponentOffer(incoming.getPrice(), round);

        buyer.log.info("[AUTO] Round " + round + " | Dealer offered RM "
                + String.format("%.0f", incoming.getPrice()));

        // ── Decision point ────────────────────────────────────────────────────

        // Hit the deadline — must decide now
        if (round >= ctx.maxRounds) {
            if (incoming.getPrice() <= ctx.reservationPrice) {
                String reason = "Deadline reached (round " + round + "/" + ctx.maxRounds
                        + ") — accepting RM " + String.format("%.0f", incoming.getPrice())
                        + " as it is within budget.";
                buyer.log.info("[AUTO] " + reason);
                appendReasoning(reason);
                buyer.acceptOffer(negotiationId, incoming.getPrice(), "Auto-accepted at deadline.");
            } else {
                String reason = "Deadline reached but offer RM "
                        + String.format("%.0f", incoming.getPrice())
                        + " exceeds budget RM "
                        + String.format("%.0f", ctx.reservationPrice)
                        + " — walking away.";
                buyer.log.info("[AUTO] " + reason);
                appendReasoning(reason);
                buyer.rejectOffer(negotiationId, "Auto-rejected: offer exceeds budget at deadline.");
            }
            active = false;
            return;
        }

        // Impasse detection — if neither side has moved meaningfully in 3 rounds,
        // the negotiation is stuck. Accept if within budget, otherwise reject.
        if (ctx.isImpasse()) {
            if (incoming.getPrice() <= ctx.reservationPrice) {
                String reason = "Impasse detected (neither side moved >0.5% in last 3 rounds)"
                        + " — accepting current offer RM "
                        + String.format("%.0f", incoming.getPrice()) + " to close the deal.";
                buyer.log.info("[AUTO] " + reason);
                appendReasoning("⚠ " + reason);
                buyer.acceptOffer(negotiationId, incoming.getPrice(),
                        "Auto-accepted: impasse detected.");
            } else {
                String reason = "Impasse detected and offer RM "
                        + String.format("%.0f", incoming.getPrice())
                        + " still exceeds budget RM "
                        + String.format("%.0f", ctx.reservationPrice)
                        + " — ending negotiation.";
                buyer.log.info("[AUTO] " + reason);
                appendReasoning("⚠ " + reason);
                buyer.rejectOffer(negotiationId, "Auto-rejected: impasse, over budget.");
            }
            active = false;
            return;
        }

        // Ask strategy: should we accept?
        if (strategy.shouldAccept(incoming.getPrice(), ctx)) {
            String reasoning = strategy.getLastReasoning();
            buyer.log.info("[AUTO] ACCEPT — " + reasoning);
            appendReasoning("✅ ACCEPT — " + reasoning);
            buyer.acceptOffer(negotiationId, incoming.getPrice(),
                    "Auto-accepted. " + reasoning);
            active = false;
            return;
        }

        // Counter-offer
        double myOffer = strategy.generateOffer(ctx);

        // Critical: buyer wants to pay LESS than the dealer's current offer.
        // If the formula overshoots (offers MORE than dealer's price), cap it
        // just below the dealer's current offer instead.
        if (myOffer >= ctx.lastOpponentOffer) {
            myOffer = ctx.lastOpponentOffer - 100; // stay RM 100 below dealer
            myOffer = Math.max(myOffer, ctx.firstOffer); // but not below our opening
        }
        // Also never exceed reservation price
        myOffer = Math.min(myOffer, ctx.reservationPrice);
        myOffer = Math.round(myOffer / 100.0) * 100.0;

        ctx.recordMyOffer(myOffer);

        String reasoning = strategy.getLastReasoning();
        buyer.log.info("[AUTO] COUNTER RM " + String.format("%.0f", myOffer)
                + " — " + reasoning);
        appendReasoning("Round " + round + " → Counter RM "
                + String.format("%.0f", myOffer) + " | " + reasoning);

        buyer.sendOffer(negotiationId, myOffer, "");

        // Brief pause between rounds so the negotiation is observable in the GUI
        // and doesn't complete too fast to follow. 1.5 seconds per round.
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    /** Append reasoning text to the GUI chat tab AND the conversation log file. */
    private void appendReasoning(String text) {
        if (buyer.getGui() != null) {
            javax.swing.SwingUtilities.invokeLater(() ->
                    buyer.getGui().appendAutoReasoning(negotiationId, text));
        }
        // Also write to the conversation log file
        negotiation.util.ConversationLogger.logReasoning(negotiationId, text);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parameters data class — passed in by BuyerAgent when starting auto mode
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parameters for one auto-negotiation session.
     * Populated from the buyer's JSON config (written by spawn_agents.py)
     * or from the GUI auto-negotiate panel.
     */
    public static class AutoNegotiationParams {

        /** Key from StrategyRegistry — e.g. "BAYESIAN", "TIT_FOR_TAT" */
        public String strategyKey;

        /** Buyer's opening bid. 0 = use 80% of asking price. */
        public double firstOffer;

        /** Buyer's walk-away price. 0 = use asking price as ceiling. */
        public double reservationPrice;

        /** Maximum rounds before forced decision. 0 = default 10. */
        public int maxRounds;

        /** Convenience constructor. */
        public AutoNegotiationParams(String strategyKey, double firstOffer,
                                     double reservationPrice, int maxRounds) {
            this.strategyKey      = strategyKey;
            this.firstOffer       = firstOffer;
            this.reservationPrice = reservationPrice;
            this.maxRounds        = maxRounds;
        }

        /** Build from BuyerAgent's inner BuyerConfig (loaded from JSON). */
        public static AutoNegotiationParams fromConfig(String strategyKey, double firstOffer,
                                                       double reservationPrice, int maxRounds) {
            return new AutoNegotiationParams(strategyKey, firstOffer, reservationPrice, maxRounds);
        }
    }
}