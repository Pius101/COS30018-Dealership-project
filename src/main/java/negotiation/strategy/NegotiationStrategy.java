package negotiation.strategy;

/**
 * Plugin interface for negotiation strategies.
 *
 * ─── How to add your own strategy ───────────────────────────────────────────
 *
 *   1. Create a class that implements this interface:
 *
 *      public class MyStrategy implements NegotiationStrategy {
 *          // implement all methods below
 *      }
 *
 *   2. Register it in StrategyRegistry.java (one line):
 *
 *      StrategyRegistry.register("MY_STRATEGY", MyStrategy::new);
 *
 *   3. That's it. It will appear in the GUI dropdown automatically.
 *
 * ─── Strategy contract ───────────────────────────────────────────────────────
 *
 *   - initialise()      called once when negotiation starts
 *   - onOpponentOffer() called every time the other party sends an offer
 *   - generateOffer()   return what price to offer next
 *   - shouldAccept()    return true to accept the opponent's last offer
 *   - getLastReasoning() full explanation of the last decision (logged + visualised)
 *
 * ─── What is "reasoning"? ────────────────────────────────────────────────────
 *
 *   The reasoning string is shown in the negotiation chat, written to the
 *   conversation log, and embedded in the HTML visualisation report.
 *   It should explain WHY the strategy made its choice:
 *
 *     "Round 3/10 | Dealer dropped RM 2,000 (rate 2.4%) — LINEAR style detected.
 *      β=1.0 | Estimated dealer floor: RM 78,500 | My offer: RM 74,000"
 */
public interface NegotiationStrategy {

    /**
     * Short machine-readable key used in config files and CLI args.
     * Example: "BAYESIAN", "TIME_DEPENDENT", "FIXED_INCREMENT"
     */
    String getKey();

    /**
     * Human-readable name shown in the GUI strategy picker dropdown.
     * Example: "Bayesian Learner", "Fixed Increment (2%)"
     */
    String getDisplayName();

    /**
     * One-sentence description shown as a tooltip in the GUI.
     */
    String getDescription();

    /**
     * Called once when the negotiation begins.
     * Use this to set initial state from the context (opening prices, deadline, etc.)
     *
     * @param ctx  the negotiation parameters — reservation price, first offer, deadline, etc.
     */
    void initialise(NegotiationContext ctx);

    /**
     * Called every time the opponent sends a new offer.
     * Update your internal model here — track concession rates, adjust beliefs, etc.
     *
     * @param opponentPrice  the price the opponent just offered
     * @param round          the current round number (1-indexed)
     */
    void onOpponentOffer(double opponentPrice, int round);

    /**
     * Compute what price to offer next.
     * Called immediately after onOpponentOffer() if shouldAccept() returned false.
     *
     * @param ctx  current negotiation state (round, last offers, etc.)
     * @return     the price to offer — must be in a sensible range
     */
    double generateOffer(NegotiationContext ctx);

    /**
     * Decide whether to accept the opponent's latest offer.
     * Called before generateOffer() — if this returns true, generateOffer() is skipped.
     *
     * @param opponentOffer  the price the opponent just offered
     * @param ctx            current negotiation state
     * @return               true to accept and close the deal
     */
    boolean shouldAccept(double opponentOffer, NegotiationContext ctx);

    /**
     * Full reasoning behind the last generateOffer() or shouldAccept() decision.
     * Written to the conversation log and embedded in the HTML report.
     * Should include: round info, what was observed, what was decided, why.
     */
    String getLastReasoning();
}