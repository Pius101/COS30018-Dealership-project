package negotiation.agents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jade.core.AID;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import negotiation.behaviours.buyer.BuyerMessageBehaviour;
import negotiation.gui.BuyerGui;
import negotiation.messages.Ontology;
import negotiation.models.Assignment;
import negotiation.models.CarRequirement;
import negotiation.models.NegotiationMessage;
import negotiation.util.AppLogger;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buyer Agent (BA) — represents a car buyer on the platform.
 *
 * Workflow (V1):
 *   1. Opens Buyer GUI on startup
 *   2. Buyer fills in car requirements → submitted to Broker Agent (KA) via ACL REQUEST
 *   3. Waits for broker assignment notification
 *   4. Receives assignment → new negotiation chat tab opens
 *   5. Reads dealer's offer, types counter-offers → sent to KA → KA routes to dealer
 *   6. Accepts or rejects dealer's offers
 */
public class BuyerAgent extends Agent {

    public final Gson      gson = new GsonBuilder().create();
    public final AppLogger log  = new AppLogger("Buyer");

    private final Map<String, CarRequirement>          myRequirements = new ConcurrentHashMap<>();
    private final Map<String, Assignment>              assignments    = new ConcurrentHashMap<>();
    private final Map<String, List<NegotiationMessage>> history       = new ConcurrentHashMap<>();

    private AID      brokerAID;
    private BuyerGui gui;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void setup() {
        brokerAID = findBroker();
        addBehaviour(new BuyerMessageBehaviour(this));
        SwingUtilities.invokeLater(() -> {
            gui = new BuyerGui(this);
            log.setLogArea(gui.getLogArea());
            gui.setVisible(true);
            log.info("Buyer Agent '" + getLocalName() + "' started. AID: " + getAID().getName());
            if (brokerAID != null) {
                log.info("Connected to Broker: " + brokerAID.getName());
            } else {
                log.error("Broker not found in DF — check the HOST is running!");
            }
        });
    }

    @Override
    protected void takeDown() {
        log.info("Buyer '" + getLocalName() + "' shutting down.");
        
        // Emergency save any ongoing negotiations
        if (brokerAID != null) {
            try {
                ACLMessage emergencySave = new ACLMessage(ACLMessage.INFORM);
                emergencySave.addReceiver(brokerAID);
                emergencySave.setOntology("EMERGENCY_SAVE");
                emergencySave.setContent(gson.toJson(new Object(){
                    public final String agentAID = getAID().getName();
                    public final String agentName = getLocalName();
                    public final String reason = "shutdown";
                    public final String[] ongoingNegotiations = assignments.keySet().toArray(new String[0]);
                }));
                send(emergencySave);
                log.info("Emergency save request sent for ongoing negotiations");
            } catch (Exception e) {
                log.error("Failed to send emergency save request: " + e.getMessage());
            }
        }
        
        if (gui != null) SwingUtilities.invokeLater(gui::dispose);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DF lookup
    // ─────────────────────────────────────────────────────────────────────────

    public AID findBroker() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(Ontology.BROKER_SERVICE_TYPE);
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(this, template);
            if (results.length > 0) {
                AID found = results[0].getName();
                log.info("Broker found in DF: " + found.getName());
                return found;
            }
        } catch (FIPAException fe) { fe.printStackTrace(); }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GUI-initiated actions
    // ─────────────────────────────────────────────────────────────────────────

    /** Called by BuyerGui when buyer clicks "Submit Requirements to Broker". */
    public void submitRequirement(CarRequirement req) {
        if (!ensureBroker()) return;
        req.setBuyerAID(getAID().getName());
        req.setBuyerName(getLocalName());
        myRequirements.put(req.getRequirementId(), req);

        ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
        msg.addReceiver(brokerAID);
        msg.setOntology(Ontology.TYPE_BUYER_REQUIREMENTS);
        msg.setConversationId(Ontology.CONV_REGISTRATION);
        msg.setContent(gson.toJson(req));
        send(msg);

        log.send("Broker", Ontology.TYPE_BUYER_REQUIREMENTS,
                req.summary() + "  [" + req.getRequirementId() + "]");
        SwingUtilities.invokeLater(() -> gui.onRequirementSubmitted(req));
    }

    /** Called by the negotiation chat panel when buyer sends a counter-offer. */
    public void sendOffer(String negotiationId, double price, String messageText) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, price, messageText, NegotiationMessage.Type.OFFER);
        nm.setToAID(a.getDealerAID());
        nm.setToName(a.getDealerName());
        recordAndSend(nm, ACLMessage.PROPOSE, Ontology.TYPE_NEG_OFFER);
        log.send("Broker→" + a.getDealerName(), Ontology.TYPE_NEG_OFFER,
                "RM " + String.format("%.0f", price)
                + (messageText.isBlank() ? "" : "  \"" + messageText + "\""));
    }

    /** Called when buyer accepts the dealer's last offer. */
    public void acceptOffer(String negotiationId, double price, String messageText) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, price, messageText, NegotiationMessage.Type.ACCEPT);
        nm.setToAID(a.getDealerAID());
        nm.setToName(a.getDealerName());
        recordLocal(nm);

        ACLMessage msg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
        msg.addReceiver(brokerAID);
        msg.setOntology(Ontology.TYPE_NEG_ACCEPT);
        msg.setConversationId(Ontology.CONV_NEGOTIATION);
        msg.setContent(gson.toJson(nm));
        send(msg);
        log.send("Broker", Ontology.TYPE_NEG_ACCEPT,
                "ACCEPTED at RM " + String.format("%.0f", price));
    }

    /** Called when buyer rejects (ends the negotiation). */
    public void rejectOffer(String negotiationId, String messageText) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, 0, messageText, NegotiationMessage.Type.REJECT);
        nm.setToAID(a.getDealerAID());
        nm.setToName(a.getDealerName());
        recordAndSend(nm, ACLMessage.REFUSE, Ontology.TYPE_NEG_REJECT);
        log.info("Negotiation [" + negotiationId + "] REJECTED by buyer");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks from BuyerMessageBehaviour
    // ─────────────────────────────────────────────────────────────────────────

    public void onAssignment(Assignment assignment) {
        assignments.put(assignment.getNegotiationId(), assignment);
        history.put(assignment.getNegotiationId(), new ArrayList<>());
        log.recv("Broker", Ontology.TYPE_ASSIGNMENT_NOTIFY,
                "Negotiation [" + assignment.getNegotiationId() + "]"
                + "  Dealer: " + assignment.getDealerName()
                + "  Listing: " + assignment.getListing().getListingId()
                + "  Asking: RM " + String.format("%.0f", assignment.getListing().getRetailPrice()));
        if (gui != null) SwingUtilities.invokeLater(() -> gui.openNegotiationTab(assignment));
    }

    public void onNegotiationMessage(NegotiationMessage msg) {
        recordLocal(msg);
        log.recv("Broker←" + msg.getFromName(), msg.getType().name(),
                "RM " + String.format("%.0f", msg.getPrice())
                + (msg.getMessage() != null && !msg.getMessage().isBlank()
                   ? "  \"" + msg.getMessage() + "\"" : ""));
        if (gui != null) SwingUtilities.invokeLater(() -> gui.appendNegotiationMessage(msg));
    }

    public void onDealComplete(NegotiationMessage finalMsg) {
        recordLocal(finalMsg);
        log.event("DEAL COMPLETE  [" + finalMsg.getNegotiationId() + "]"
                + "  Final price: RM " + String.format("%.0f", finalMsg.getPrice()));
        if (gui != null) SwingUtilities.invokeLater(() -> gui.onDealComplete(finalMsg));
    }

    public void onRequirementAck(String requirementId) {
        log.recv("Broker", Ontology.TYPE_REQUIREMENTS_ACK,
                "Requirement " + requirementId + " confirmed stored — waiting for assignment");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private NegotiationMessage buildMsg(String negotiationId, Assignment a,
                                        double price, String text, NegotiationMessage.Type type) {
        NegotiationMessage nm = new NegotiationMessage();
        nm.setNegotiationId(negotiationId);
        nm.setListingId(a.getListing().getListingId());
        nm.setListingDescription(a.getListing().toString());
        nm.setFromAID(getAID().getName());
        nm.setFromName(getLocalName());
        nm.setFromRole("BUYER");
        nm.setPrice(price);
        nm.setMessage(text);
        nm.setType(type);
        return nm;
    }

    private void recordAndSend(NegotiationMessage nm, int performative, String ontology) {
        recordLocal(nm);
        ACLMessage msg = new ACLMessage(performative);
        msg.addReceiver(brokerAID);
        msg.setOntology(ontology);
        msg.setConversationId(Ontology.CONV_NEGOTIATION);
        msg.setContent(gson.toJson(nm));
        send(msg);
    }

    private void recordLocal(NegotiationMessage nm) {
        history.computeIfAbsent(nm.getNegotiationId(), k -> new ArrayList<>()).add(nm);
    }

    private boolean ensureBroker() {
        if (brokerAID != null) return true;
        brokerAID = findBroker();
        if (brokerAID == null) {
            log.error("Cannot find Broker Agent — is the HOST running?");
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(gui,
                    "Cannot reach the Broker Agent.\nMake sure the HOST is running.",
                    "Connection Error", JOptionPane.ERROR_MESSAGE));
            return false;
        }
        return true;
    }

    // ── Accessors ────────────────────────────────────────────────────────────
    public List<CarRequirement>     getMyRequirements()          { return new ArrayList<>(myRequirements.values()); }
    public Map<String, Assignment>  getAssignments()             { return Collections.unmodifiableMap(assignments); }
    public List<NegotiationMessage> getHistory(String id)        { return history.getOrDefault(id, Collections.emptyList()); }
}
