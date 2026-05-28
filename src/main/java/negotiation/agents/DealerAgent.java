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
import negotiation.behaviours.DealerMessageBehaviour;
import negotiation.behaviours.DealerNegotiationBehaviour;
import negotiation.behaviours.DealerNegotiationBehaviour.AutoDealerParams;
import negotiation.gui.DealerGui;
import negotiation.messages.Ontology;
import negotiation.models.Assignment;
import negotiation.models.CarListing;
import negotiation.models.NegotiationMessage;
import negotiation.util.AppLogger;
import negotiation.util.GuiMode;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Dealer Agent (DA) — represents a car dealer on the platform.
 *
 * Supports both manual negotiation (via GUI) and automated negotiation
 * (via DealerNegotiationBehaviour driven by the strategy layer).
 */
public class DealerAgent extends Agent {

    public final Gson      gson = new GsonBuilder().create();
    public       AppLogger log  = new AppLogger("Dealer");

    private final Map<String, CarListing>               myListings  = new ConcurrentHashMap<>();
    private final Map<String, Assignment>               assignments = new ConcurrentHashMap<>();
    private final Map<String, List<NegotiationMessage>> history     = new ConcurrentHashMap<>();

    // ── Auto-negotiation state ────────────────────────────────────────────────
    private final Map<String, ConcurrentLinkedQueue<NegotiationMessage>> autoQueues
            = new ConcurrentHashMap<>();
    private boolean autoDealerMode        = false;
    private String  autoDealerStrategyKey = null; // Will be set from config
    private double  autoDealerMarginPct   = 0.10;

    private AID       brokerAID;
    private DealerGui gui;

    // Bug fix #3: proper POJO for Gson
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
        log = new AppLogger("Dealer:" + getLocalName());
        brokerAID = findBroker();
        addBehaviour(new DealerMessageBehaviour(this));

        if (GuiMode.isEnabled()) {
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
        } else {
            logStartupState();
        }

        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] instanceof String configPath) {
            loadConfigAndSubmitListings(configPath);
        }
    }

    @Override
    protected void takeDown() {
        log.info("Dealer '" + getLocalName() + "' shutting down.");
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
                log.info("Emergency save sent — " + payload.ongoingNegotiations.length
                        + " ongoing negotiations");
            } catch (Exception e) {
                log.error("Failed to send emergency save", e);
            }
        }
        if (gui != null) SwingUtilities.invokeLater(gui::dispose);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Config loading (used by spawn script)
    // ─────────────────────────────────────────────────────────────────────────

    private void loadConfigAndSubmitListings(String configPath) {
        try {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of(configPath));
            DealerConfig config = gson.fromJson(json, DealerConfig.class);
            if (config == null || config.listings == null) {
                log.error("Config file empty or invalid: " + configPath);
                return;
            }
            log.info("Loading config from " + configPath
                    + " — " + config.listings.size() + " listings");

            // Set auto mode BEFORE submitting listings so it's ready when assignments arrive
            if (config.autoNegotiate) {
                String stratKey = config.strategy != null ? config.strategy : getRandomStrategy();
                double margin   = config.marginPct > 0 ? config.marginPct : 0.10;
                setAutoMode(true, stratKey, margin);
                log.info("[AUTO-DEALER] Auto mode armed: strategy=" + stratKey
                        + " margin=" + String.format("%.0f%%", margin * 100));
            }

            doWait(1500);

            for (DealerConfig.ListingSpec spec : config.listings) {
                CarListing listing = new CarListing(
                        spec.make, spec.model, spec.year, spec.mileage,
                        spec.color, spec.retailPrice, spec.condition, spec.description);
                submitListing(listing);
                doWait(200);
            }
            log.info("All listings submitted from config.");

        } catch (java.io.IOException e) {
            log.error("Cannot read config file: " + configPath + " — " + e.getMessage());
        } catch (Exception e) {
            log.error("Config parse error for " + configPath, e);
        }
    }

    private static class DealerConfig {
        String  dealerName;
        String  brand;
        boolean autoSubmit;
        boolean autoNegotiate;
        String  strategy;
        double  marginPct;
        int     maxRounds;
        java.util.List<ListingSpec> listings;

        static class ListingSpec {
            String make, model, color, condition, description;
            int    year, mileage;
            double retailPrice, marketPrice;
        }
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

        if (gui != null) {
            SwingUtilities.invokeLater(() -> gui.refreshMyListings(new ArrayList<>(myListings.values())));
        }
    }

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

        NegotiationMessage nm = buildMsg(negotiationId, a, price, messageText,
                NegotiationMessage.Type.OFFER);
        nm.setToAID(a.getBuyerAID());
        nm.setToName(a.getBuyerName());
        setAuditMetadata(nm, round, strategy, "ONGOING", reason, utility);
        recordAndSend(nm, ACLMessage.PROPOSE, Ontology.TYPE_NEG_OFFER);
        log.send("Broker→" + a.getBuyerName(), Ontology.TYPE_NEG_OFFER,
                "[" + negotiationId + "] ACL.PROPOSE RM " + String.format("%.0f", price)
                        + (isBlank(messageText) ? "" : "  \"" + messageText + "\""));
    }

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

        NegotiationMessage nm = buildMsg(negotiationId, a, price, messageText,
                NegotiationMessage.Type.ACCEPT);
        nm.setToAID(a.getBuyerAID());
        nm.setToName(a.getBuyerName());
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

        NegotiationMessage nm = buildMsg(negotiationId, a, 0, messageText,
                NegotiationMessage.Type.REJECT);
        nm.setToAID(a.getBuyerAID());
        nm.setToName(a.getBuyerName());
        setAuditMetadata(nm, round, strategy, "FAILED", reason, utility);
        recordAndSend(nm, ACLMessage.REFUSE, Ontology.TYPE_NEG_REJECT);
        log.negotiation("[" + negotiationId + "] Dealer rejected negotiation");
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

        if (autoDealerMode) {
            // Store maxRounds in Assignment for report generation (using default 10 for auto-dealers)
            assignment.setMaxRounds(10);
            startAutoNegotiation(assignment);
        }
    }

    public void onNegotiationMessage(NegotiationMessage msg) {
        recordLocal(msg);
        log.recv("Broker←" + msg.getFromName(), msg.getType().name(),
                "[" + msg.getNegotiationId() + "] "
                        + aclLabel(msg) + " RM " + String.format("%.0f", msg.getPrice())
                        + (msg.getMessage() != null && !msg.getMessage().isBlank()
                        ? "  \"" + msg.getMessage() + "\"" : ""));

        ConcurrentLinkedQueue<NegotiationMessage> q = autoQueues.get(msg.getNegotiationId());
        if (q != null) {
            q.add(msg);
        }
        // Always show in GUI regardless of auto/manual
        if (gui != null) SwingUtilities.invokeLater(() -> gui.appendNegotiationMessage(msg));
    }

    public void onDealComplete(NegotiationMessage finalMsg) {
        recordLocal(finalMsg);
        log.event("DEAL COMPLETE  [" + finalMsg.getNegotiationId() + "]"
                + "  Final price: RM " + String.format("%.0f", finalMsg.getPrice()));
        if (gui != null) SwingUtilities.invokeLater(() -> gui.onDealComplete(finalMsg));
    }

    public void onListingAck(String listingId) {
        log.recv("Broker", Ontology.TYPE_LISTING_ACK,
                "Listing " + listingId + " confirmed stored");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private NegotiationMessage buildMsg(String negotiationId, Assignment a,
                                        double price, String text,
                                        NegotiationMessage.Type type) {
        NegotiationMessage nm = new NegotiationMessage();
        nm.setNegotiationId(negotiationId);
        nm.setConversationId(Ontology.CONV_NEGOTIATION + "-" + negotiationId);
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
        log.info("Dealer Agent '" + getLocalName() + "' started. AID: " + getAID().getName());
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

    public void startAutoNegotiation(Assignment assignment) {
        String negId = assignment.getNegotiationId();
        ConcurrentLinkedQueue<NegotiationMessage> queue = new ConcurrentLinkedQueue<>();
        autoQueues.put(negId, queue);

        AutoDealerParams params = AutoDealerParams.withMargin(
                autoDealerStrategyKey,
                assignment.getListing().getRetailPrice(),
                autoDealerMarginPct,
                10);

        DealerNegotiationBehaviour behaviour =
                new DealerNegotiationBehaviour(this, assignment, params, queue);
        addBehaviour(behaviour);
        log.info("[AUTO-DEALER] DealerNegotiationBehaviour started for [" + negId + "]");
    }

    public void setAutoMode(boolean enabled, String strategyKey, double marginPct) {
        this.autoDealerMode        = enabled;
        this.autoDealerStrategyKey = strategyKey;
        this.autoDealerMarginPct   = marginPct;
        log.info("[AUTO-DEALER] Auto mode " + (enabled ? "ENABLED" : "DISABLED")
                + " | Strategy: " + strategyKey
                + " | Margin: " + String.format("%.0f%%", marginPct * 100));
    }

    /** Get a random strategy from available strategies */
    private String getRandomStrategy() {
        String[] strategies = {"TIME_DEPENDENT_BOULWARE", "TIT_FOR_TAT", "FIXED_INCREMENT_5PCT"};
        int index = (int) (Math.random() * strategies.length);
        return strategies[index];
    }

    public boolean isAutoMode() { return autoDealerMode; }

    // ── Accessors ─────────────────────────────────────────────────────────────
    public List<CarListing>         getMyListings()          { return new ArrayList<>(myListings.values()); }
    public Map<String, Assignment>  getAssignments()         { return Collections.unmodifiableMap(assignments); }
    public List<NegotiationMessage> getHistory(String id)    { return history.getOrDefault(id, Collections.emptyList()); }
    public DealerGui                getGui()                 { return gui; }

} // ← only ONE closing brace for the class, here at the very end
