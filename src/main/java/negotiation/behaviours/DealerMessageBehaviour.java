package negotiation.behaviours;

import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import negotiation.agents.DealerAgent;
import negotiation.messages.Ontology;
import negotiation.models.Assignment;
import negotiation.models.NegotiationMessage;
import negotiation.models.PotentialBuyerList;

/**
 * Handles messages arriving from the Broker Agent:
 *   POTENTIAL_BUYERS   — broker sent potential buyers for our cars (NEW, CFP)
 *   ASSIGNMENT_NOTIFY  — broker assigned us to a buyer
 *   NEG_OFFER          — buyer sent a counter-offer (routed by KA)
 *   NEG_REJECT         — buyer ended the negotiation (routed by KA)
 *   DEAL_COMPLETE      — KA confirmed the deal is done
 *   LISTING_ACK        — KA confirmed a listing was stored
 */
public class DealerMessageBehaviour extends CyclicBehaviour {

    private final DealerAgent dealer;

    public DealerMessageBehaviour(DealerAgent dealer) {
        super(dealer);
        this.dealer = dealer;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive();
        if (msg == null) { block(); return; }

        String type = msg.getOntology();
        if (type == null) return;

        switch (type) {
            case Ontology.TYPE_POTENTIAL_BUYERS  -> handlePotentialBuyers(msg);  // NEW (Phase C)
            case Ontology.TYPE_ASSIGNMENT_NOTIFY -> handleAssignment(msg);
            case Ontology.TYPE_NEG_OFFER         -> handleNegotiationMessage(msg);
            case Ontology.TYPE_NEG_REJECT        -> handleNegotiationMessage(msg);
            case Ontology.TYPE_DEAL_COMPLETE     -> handleDealComplete(msg);
            case Ontology.TYPE_LISTING_ACK       -> {
                try {
                    com.google.gson.JsonObject obj = dealer.gson.fromJson(
                            msg.getContent(), com.google.gson.JsonObject.class);
                    dealer.onListingAck(obj.get("listingId").getAsString());
                } catch (Exception ignored) {
                    dealer.log.recv("Broker", Ontology.TYPE_LISTING_ACK, "");
                }
            }
            default ->
                    System.err.println("[Dealer:" + dealer.getLocalName()
                            + "] Unknown message type: " + type);
        }
    }

    /** NEW — Phase C: broker sent potential buyers; dealer selects whom to engage. */
    private void handlePotentialBuyers(ACLMessage msg) {
        try {
            PotentialBuyerList pl = dealer.gson.fromJson(msg.getContent(), PotentialBuyerList.class);
            dealer.onPotentialBuyers(pl);
        } catch (Exception e) {
            System.err.println("[Dealer] Failed to parse POTENTIAL_BUYERS: " + e.getMessage());
        }
    }

    private void handleAssignment(ACLMessage msg) {
        try {
            Assignment a = dealer.gson.fromJson(msg.getContent(), Assignment.class);
            dealer.onAssignment(a);
        } catch (Exception e) {
            System.err.println("[Dealer] Failed to parse assignment: " + e.getMessage());
        }
    }

    private void handleNegotiationMessage(ACLMessage msg) {
        try {
            NegotiationMessage nm = dealer.gson.fromJson(msg.getContent(), NegotiationMessage.class);
            dealer.onNegotiationMessage(nm);
        } catch (Exception e) {
            System.err.println("[Dealer] Failed to parse negotiation message: " + e.getMessage());
        }
    }

    private void handleDealComplete(ACLMessage msg) {
        try {
            NegotiationMessage nm = dealer.gson.fromJson(msg.getContent(), NegotiationMessage.class);
            dealer.onDealComplete(nm);
        } catch (Exception e) {
            System.err.println("[Dealer] Failed to parse deal-complete: " + e.getMessage());
        }
    }
}