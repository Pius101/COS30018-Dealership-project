package negotiation.behaviours;

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
        if (type == null) {
            buyer.log.warn("Message with no ontology from "
                    + msg.getSender().getLocalName()
                    + " | ACL." + aclPerformativeName(msg.getPerformative())
                    + " | content=" + msg.getContent());
            return;
        }

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
                buyer.log.warn("Unknown message type: " + type
                        + " | sender=" + msg.getSender().getLocalName()
                        + " | ACL." + aclPerformativeName(msg.getPerformative())
                        + " | content=" + msg.getContent());
        }
    }

    private void handleAssignment(ACLMessage msg) {
        try {
            Assignment a = buyer.gson.fromJson(msg.getContent(), Assignment.class);
            buyer.onAssignment(a);
        } catch (Exception e) {
            buyer.log.error("Failed to parse assignment"
                    + " | sender=" + msg.getSender().getLocalName()
                    + " | content=" + msg.getContent(), e);
        }
    }

    private void handleNegotiationMessage(ACLMessage msg) {
        try {
            NegotiationMessage nm = buyer.gson.fromJson(msg.getContent(), NegotiationMessage.class);
            enrichAclMetadata(msg, nm);
            buyer.onNegotiationMessage(nm);
        } catch (Exception e) {
            buyer.log.error("Failed to parse negotiation message"
                    + " | sender=" + msg.getSender().getLocalName()
                    + " | receiver=" + buyer.getLocalName()
                    + " | content=" + msg.getContent(), e);
        }
    }

    private void handleDealComplete(ACLMessage msg) {
        try {
            NegotiationMessage nm = buyer.gson.fromJson(msg.getContent(), NegotiationMessage.class);
            enrichAclMetadata(msg, nm);
            buyer.onDealComplete(nm);
        } catch (Exception e) {
            buyer.log.error("Failed to parse deal-complete"
                    + " | sender=" + msg.getSender().getLocalName()
                    + " | content=" + msg.getContent(), e);
        }
    }

    private static void enrichAclMetadata(ACLMessage aclMessage, NegotiationMessage negotiationMessage) {
        negotiationMessage.setAclPerformative(aclPerformativeName(aclMessage.getPerformative()));
        negotiationMessage.setConversationId(aclMessage.getConversationId());
    }

    private static String aclPerformativeName(int performative) {
        return switch (performative) {
            case ACLMessage.REQUEST -> "REQUEST";
            case ACLMessage.INFORM -> "INFORM";
            case ACLMessage.PROPOSE -> "PROPOSE";
            case ACLMessage.ACCEPT_PROPOSAL -> "ACCEPT_PROPOSAL";
            case ACLMessage.REJECT_PROPOSAL -> "REJECT_PROPOSAL";
            case ACLMessage.REFUSE -> "REFUSE";
            case ACLMessage.FAILURE -> "FAILURE";
            case ACLMessage.CONFIRM -> "CONFIRM";
            default -> String.valueOf(performative);
        };
    }
}
