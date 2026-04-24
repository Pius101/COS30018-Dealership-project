package negotiation.behaviours.buyer;

import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import negotiation.agents.BuyerAgent;
import negotiation.messages.Ontology;
import negotiation.models.Assignment;
import negotiation.models.NegotiationMessage;

/**
 * Buyer Agent's cyclic message listener.
 *
 * Handles messages arriving from the Broker Agent:
 *   ASSIGNMENT_NOTIFY  — broker assigned us to a dealer
 *   NEG_OFFER          — dealer sent an offer (routed by KA)
 *   NEG_REJECT         — dealer ended the negotiation (routed by KA)
 *   DEAL_COMPLETE      — KA confirmed the deal is done
 *   REQUIREMENTS_ACK   — KA confirmed requirements were stored (optional log)
 */
public class BuyerMessageBehaviour extends CyclicBehaviour {

    private final BuyerAgent buyer;

    public BuyerMessageBehaviour(BuyerAgent buyer) {
        super(buyer);
        this.buyer = buyer;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive();

        if (msg == null) {
            block();
            return;
        }

        String type = msg.getOntology();
        if (type == null) return;

        switch (type) {
            case Ontology.TYPE_ASSIGNMENT_NOTIFY  -> handleAssignment(msg);
            case Ontology.TYPE_NEG_OFFER          -> handleNegotiationMessage(msg);
            case Ontology.TYPE_NEG_REJECT         -> handleNegotiationMessage(msg);
            case Ontology.TYPE_DEAL_COMPLETE      -> handleDealComplete(msg);
            case Ontology.TYPE_REQUIREMENTS_ACK   -> {
                try {
                    com.google.gson.JsonObject obj = buyer.gson.fromJson(
                        msg.getContent(), com.google.gson.JsonObject.class);
                    buyer.onRequirementAck(obj.get("requirementId").getAsString());
                } catch (Exception ignored) {
                    buyer.log.recv("Broker", Ontology.TYPE_REQUIREMENTS_ACK, "");
                }
            }
            default ->
                System.err.println("[Buyer:" + buyer.getLocalName()
                        + "] Unknown message type: " + type);
        }
    }

    private void handleAssignment(ACLMessage msg) {
        try {
            Assignment a = buyer.gson.fromJson(msg.getContent(), Assignment.class);
            buyer.onAssignment(a);
        } catch (Exception e) {
            System.err.println("[Buyer] Failed to parse assignment: " + e.getMessage());
        }
    }

    private void handleNegotiationMessage(ACLMessage msg) {
        try {
            NegotiationMessage nm = buyer.gson.fromJson(msg.getContent(), NegotiationMessage.class);
            buyer.onNegotiationMessage(nm);
        } catch (Exception e) {
            System.err.println("[Buyer] Failed to parse negotiation message: " + e.getMessage());
        }
    }

    private void handleDealComplete(ACLMessage msg) {
        try {
            NegotiationMessage nm = buyer.gson.fromJson(msg.getContent(), NegotiationMessage.class);
            buyer.onDealComplete(nm);
        } catch (Exception e) {
            System.err.println("[Buyer] Failed to parse deal-complete: " + e.getMessage());
        }
    }
}
