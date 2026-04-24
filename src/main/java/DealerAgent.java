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
import negotiation.behaviours.dealer.DealerMessageBehaviour;
import negotiation.gui.DealerGui;
import negotiation.messages.Ontology;
import negotiation.models.Assignment;
import negotiation.models.CarListing;
import negotiation.models.NegotiationMessage;
import negotiation.util.AppLogger;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dealer Agent (DA) — represents a car dealer on the platform.
 *
 * Workflow (V1):
 *   1. Opens Dealer GUI on startup
 *   2. Dealer fills in car listings → submitted to Broker Agent (KA) via ACL INFORM
 *   3. Waits for broker assignment notification
 *   4. Receives assignment → new negotiation chat tab opens
 *   5. Types offers in the chat → sent to KA → KA routes to buyer
 *   6. Accepts or rejects buyer's counter-offers
 */
public class DealerAgent extends Agent {

    public final Gson      gson = new GsonBuilder().create();
    public final AppLogger log  = new AppLogger("Dealer:" + hashCode()); // temp name

    private final Map<String, CarListing>              myListings   = new ConcurrentHashMap<>();
    private final Map<String, Assignment>              assignments  = new ConcurrentHashMap<>();
    private final Map<String, List<NegotiationMessage>> history     = new ConcurrentHashMap<>();

    private AID       brokerAID;
    private DealerGui gui;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void setup() {
        // Update logger name now that we know our agent name
        AppLogger namedLog = new AppLogger("Dealer:" + getLocalName());
        // we can't reassign final, so just use the field directly after setup
        // (AppLogger is not final, reassignment is fine — see field declaration)
        brokerAID = findBroker();
        addBehaviour(new DealerMessageBehaviour(this));
        SwingUtilities.invokeLater(() -> {
            gui = new DealerGui(this);
            log.setLogArea(gui.getLogArea());
            gui.setVisible(true);
            log.info("Dealer Agent '" + getLocalName() + "' started. AID: " + getAID().getName());
            if (brokerAID != null) {
                log.info("Connected to Broker: " + brokerAID.getName());
            } else {
                log.error("Broker not found in DF — check the HOST is running!");
            }
        });
    }

    @Override
    protected void takeDown() {
        log.info("Dealer '" + getLocalName() + "' shutting down.");
        
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

    /** Called by DealerGui when dealer clicks "Submit Listing to Broker". */
    public void submitListing(CarListing listing) {
        if (!ensureBroker()) return;
        listing.setDealerAID(getAID().getName());
        listing.setDealerName(getLocalName());
        myListings.put(listing.getListingId(), listing);

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(brokerAID);
        msg.setOntology(Ontology.TYPE_LISTING_REGISTER);
        msg.setConversationId(Ontology.CONV_REGISTRATION);
        msg.setContent(gson.toJson(listing));
        send(msg);

        log.send("Broker", Ontology.TYPE_LISTING_REGISTER,
                listing.getYear() + " " + listing.getMake() + " " + listing.getModel()
                + "  RM " + String.format("%.0f", listing.getRetailPrice())
                + "  [" + listing.getListingId() + "]");

        SwingUtilities.invokeLater(() -> gui.refreshMyListings(new ArrayList<>(myListings.values())));
    }

    /** Called by the negotiation chat panel when dealer sends a price offer. */
    public void sendOffer(String negotiationId, double price, String messageText) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, price, messageText, NegotiationMessage.Type.OFFER);
        nm.setToAID(a.getBuyerAID());
        nm.setToName(a.getBuyerName());
        recordAndSend(nm, ACLMessage.PROPOSE, Ontology.TYPE_NEG_OFFER);
        log.send("Broker→" + a.getBuyerName(), Ontology.TYPE_NEG_OFFER,
                "RM " + String.format("%.0f", price)
                + (messageText.isBlank() ? "" : "  \"" + messageText + "\""));
    }

    /** Called when dealer accepts the buyer's last offer. */
    public void acceptOffer(String negotiationId, double price, String messageText) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, price, messageText, NegotiationMessage.Type.ACCEPT);
        nm.setToAID(a.getBuyerAID());
        nm.setToName(a.getBuyerName());
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

    /** Called when dealer rejects (ends the negotiation). */
    public void rejectOffer(String negotiationId, String messageText) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, 0, messageText, NegotiationMessage.Type.REJECT);
        nm.setToAID(a.getBuyerAID());
        nm.setToName(a.getBuyerName());
        recordAndSend(nm, ACLMessage.REFUSE, Ontology.TYPE_NEG_REJECT);
        log.info("Negotiation [" + negotiationId + "] REJECTED by dealer");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks from DealerMessageBehaviour
    // ─────────────────────────────────────────────────────────────────────────

    public void onAssignment(Assignment assignment) {
        assignments.put(assignment.getNegotiationId(), assignment);
        history.put(assignment.getNegotiationId(), new ArrayList<>());
        log.recv("Broker", Ontology.TYPE_ASSIGNMENT_NOTIFY,
                "Negotiation [" + assignment.getNegotiationId() + "]"
                + "  Buyer: " + assignment.getBuyerName()
                + "  Listing: " + assignment.getListing().getListingId());
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

    public void onListingAck(String listingId) {
        log.recv("Broker", Ontology.TYPE_LISTING_ACK, "Listing " + listingId + " confirmed stored");
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
        nm.setFromRole("DEALER");
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
    public List<CarListing>         getMyListings()               { return new ArrayList<>(myListings.values()); }
    public Map<String, Assignment>  getAssignments()              { return Collections.unmodifiableMap(assignments); }
    public List<NegotiationMessage> getHistory(String id)         { return history.getOrDefault(id, Collections.emptyList()); }
}
