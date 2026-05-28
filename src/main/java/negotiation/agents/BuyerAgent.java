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
import negotiation.models.BuyerShortlistEntry;
import negotiation.models.BuyerShortlistMessage;
import negotiation.models.CarListing;
import negotiation.models.CarRequirement;
import negotiation.models.MatchingResults;
import negotiation.models.NegotiationMessage;
import negotiation.util.AppLogger;
import negotiation.util.GuiMode;

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

    // ── Extension 1: concurrent-negotiation coordination ──────────────────────
    // The buyer can run up to 3 negotiations at once (one per shortlisted dealer).
    // These maps let the negotiations inform each other (shared BATNA) and let the
    // buyer commit to the best dealer and cancel the rest.
    private final Map<String, AutoNegotiationBehaviour> activeAuto        = new ConcurrentHashMap<>();
    private final Map<String, String>                   negToRequirement  = new ConcurrentHashMap<>();
    private final Map<String, Double>                   latestDealerOffer = new ConcurrentHashMap<>();
    private final java.util.Set<String>                 settledRequirements
            = ConcurrentHashMap.newKeySet();

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
        if (GuiMode.isEnabled()) {
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
        } else {
            logStartupState();
        }

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
                log.error("Failed to send emergency save", e);
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
            log.error("Config parse error for " + configPath, e);
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
        } catch (FIPAException fe) {
            log.error("Broker DF lookup failed", fe);
        }
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
        if (gui != null) {
            SwingUtilities.invokeLater(() -> gui.onRequirementSubmitted(req));
        }
    }

    /** Called by the negotiation chat panel when buyer sends a counter-offer. */
    public void sendOffer(String negotiationId, double price, String messageText) {
        sendOffer(negotiationId, price, messageText, 0, null, null, null);
    }

    public void sendOffer(String negotiationId,
                          double price,
                          String messageText,
                          int round,
                          String strategy,
                          String reason,
                          Double utility) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, price, messageText, NegotiationMessage.Type.OFFER);
        nm.setToAID(a.getDealerAID());
        nm.setToName(a.getDealerName());
        setAuditMetadata(nm, round, strategy, "ONGOING", reason, utility);
        recordAndSend(nm, ACLMessage.PROPOSE, Ontology.TYPE_NEG_OFFER);
        log.send("Broker→" + a.getDealerName(), Ontology.TYPE_NEG_OFFER,
                "[" + negotiationId + "] ACL.PROPOSE RM " + String.format("%.0f", price)
                        + (isBlank(messageText) ? "" : "  \"" + messageText + "\""));
    }

    /** Called when buyer accepts the dealer's last offer. */
    public void acceptOffer(String negotiationId, double price, String messageText) {
        acceptOffer(negotiationId, price, messageText, 0, null, null, null);
    }

    public void acceptOffer(String negotiationId,
                            double price,
                            String messageText,
                            int round,
                            String strategy,
                            String reason,
                            Double utility) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, price, messageText, NegotiationMessage.Type.ACCEPT);
        nm.setToAID(a.getDealerAID());
        nm.setToName(a.getDealerName());
        setAuditMetadata(nm, round, strategy, "COMPLETED", reason, utility);
        setTransportMetadata(nm, ACLMessage.ACCEPT_PROPOSAL);
        recordLocal(nm);

        ACLMessage msg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
        msg.addReceiver(brokerAID);
        msg.setOntology(Ontology.TYPE_NEG_ACCEPT);
        msg.setConversationId(Ontology.CONV_NEGOTIATION);
        msg.setContent(gson.toJson(nm));
        send(msg);
        log.send("Broker", Ontology.TYPE_NEG_ACCEPT,
                "[" + negotiationId + "] ACL.ACCEPT_PROPOSAL ACCEPTED at RM " + String.format("%.0f", price));
    }

    /** Called when buyer rejects (ends the negotiation). */
    public void rejectOffer(String negotiationId, String messageText) {
        rejectOffer(negotiationId, messageText, 0, null, null, null);
    }

    public void rejectOffer(String negotiationId,
                            String messageText,
                            int round,
                            String strategy,
                            String reason,
                            Double utility) {
        if (!ensureBroker()) return;
        Assignment a = assignments.get(negotiationId);
        if (a == null) return;

        NegotiationMessage nm = buildMsg(negotiationId, a, 0, messageText, NegotiationMessage.Type.REJECT);
        nm.setToAID(a.getDealerAID());
        nm.setToName(a.getDealerName());
        setAuditMetadata(nm, round, strategy, "FAILED", reason, utility);
        recordAndSend(nm, ACLMessage.REFUSE, Ontology.TYPE_NEG_REJECT);
        log.negotiation("[" + negotiationId + "] Buyer rejected negotiation");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks from BuyerMessageBehaviour
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Phase B of the spec protocol: the broker returned the dealers/cars matching
     * our specifications. We pick up to three (per the brief) that we are not
     * already negotiating, attach a first offer to each, and reply with a
     * BUYER_SHORTLIST (ACL PROPOSE).
     */
    public void onMatchResults(MatchingResults mr) {
        log.recv("Broker", Ontology.TYPE_MATCH_RESULTS,
                (mr.getMatches() == null ? 0 : mr.getMatches().size()) + " match(es)");

        BuyerShortlistMessage sl = new BuyerShortlistMessage();
        sl.setRequirementId(mr.getRequirementId());
        sl.setBuyerAID(getAID().getName());
        sl.setBuyerName(getLocalName());

        int picked = 0;
        if (mr.getMatches() != null) {
            for (CarListing l : mr.getMatches()) {
                if (picked >= 3) break;                       // spec: up to three dealers
                if (alreadyNegotiating(l.getListingId())) continue;

                double first = (pendingAutoParams != null && pendingAutoParams.firstOffer > 0)
                        ? pendingAutoParams.firstOffer
                        : l.getRetailPrice() * 0.85;          // sensible default opening bid

                BuyerShortlistEntry e = new BuyerShortlistEntry();
                e.setListingId(l.getListingId());
                e.setDealerAID(l.getDealerAID());
                e.setFirstOffer(first);
                sl.getEntries().add(e);
                picked++;
            }
        }

        if (sl.getEntries().isEmpty()) {
            log.info("[MATCH] No new cars to shortlist for requirement " + mr.getRequirementId());
            return;
        }

        ACLMessage m = new ACLMessage(ACLMessage.PROPOSE);
        m.addReceiver(brokerAID);
        m.setOntology(Ontology.TYPE_BUYER_SHORTLIST);
        m.setConversationId(Ontology.CONV_MATCHING);
        m.setContent(gson.toJson(sl));
        send(m);
        log.send("Broker", Ontology.TYPE_BUYER_SHORTLIST,
                sl.getEntries().size() + " car(s) shortlisted");
    }

    /** True if we already have an assignment/negotiation for this listing. */
    private boolean alreadyNegotiating(String listingId) {
        return assignments.values().stream()
                .anyMatch(a -> a.getListing() != null
                        && listingId.equals(a.getListing().getListingId()));
    }

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

        // If auto params are configured, start automated negotiation for THIS assignment.
        // Extension 1: we deliberately do NOT clear pendingAutoParams, so every dealer
        // the broker assigns to this buyer (up to 3) spins up its own concurrent
        // negotiation. We skip assignments for a requirement that already closed a deal.
        if (pendingAutoParams != null) {
            String reqId = assignment.getRequirement() != null
                    ? assignment.getRequirement().getRequirementId() : null;
            if (isRequirementSettled(reqId)) {
                log.info("[AUTO][CONCURRENT] Requirement " + reqId
                        + " already settled — skipping negotiation ["
                        + assignment.getNegotiationId() + "]");
            } else {
                assignment.setMaxRounds(pendingAutoParams.maxRounds);
                startAutoNegotiation(assignment, pendingAutoParams);
            }
        }
    }

    public void onNegotiationMessage(NegotiationMessage msg) {
        recordLocal(msg);
        log.recv("Broker←" + msg.getFromName(), msg.getType().name(),
                "[" + msg.getNegotiationId() + "] "
                        + aclLabel(msg) + " RM " + String.format("%.0f", msg.getPrice())
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
        nm.setConversationId(Ontology.CONV_NEGOTIATION + "-" + negotiationId);
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
        setTransportMetadata(nm, performative);
        recordLocal(nm);
        ACLMessage msg = new ACLMessage(performative);
        msg.addReceiver(brokerAID);
        msg.setOntology(ontology);
        msg.setConversationId(Ontology.CONV_NEGOTIATION);
        msg.setContent(gson.toJson(nm));
        send(msg);
    }

    private void setAuditMetadata(NegotiationMessage nm,
                                  int round,
                                  String strategy,
                                  String outcome,
                                  String reason,
                                  Double utility) {
        if (round > 0) {
            nm.setRound(round);
        }
        if (!isBlank(strategy)) {
            nm.setStrategy(strategy);
        }
        if (!isBlank(outcome)) {
            nm.setOutcome(outcome);
        }
        if (!isBlank(reason)) {
            nm.setReason(reason);
        }
        if (utility != null && !utility.isNaN() && !utility.isInfinite()) {
            nm.setUtility(utility);
        }
    }

    private void setTransportMetadata(NegotiationMessage nm, int performative) {
        nm.setAclPerformative(aclPerformativeName(performative));
        if (isBlank(nm.getConversationId())) {
            nm.setConversationId(Ontology.CONV_NEGOTIATION + "-" + nm.getNegotiationId());
        }
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

    private static String aclLabel(NegotiationMessage msg) {
        return isBlank(msg.getAclPerformative()) ? "ACL.UNKNOWN" : "ACL." + msg.getAclPerformative();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void recordLocal(NegotiationMessage nm) {
        history.computeIfAbsent(nm.getNegotiationId(), k -> new ArrayList<>()).add(nm);
    }

    private void logStartupState() {
        log.info("Buyer Agent '" + getLocalName() + "' started. AID: " + getAID().getName());
        if (brokerAID != null) {
            log.info("Connected to Broker: " + brokerAID.getName());
        } else {
            log.error("Broker not found in DF - check the HOST is running!");
        }
    }

    private boolean ensureBroker() {
        if (brokerAID != null) return true;
        brokerAID = findBroker();
        if (brokerAID == null) {
            log.error("Cannot find Broker Agent — is the HOST running?");
            if (gui != null) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(gui,
                                "Cannot reach the Broker Agent.\nMake sure the HOST is running.",
                                "Connection Error", JOptionPane.ERROR_MESSAGE));
            }
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
        // Extension 1: register in the concurrent-negotiation coordinator
        activeAuto.put(negId, behaviour);
        if (assignment.getRequirement() != null) {
            negToRequirement.put(negId, assignment.getRequirement().getRequirementId());
        }
        addBehaviour(behaviour);
        log.info("[AUTO] AutoNegotiationBehaviour started for negotiation [" + negId + "]");
    }

    // ── Extension 1: concurrent-negotiation coordination ──────────────────────

    /** A negotiation just saw a new dealer offer — remember it as a possible alternative. */
    public void recordDealerOffer(String negotiationId, double price) {
        latestDealerOffer.put(negotiationId, price);
    }

    /**
     * The best alternative price (lowest dealer offer) currently on the table from the
     * buyer's OTHER live negotiations for the same requirement — i.e. the BATNA for the
     * given negotiation. Returns Double.MAX_VALUE when there is no alternative yet.
     */
    public double bestAlternativeFor(String negotiationId) {
        String reqId = negToRequirement.get(negotiationId);
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, Double> e : latestDealerOffer.entrySet()) {
            if (e.getKey().equals(negotiationId)) continue;
            if (reqId != null && !reqId.equals(negToRequirement.get(e.getKey()))) continue;
            best = Math.min(best, e.getValue());
        }
        return best;
    }

    /**
     * Called when one negotiation closes a deal. The buyer only needs one car, so we
     * mark the requirement settled and cancel the sibling negotiations for it.
     */
    public void commitDealAndCancelSiblings(String winningNegId) {
        String reqId = negToRequirement.get(winningNegId);
        if (reqId == null) return;
        settledRequirements.add(reqId);
        for (Map.Entry<String, String> e : negToRequirement.entrySet()) {
            String negId = e.getKey();
            if (negId.equals(winningNegId)) continue;
            if (!reqId.equals(e.getValue())) continue;
            AutoNegotiationBehaviour sibling = activeAuto.get(negId);
            if (sibling != null) sibling.cancelDueToBetterDeal(winningNegId);
        }
    }

    /** Remove a finished negotiation from the active-behaviour registry. */
    public void deregisterAuto(String negotiationId) {
        activeAuto.remove(negotiationId);
    }

    /** True once any negotiation for this requirement has closed a deal. */
    public boolean isRequirementSettled(String requirementId) {
        return requirementId != null && settledRequirements.contains(requirementId);
    }

    // ── Accessors ────────────────────────────────────────────────────────────
    public List<CarRequirement>     getMyRequirements()          { return new ArrayList<>(myRequirements.values()); }
    public Map<String, Assignment>  getAssignments()             { return Collections.unmodifiableMap(assignments); }
    public List<NegotiationMessage> getHistory(String id)        { return history.getOrDefault(id, Collections.emptyList()); }
    public BuyerGui                 getGui()                     { return gui; }
}