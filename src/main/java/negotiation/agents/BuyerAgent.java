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
import negotiation.behaviours.BuyerMessageBehaviour;
import negotiation.gui.BuyerGui;
import negotiation.messages.Ontology;
import negotiation.models.Assignment;
import negotiation.models.CarRequirement;
import negotiation.models.NegotiationMessage;
import negotiation.util.AppLogger;

import negotiation.behaviours.AutoNegotiationBehaviour;
import negotiation.behaviours.AutoNegotiationBehaviour.AutoNegotiationParams;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    // ── Auto-negotiation state ────────────────────────────────────────────────
    // Queue per negotiation: BuyerMessageBehaviour feeds messages in,
    // AutoNegotiationBehaviour reads them out.
    private final Map<String, ConcurrentLinkedQueue<NegotiationMessage>> autoQueues
            = new ConcurrentHashMap<>();
    // Auto params stored from config until an assignment arrives
    private AutoNegotiationParams pendingAutoParams = null;

    private AID      brokerAID;
    private BuyerGui gui;

    // Bug fix #3: proper POJO for Gson — anonymous classes always serialise to {}
    private static class EmergencyPayload {
        String   agentAID;
        String   agentName;
        String   reason;
        String[] ongoingNegotiations;
    }

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

        // Auto-load requirements from config file if passed as agent argument.
        // The spawn script passes the config path as args[0].
        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] instanceof String configPath) {
            loadConfigAndSubmitRequirements(configPath);
        }
    }

    @Override
    protected void takeDown() {
        log.info("Buyer '" + getLocalName() + "' shutting down.");

        // Bug fix #3: use proper POJO so Gson serialises correctly
        if (brokerAID != null && !assignments.isEmpty()) {
            try {
                EmergencyPayload payload = new EmergencyPayload();
                payload.agentAID            = getAID().getName();
                payload.agentName           = getLocalName();
                payload.reason              = "shutdown";
                payload.ongoingNegotiations = assignments.keySet().toArray(new String[0]);

                ACLMessage emergencySave = new ACLMessage(ACLMessage.INFORM);
                emergencySave.addReceiver(brokerAID);
                emergencySave.setOntology("EMERGENCY_SAVE");
                emergencySave.setContent(gson.toJson(payload));
                send(emergencySave);
                log.info("Emergency save sent — " + payload.ongoingNegotiations.length + " ongoing negotiations");
            } catch (Exception e) {
                log.error("Failed to send emergency save: " + e.getMessage());
            }
        }

        if (gui != null) SwingUtilities.invokeLater(gui::dispose);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Config loading (used by spawn script / headless mode)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads a JSON config file and auto-submits requirements to the broker.
     * Config format matches what spawn_agents.py writes.
     */
    private void loadConfigAndSubmitRequirements(String configPath) {
        try {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of(configPath));
            BuyerConfig config = gson.fromJson(json, BuyerConfig.class);
            if (config == null) {
                log.error("Config file empty or invalid: " + configPath);
                return;
            }
            log.info("Loading config from " + configPath);

            // Wait a moment for broker to be ready
            doWait(2000);

            CarRequirement req = new CarRequirement();
            req.setMake(config.make != null ? config.make : "");
            req.setModel(config.model != null ? config.model : "");
            req.setYearMin(config.yearMin);
            req.setYearMax(config.yearMax);
            req.setMaxPrice(config.maxPrice);
            req.setMaxMileage(config.maxMileage);
            req.setCondition(config.condition != null ? config.condition : "Any");
            req.setNotes(config.notes != null ? config.notes : "");

            submitRequirement(req);
            log.info("Requirements submitted from config: " + req.summary());

            // Store auto-negotiation params so onAssignment() can use them
            // when the broker eventually assigns a dealer to us.
            if (config.autoNegotiate) {
                pendingAutoParams = AutoNegotiationParams.fromConfig(
                        config.strategy,
                        config.firstOffer,
                        config.reservationPrice,
                        config.maxRounds);
                log.info("[AUTO] Auto-negotiate armed: strategy=" + config.strategy
                        + " firstOffer=RM" + String.format("%.0f", config.firstOffer)
                        + " budget=RM" + String.format("%.0f", config.reservationPrice)
                        + " maxRounds=" + config.maxRounds);
            }

        } catch (java.io.IOException e) {
            log.error("Cannot read config file: " + configPath + " — " + e.getMessage());
        } catch (Exception e) {
            log.error("Config parse error: " + e.getMessage());
        }
    }

    /** Inner class matching the JSON structure written by spawn_agents.py. */
    private static class BuyerConfig {
        String name, make, model, condition, notes, strategy;
        int yearMin, yearMax, maxMileage, maxRounds;
        double maxPrice, firstOffer, reservationPrice;
        boolean autoNegotiate;
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

        // Always open the GUI tab so progress is visible (even in auto mode)
        if (gui != null) SwingUtilities.invokeLater(() -> gui.openNegotiationTab(assignment));

        // If auto params are waiting, start the automated negotiation behaviour
        if (pendingAutoParams != null) {
            startAutoNegotiation(assignment, pendingAutoParams);
            pendingAutoParams = null; // consumed — don't reuse for another assignment
        }
    }

    public void onNegotiationMessage(NegotiationMessage msg) {
        recordLocal(msg);
        log.recv("Broker←" + msg.getFromName(), msg.getType().name(),
                "RM " + String.format("%.0f", msg.getPrice())
                        + (msg.getMessage() != null && !msg.getMessage().isBlank()
                        ? "  \"" + msg.getMessage() + "\"" : ""));

        // If there is an active auto-behaviour for this negotiation,
        // feed the message into its queue — the behaviour will handle it.
        // Otherwise, route to the GUI for manual response.
        ConcurrentLinkedQueue<NegotiationMessage> q = autoQueues.get(msg.getNegotiationId());
        if (q != null) {
            q.add(msg); // AutoNegotiationBehaviour picks this up on next poll
            // Still update the GUI so the dealer's offer is visible in the chat
            if (gui != null) SwingUtilities.invokeLater(() -> gui.appendNegotiationMessage(msg));
        } else {
            if (gui != null) SwingUtilities.invokeLater(() -> gui.appendNegotiationMessage(msg));
        }
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

    // ── Auto-negotiation ──────────────────────────────────────────────────────

    /**
     * Starts an AutoNegotiationBehaviour for the given assignment.
     * Creates a message queue for that negotiation and adds the behaviour to JADE.
     *
     * Called automatically from onAssignment() when pendingAutoParams is set.
     * Can also be called manually from the GUI auto panel (future V2 GUI feature).
     */
    public void startAutoNegotiation(Assignment assignment, AutoNegotiationParams params) {
        String negId = assignment.getNegotiationId();
        // Create the queue this behaviour will read from
        ConcurrentLinkedQueue<NegotiationMessage> queue = new ConcurrentLinkedQueue<>();
        autoQueues.put(negId, queue);
        // Create and register the behaviour with JADE
        AutoNegotiationBehaviour behaviour = new AutoNegotiationBehaviour(
                this, assignment, params, queue);
        addBehaviour(behaviour);
        log.info("[AUTO] AutoNegotiationBehaviour started for negotiation [" + negId + "]");
    }

    // ── Accessors ────────────────────────────────────────────────────────────
    public List<CarRequirement>     getMyRequirements()          { return new ArrayList<>(myRequirements.values()); }
    public Map<String, Assignment>  getAssignments()             { return Collections.unmodifiableMap(assignments); }
    public List<NegotiationMessage> getHistory(String id)        { return history.getOrDefault(id, Collections.emptyList()); }
    public BuyerGui                 getGui()                     { return gui; }
}