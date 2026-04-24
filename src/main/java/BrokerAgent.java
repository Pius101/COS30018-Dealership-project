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
import negotiation.behaviours.broker.BrokerMessageBehaviour;
import negotiation.gui.BrokerGui;
import negotiation.messages.Ontology;
import negotiation.models.*;
import negotiation.util.AppLogger;
import negotiation.util.ConversationLogger;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Broker Agent (KA) — the platform's central hub.
 *
 * In V1 the broker operator (a human using the Broker GUI) is the decision-maker:
 *   1. Receives car listings from Dealer Agents → logs + stores + shows on dashboard
 *   2. Receives buyer requirements from Buyer Agents → logs + stores + shows on dashboard
 *   3. Lets the operator manually pair a listing with a buyer requirement
 *   4. Notifies both parties of the assignment → negotiation begins
 *   5. Routes all negotiation messages between dealer and buyer, logging each one
 *   6. Records completed deals (with final price)
 */
public class BrokerAgent extends Agent {

    // ── Shared utilities ──────────────────────────────────────────────────────
    public  final Gson      gson = new GsonBuilder().create();
    public  final AppLogger log  = new AppLogger("Broker");

    // ── Platform state (thread-safe maps) ─────────────────────────────────────
    private final Map<String, CarListing>     listings    = new ConcurrentHashMap<>();
    private final Map<String, CarRequirement> requirements = new ConcurrentHashMap<>();
    private final Map<String, Assignment>     assignments = new ConcurrentHashMap<>();
    private final Map<String, List<NegotiationMessage>> history = new ConcurrentHashMap<>();
    private final Map<String, NegotiationMessage> completedDeals = new ConcurrentHashMap<>();

    private BrokerGui gui;

    // ─────────────────────────────────────────────────────────────────────────
    // JADE Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void setup() {
        log.info("Broker Agent starting as " + getAID().getName());
        registerWithDF();
        addBehaviour(new BrokerMessageBehaviour(this));
        SwingUtilities.invokeLater(() -> {
            gui = new BrokerGui(this);
            log.setLogArea(gui.getLogArea());
            gui.setVisible(true);
            log.info("Platform ready — waiting for dealers and buyers.");
        });
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
        if (gui != null) SwingUtilities.invokeLater(gui::dispose);
        log.info("Broker shut down.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DF registration
    // ─────────────────────────────────────────────────────────────────────────

    private void registerWithDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(Ontology.BROKER_SERVICE_TYPE);
        sd.setName(Ontology.BROKER_SERVICE_NAME);
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            log.info("Registered in JADE DF as service type: " + Ontology.BROKER_SERVICE_TYPE);
        } catch (FIPAException fe) {
            log.error("DF registration failed: " + fe.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks from BrokerMessageBehaviour
    // ─────────────────────────────────────────────────────────────────────────

    public void onListingReceived(CarListing listing) {
        listings.put(listing.getListingId(), listing);
        log.recv(listing.getDealerName(), Ontology.TYPE_LISTING_REGISTER,
                listing.getYear() + " " + listing.getMake() + " " + listing.getModel()
                + "  RM " + String.format("%.0f", listing.getRetailPrice())
                + "  [ID: " + listing.getListingId() + "]");
        if (gui != null) SwingUtilities.invokeLater(() -> gui.refreshListings(getListings()));
    }

    public void onRequirementReceived(CarRequirement req) {
        requirements.put(req.getRequirementId(), req);
        log.recv(req.getBuyerName(), Ontology.TYPE_BUYER_REQUIREMENTS,
                req.summary() + "  [ID: " + req.getRequirementId() + "]");
        if (gui != null) SwingUtilities.invokeLater(() -> gui.refreshRequirements(getRequirements()));
    }

    public void onNegotiationMessage(NegotiationMessage msg) {
        history.computeIfAbsent(msg.getNegotiationId(), k -> new ArrayList<>()).add(msg);
        String detail = msg.getType() + "  RM " + String.format("%.0f", msg.getPrice())
                + (msg.getMessage() != null && !msg.getMessage().isBlank()
                   ? "  \"" + msg.getMessage() + "\"" : "");
        log.info("NEG [" + msg.getNegotiationId() + "]  "
                + msg.getFromName() + " → " + msg.getToName() + "  " + detail);
        if (gui != null) SwingUtilities.invokeLater(() -> gui.onNegotiationMessage(msg));
    }

    public void onDealComplete(NegotiationMessage finalMsg) {
        completedDeals.put(finalMsg.getNegotiationId(), finalMsg);
        
        // Remove the sold listing from available listings
        Assignment assignment = assignments.get(finalMsg.getNegotiationId());
        if (assignment != null) {
            String listingId = assignment.getListing().getListingId();
            CarListing soldListing = listings.remove(listingId);
            if (soldListing != null) {
                log.event("LISTING REMOVED  [" + listingId + "]"
                        + "  Car: " + soldListing.getMake() + " " + soldListing.getModel()
                        + "  Sold to: " + assignment.getBuyerName()
                        + "  Price: RM " + String.format("%.0f", finalMsg.getPrice()));
            }
        }
        
        log.event("DEAL COMPLETE  [" + finalMsg.getNegotiationId() + "]"
                + "  Final price: RM " + String.format("%.0f", finalMsg.getPrice()));
        if (gui != null) SwingUtilities.invokeLater(() -> gui.onDealComplete(finalMsg));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called by BrokerGui when operator clicks "Assign"
    // ─────────────────────────────────────────────────────────────────────────

    public void createAssignment(CarListing listing, CarRequirement requirement, String brokerNote) {
        Assignment a = new Assignment();
        
        // Generate unique negotiation ID to handle re-negotiations
        String uniqueNegotiationId = ConversationLogger.generateNegotiationId(
            requirement.getBuyerAID(), 
            listing.getDealerAID(), 
            listing.getListingId()
        );
        a.setNegotiationId(uniqueNegotiationId);
        
        a.setDealerAID(listing.getDealerAID());
        a.setDealerName(listing.getDealerName());
        a.setListing(listing);
        a.setBuyerAID(requirement.getBuyerAID());
        a.setBuyerName(requirement.getBuyerName());
        a.setRequirement(requirement);
        a.setBrokerNote(brokerNote);

        assignments.put(a.getNegotiationId(), a);
        history.put(a.getNegotiationId(), new ArrayList<>());

        log.event("Assignment created  [" + a.getNegotiationId() + "]"
                + "  Dealer: " + a.getDealerName()
                + "  ↔  Buyer: " + a.getBuyerName()
                + "  Listing: " + listing.getListingId());

        // Notify dealer
        ACLMessage toDealer = new ACLMessage(ACLMessage.INFORM);
        toDealer.addReceiver(new AID(listing.getDealerAID(), AID.ISGUID));
        toDealer.setOntology(Ontology.TYPE_ASSIGNMENT_NOTIFY);
        toDealer.setConversationId(Ontology.CONV_ASSIGNMENT);
        toDealer.setContent(gson.toJson(a));
        send(toDealer);
        log.send(a.getDealerName(), Ontology.TYPE_ASSIGNMENT_NOTIFY,
                "Buyer: " + a.getBuyerName() + "  Listing: " + listing.getListingId());

        // Notify buyer
        ACLMessage toBuyer = new ACLMessage(ACLMessage.INFORM);
        toBuyer.addReceiver(new AID(requirement.getBuyerAID(), AID.ISGUID));
        toBuyer.setOntology(Ontology.TYPE_ASSIGNMENT_NOTIFY);
        toBuyer.setConversationId(Ontology.CONV_ASSIGNMENT);
        toBuyer.setContent(gson.toJson(a));
        send(toBuyer);
        log.send(a.getBuyerName(), Ontology.TYPE_ASSIGNMENT_NOTIFY,
                "Dealer: " + a.getDealerName() + "  Listing: " + listing.getListingId());

        if (gui != null) SwingUtilities.invokeLater(() -> gui.refreshAssignments(getAssignments()));
    }

    public void routeNegotiationMessage(NegotiationMessage msg, String recipientAID) {
        onNegotiationMessage(msg);

        int performative = switch (msg.getType()) {
            case OFFER  -> ACLMessage.PROPOSE;
            case ACCEPT -> ACLMessage.ACCEPT_PROPOSAL;
            case REJECT -> ACLMessage.REFUSE;
        };
        String ontology = switch (msg.getType()) {
            case OFFER  -> Ontology.TYPE_NEG_OFFER;
            case ACCEPT -> Ontology.TYPE_NEG_ACCEPT;
            case REJECT -> Ontology.TYPE_NEG_REJECT;
        };

        ACLMessage fwd = new ACLMessage(performative);
        fwd.addReceiver(new AID(recipientAID, AID.ISGUID));
        fwd.setOntology(ontology);
        fwd.setConversationId(Ontology.CONV_NEGOTIATION + "-" + msg.getNegotiationId());
        fwd.setContent(gson.toJson(msg));
        send(fwd);

        log.send(msg.getToName(), ontology,
                "from " + msg.getFromName() + "  RM " + String.format("%.0f", msg.getPrice()));
    }

    public void closeDeal(NegotiationMessage acceptMsg) {
        onDealComplete(acceptMsg);
        Assignment a = assignments.get(acceptMsg.getNegotiationId());

        String dealerAID = a != null ? a.getDealerAID() : acceptMsg.getToAID();
        String buyerAID  = a != null ? a.getBuyerAID()  : acceptMsg.getFromAID();
        String dealerName = a != null ? a.getDealerName() : "dealer";
        String buyerName  = a != null ? a.getBuyerName()  : "buyer";

        // Log the completed conversation
        Assignment assignment = assignments.get(acceptMsg.getNegotiationId());
        if (assignment != null) {
            ConversationLogger.markCompleted(acceptMsg.getNegotiationId());
            log.info("Conversation saved: " + acceptMsg.getNegotiationId());
        }

        for (String[] party : new String[][]{{dealerAID, dealerName}, {buyerAID, buyerName}}) {
            ACLMessage notif = new ACLMessage(ACLMessage.INFORM);
            notif.addReceiver(new AID(party[0], AID.ISGUID));
            notif.setOntology(Ontology.TYPE_DEAL_COMPLETE);
            notif.setConversationId(Ontology.CONV_NEGOTIATION + "-" + acceptMsg.getNegotiationId());
            notif.setContent(gson.toJson(acceptMsg));
            send(notif);
            log.send(party[1], Ontology.TYPE_DEAL_COMPLETE,
                    "Final price: RM " + String.format("%.0f", acceptMsg.getPrice()));
        }
    }

    /** Opens the JADE Remote Monitoring Agent (agent monitor / platform view). */
    public void launchJadeRma() {
        launchTool("rma-" + System.currentTimeMillis(), "jade.tools.rma.rma");
    }

    /**
     * Opens the JADE Sniffer Agent — shows a live sequence diagram of every
     * ACL message flowing between agents.  Drag agents into the sniff area.
     */
    public void launchSniffer() {
        launchTool("sniffer-" + System.currentTimeMillis(), "jade.tools.Sniffer.Sniffer");
    }

    /**
     * Opens a JADE DummyAgent — a manual message composer.
     * Useful for sending test messages directly to the broker or any other agent.
     * Set Ontology to one of the Ontology.TYPE_* constants and Content to JSON.
     */
    public void launchDummyAgent() {
        launchTool("dummy-" + System.currentTimeMillis(), "jade.tools.DummyAgent.DummyAgent");
    }

    private void launchTool(String name, String className) {
        try {
            // getContainerController() is available on every JADE Agent instance
            jade.wrapper.AgentContainer c = getContainerController();
            jade.wrapper.AgentController tool = c.createNewAgent(name, className, new Object[0]);
            tool.start();
            log.info("Launched JADE tool: " + className);
        } catch (Exception e) {
            log.error("Failed to launch " + className + ": " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessors
    // ─────────────────────────────────────────────────────────────────────────

    public Collection<CarListing>      getListings()      { return Collections.unmodifiableCollection(listings.values()); }
    public Collection<CarRequirement>  getRequirements()  { return Collections.unmodifiableCollection(requirements.values()); }
    public Collection<Assignment>      getAssignments()   { return Collections.unmodifiableCollection(assignments.values()); }
    public Map<String, Assignment>     getAssignmentsMap() { return assignments; }
    public List<NegotiationMessage>    getHistory(String id) {
        return Collections.unmodifiableList(history.getOrDefault(id, Collections.emptyList()));
    }
    public Collection<NegotiationMessage> getCompletedDeals() {
        return Collections.unmodifiableCollection(completedDeals.values());
    }
    public CarListing getListing(String id) { return listings.get(id); }
}
