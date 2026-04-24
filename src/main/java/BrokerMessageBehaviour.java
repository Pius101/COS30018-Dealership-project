package negotiation.behaviours.broker;

import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import negotiation.agents.BrokerAgent;
import negotiation.messages.Ontology;
import negotiation.models.CarListing;
import negotiation.models.CarRequirement;
import negotiation.models.NegotiationMessage;

/**
 * The Broker Agent's single cyclic behaviour.
 * Receives all inbound ACL messages and dispatches based on ontology type.
 */
public class BrokerMessageBehaviour extends CyclicBehaviour {

    private final BrokerAgent broker;

    public BrokerMessageBehaviour(BrokerAgent broker) {
        super(broker);
        this.broker = broker;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive();
        if (msg == null) { block(); return; }

        String type = msg.getOntology();
        if (type == null) {
            broker.log.error("Message with no ontology from " + msg.getSender().getLocalName());
            return;
        }

        switch (type) {
            case Ontology.TYPE_LISTING_REGISTER   -> handleListingRegister(msg);
            case Ontology.TYPE_BUYER_REQUIREMENTS -> handleBuyerRequirements(msg);
            case Ontology.TYPE_NEG_OFFER          -> handleNegotiationMessage(msg, NegotiationMessage.Type.OFFER);
            case Ontology.TYPE_NEG_ACCEPT         -> handleNegotiationAccept(msg);
            case Ontology.TYPE_NEG_REJECT         -> handleNegotiationMessage(msg, NegotiationMessage.Type.REJECT);
            case "EMERGENCY_SAVE"                  -> handleEmergencySave(msg);
            default -> broker.log.error("Unknown ontology type: " + type
                    + "  from " + msg.getSender().getLocalName());
        }
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private void handleListingRegister(ACLMessage msg) {
        try {
            CarListing listing = broker.gson.fromJson(msg.getContent(), CarListing.class);
            listing.setDealerAID(msg.getSender().getName());
            listing.setDealerName(msg.getSender().getLocalName());
            broker.onListingReceived(listing);

            ACLMessage ack = msg.createReply();
            ack.setPerformative(ACLMessage.CONFIRM);
            ack.setOntology(Ontology.TYPE_LISTING_ACK);
            ack.setContent("{\"listingId\":\"" + listing.getListingId() + "\"}");
            myAgent.send(ack);
            broker.log.send(msg.getSender().getLocalName(), Ontology.TYPE_LISTING_ACK,
                    "ACK for " + listing.getListingId());
        } catch (Exception e) {
            broker.log.error("Bad LISTING_REGISTER from " + msg.getSender().getLocalName()
                    + ": " + e.getMessage());
        }
    }

    private void handleBuyerRequirements(ACLMessage msg) {
        try {
            CarRequirement req = broker.gson.fromJson(msg.getContent(), CarRequirement.class);
            req.setBuyerAID(msg.getSender().getName());
            req.setBuyerName(msg.getSender().getLocalName());
            broker.onRequirementReceived(req);

            ACLMessage ack = msg.createReply();
            ack.setPerformative(ACLMessage.CONFIRM);
            ack.setOntology(Ontology.TYPE_REQUIREMENTS_ACK);
            ack.setContent("{\"requirementId\":\"" + req.getRequirementId() + "\"}");
            myAgent.send(ack);
            broker.log.send(msg.getSender().getLocalName(), Ontology.TYPE_REQUIREMENTS_ACK,
                    "ACK for " + req.getRequirementId());
        } catch (Exception e) {
            broker.log.error("Bad BUYER_REQUIREMENTS from " + msg.getSender().getLocalName()
                    + ": " + e.getMessage());
        }
    }

    private void handleNegotiationMessage(ACLMessage msg, NegotiationMessage.Type type) {
        try {
            NegotiationMessage nm = broker.gson.fromJson(msg.getContent(), NegotiationMessage.class);
            nm.setType(type);
            nm.setTimestamp(System.currentTimeMillis());
            broker.routeNegotiationMessage(nm, nm.getToAID());
        } catch (Exception e) {
            broker.log.error("Bad negotiation message from " + msg.getSender().getLocalName()
                    + ": " + e.getMessage());
        }
    }

    private void handleNegotiationAccept(ACLMessage msg) {
        try {
            NegotiationMessage nm = broker.gson.fromJson(msg.getContent(), NegotiationMessage.class);
            nm.setType(NegotiationMessage.Type.ACCEPT);
            nm.setTimestamp(System.currentTimeMillis());
            broker.closeDeal(nm);
        } catch (Exception e) {
            broker.log.error("Bad NEG_ACCEPT from " + msg.getSender().getLocalName()
                    + ": " + e.getMessage());
        }
    }

    private void handleEmergencySave(ACLMessage msg) {
        try {
            // Parse emergency save data
            String content = msg.getContent();
            broker.log.info("Emergency save request from " + msg.getSender().getLocalName());
            
            // Extract data using simple parsing (avoiding complex object creation)
            String agentName = msg.getSender().getLocalName();
            String reason = "shutdown";
            
            // Find all ongoing negotiations for this agent
            broker.getAssignmentsMap().values().stream()
                .filter(assignment -> assignment.getBuyerAID().equals(msg.getSender().getName()) || 
                                   assignment.getDealerAID().equals(msg.getSender().getName()))
                .forEach(assignment -> {
                    negotiation.util.ConversationLogger.markInterrupted(
                        assignment.getNegotiationId(), 
                        agentName, 
                        reason
                    );
                    broker.log.info("Emergency saved conversation: " + assignment.getNegotiationId());
                });
                
        } catch (Exception e) {
            broker.log.error("Failed to handle emergency save from " + msg.getSender().getLocalName()
                    + ": " + e.getMessage());
        }
    }
}
