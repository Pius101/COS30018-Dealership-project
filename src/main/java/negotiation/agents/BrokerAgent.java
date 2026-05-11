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
import negotiation.behaviours.BrokerMessageBehaviour;
import negotiation.gui.BrokerGui;
import negotiation.messages.Ontology;
import negotiation.models.*;
import negotiation.report.NegotiationReportGenerator;
import negotiation.testing.NegotiationTestLogger;
import negotiation.util.AppLogger;
import negotiation.util.ConversationLogger;
import negotiation.util.GuiMode;

import javax.swing.*;
import java.io.IOException;
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
    private static final String AUTO_ASSIGN_NOTE = "Automatically matched by Broker Agent.";

    private BrokerGui gui;

    // ─────────────────────────────────────────────────────────────────────────
    // JADE Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void setup() {
        log.info("Broker Agent starting as " + getAID().getName());
        registerWithDF();
        addBehaviour(new BrokerMessageBehaviour(this));
        if (GuiMode.isEnabled()) {
            SwingUtilities.invokeLater(() -> {
                gui = new BrokerGui(this);
                log.setLogArea(gui.getLogArea());
                gui.setVisible(true);
            log.info("Platform ready — waiting for dealers and buyers.");
            });
        } else {
            log.info("Platform ready in headless mode - waiting for dealers and buyers.");
        }
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
            log.error("DF registration failed", fe);
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
        autoAssignMatchesForListing(listing);
    }

    public void onRequirementReceived(CarRequirement req) {
        requirements.put(req.getRequirementId(), req);
        log.recv(req.getBuyerName(), Ontology.TYPE_BUYER_REQUIREMENTS,
                req.summary() + "  [ID: " + req.getRequirementId() + "]");
        if (gui != null) SwingUtilities.invokeLater(() -> gui.refreshRequirements(getRequirements()));
        autoAssignMatchesForRequirement(req);
    }

    public void onNegotiationMessage(NegotiationMessage msg) {
        history.computeIfAbsent(msg.getNegotiationId(), k -> new ArrayList<>()).add(msg);
        String detail = aclLabel(msg) + "  " + msg.getType()
                + "  RM " + String.format("%.0f", msg.getPrice())
                + (msg.getMessage() != null && !msg.getMessage().isBlank()
                ? "  \"" + msg.getMessage() + "\"" : "");
        log.negotiation("[" + msg.getNegotiationId() + "]  "
                + msg.getFromName() + " → " + msg.getToName() + "  " + detail);

        // Bug fix #4: actually write each message to the conversation file.
        // Previously logMessage() was defined but never called here.
        Assignment assignment = assignments.get(msg.getNegotiationId());
        if (assignment != null) {
            ConversationLogger.logMessage(msg, assignment);
            refreshNegotiationTestReport();
        }

        if (gui != null) SwingUtilities.invokeLater(() -> gui.onNegotiationMessage(msg));
    }

    public void refreshNegotiationTestReport() {
        try {
            NegotiationTestLogger.refreshDefaultReports();
        } catch (IOException e) {
            log.error("Failed to refresh negotiation test report", e);
        }
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

        // Generate HTML report
        generateReport(finalMsg, assignment);
    }

    private void generateReport(NegotiationMessage finalMsg, Assignment assignment) {
        if (assignment == null) return;

        NegotiationReportGenerator.NegotiationReport report =
                new NegotiationReportGenerator.NegotiationReport();
        report.negotiationId = finalMsg.getNegotiationId();
        report.buyerName = assignment.getBuyerName();
        report.dealerName = assignment.getDealerName();
        report.carDescription = assignment.getListing().getMake() + " "
                + assignment.getListing().getModel() + " " + assignment.getListing().getYear();
        report.askingPrice = assignment.getListing().getRetailPrice();
        report.buyerFirstOffer = assignment.getRequirement().getMaxPrice() * 0.8; // approximate
        report.buyerReservationPrice = assignment.getRequirement().getMaxPrice();
        report.maxRounds = 10;
        report.startTime = new java.text.SimpleDateFormat("HH:mm:ss dd MMM yyyy")
                .format(new Date(finalMsg.getTimestamp() - 300000)); // approximate start
        String strategyInfo = ConversationLogger.getStrategyInfo(finalMsg.getNegotiationId());
        report.buyerStrategyName = valueFromStrategyInfo(strategyInfo, "BUYER_STRATEGY");
        report.dealerStrategyName = valueFromStrategyInfo(strategyInfo, "DEALER_STRATEGY");
        report.strategyName = formatStrategySummary(report.buyerStrategyName, report.dealerStrategyName);
        report.buyerFirstOffer = moneyFromStrategyInfo(strategyInfo, "BuyerFirstOffer", report.buyerFirstOffer);
        report.buyerReservationPrice = moneyFromStrategyInfo(strategyInfo, "BuyerBudget", report.buyerReservationPrice);
        report.maxRounds = intFromStrategyInfo(strategyInfo, "MaxRounds", report.maxRounds);
        report.autoNegotiated = true;

        // Add round entries from history
        List<NegotiationMessage> msgs = history.get(finalMsg.getNegotiationId());
        if (msgs != null) {
            int round = 1;
            for (NegotiationMessage msg : msgs) {
                if (msg.getType() == NegotiationMessage.Type.OFFER) {
                    double dealerPrice = msg.getFromName().equals(assignment.getDealerName())
                            ? msg.getPrice() : 0;
                    double buyerPrice = msg.getFromName().equals(assignment.getBuyerName())
                            ? msg.getPrice() : 0;
                    if (dealerPrice > 0 || buyerPrice > 0) {
                        report.addRound(round++, dealerPrice, buyerPrice,
                                msg.getMessage() != null ? msg.getMessage() : "");
                    }
                }
            }
        }

        report.finalise("DEAL", finalMsg.getPrice());
        NegotiationReportGenerator.save(report);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called by BrokerGui when operator clicks "Assign"
    // ─────────────────────────────────────────────────────────────────────────

    private static String valueFromStrategyInfo(String info, String key) {
        if (info == null || key == null) return null;
        for (String part : info.split("\\|")) {
            String trimmed = part.trim();
            String prefix = key + "=";
            if (trimmed.startsWith(prefix)) {
                String value = trimmed.substring(prefix.length()).trim();
                return isPlaceholderStrategy(value) ? null : value;
            }
        }
        return null;
    }

    private static double moneyFromStrategyInfo(String info, String key, double fallback) {
        String value = valueFromStrategyInfo(info, key);
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value.replace("RM", "").replace(",", "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intFromStrategyInfo(String info, String key, int fallback) {
        String value = valueFromStrategyInfo(info, key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatStrategySummary(String buyerStrategy, String dealerStrategy) {
        boolean hasBuyer = buyerStrategy != null && !buyerStrategy.isBlank();
        boolean hasDealer = dealerStrategy != null && !dealerStrategy.isBlank();
        if (hasBuyer && hasDealer) {
            return "Buyer: " + buyerStrategy + " | Dealer: " + dealerStrategy;
        }
        if (hasBuyer) return "Buyer: " + buyerStrategy;
        if (hasDealer) return "Dealer: " + dealerStrategy;
        return "Auto-negotiation";
    }

    private static boolean isPlaceholderStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) return true;
        String normalized = strategy.toLowerCase(Locale.ROOT);
        return normalized.contains("unknown")
                || normalized.contains("pending")
                || normalized.contains("see buyer log");
    }

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
        toDealer.addReceiver(new AID(listing.getDealerAID(), true));
        toDealer.setOntology(Ontology.TYPE_ASSIGNMENT_NOTIFY);
        toDealer.setConversationId(Ontology.CONV_ASSIGNMENT);
        toDealer.setContent(gson.toJson(a));
        send(toDealer);
        log.send(a.getDealerName(), Ontology.TYPE_ASSIGNMENT_NOTIFY,
                "Buyer: " + a.getBuyerName() + "  Listing: " + listing.getListingId());

        // Notify buyer
        ACLMessage toBuyer = new ACLMessage(ACLMessage.INFORM);
        toBuyer.addReceiver(new AID(requirement.getBuyerAID(), true));
        toBuyer.setOntology(Ontology.TYPE_ASSIGNMENT_NOTIFY);
        toBuyer.setConversationId(Ontology.CONV_ASSIGNMENT);
        toBuyer.setContent(gson.toJson(a));
        send(toBuyer);
        log.send(a.getBuyerName(), Ontology.TYPE_ASSIGNMENT_NOTIFY,
                "Dealer: " + a.getDealerName() + "  Listing: " + listing.getListingId());

        if (gui != null) SwingUtilities.invokeLater(() -> gui.refreshAssignments(getAssignments()));
    }

    private void autoAssignMatchesForListing(CarListing listing) {
        List<CarRequirement> matches = requirements.values().stream()
                .filter(req -> matches(listing, req))
                .filter(req -> !assignmentExists(listing.getListingId(), req.getRequirementId()))
                .toList();

        for (CarRequirement req : matches) {
            log.event("AUTO MATCH  Listing " + listing.getListingId()
                    + " -> Buyer " + req.getBuyerName()
                    + " [" + req.getRequirementId() + "]");
            createAssignment(listing, req, AUTO_ASSIGN_NOTE);
        }
    }

    private void autoAssignMatchesForRequirement(CarRequirement req) {
        List<CarListing> matches = listings.values().stream()
                .filter(listing -> matches(listing, req))
                .filter(listing -> !assignmentExists(listing.getListingId(), req.getRequirementId()))
                .toList();

        for (CarListing listing : matches) {
            log.event("AUTO MATCH  Buyer " + req.getBuyerName()
                    + " [" + req.getRequirementId() + "]"
                    + " -> Listing " + listing.getListingId());
            createAssignment(listing, req, AUTO_ASSIGN_NOTE);
        }
    }

    private boolean assignmentExists(String listingId, String requirementId) {
        return assignments.values().stream()
                .anyMatch(a -> a.getListing() != null
                        && a.getRequirement() != null
                        && listingId.equals(a.getListing().getListingId())
                        && requirementId.equals(a.getRequirement().getRequirementId()));
    }

    private boolean matches(CarListing listing, CarRequirement req) {
        return matchesText(req.getMake(), listing.getMake())
                && matchesText(req.getModel(), listing.getModel())
                && matchesYear(req, listing.getYear())
                && matchesPrice(req.getMaxPrice(), listing.getRetailPrice())
                && matchesText(req.getCondition(), listing.getCondition(), "Any")
                && matchesMileage(req.getMaxMileage(), listing.getMileage());
    }

    private boolean matchesText(String required, String actual) {
        return matchesText(required, actual, null);
    }

    private boolean matchesText(String required, String actual, String wildcard) {
        if (required == null || required.isBlank()) return true;
        if (wildcard != null && required.equalsIgnoreCase(wildcard)) return true;
        return actual != null && required.equalsIgnoreCase(actual);
    }

    private boolean matchesYear(CarRequirement req, int listingYear) {
        return (req.getYearMin() <= 0 || listingYear >= req.getYearMin())
                && (req.getYearMax() <= 0 || listingYear <= req.getYearMax());
    }

    private boolean matchesPrice(double maxPrice, double listingPrice) {
        return maxPrice <= 0 || listingPrice <= maxPrice;
    }

    private boolean matchesMileage(int maxMileage, int listingMileage) {
        return maxMileage <= 0 || listingMileage <= maxMileage;
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
        fwd.addReceiver(new AID(recipientAID, true));
        fwd.setOntology(ontology);
        fwd.setConversationId(Ontology.CONV_NEGOTIATION + "-" + msg.getNegotiationId());
        msg.setConversationId(fwd.getConversationId());
        fwd.setContent(gson.toJson(msg));
        send(fwd);

        log.send(msg.getToName(), ontology,
                "[" + msg.getNegotiationId() + "] ACL." + aclPerformativeName(performative)
                        + " from " + msg.getFromName()
                        + "  RM " + String.format("%.0f", msg.getPrice()));
    }

    public void closeDeal(NegotiationMessage acceptMsg) {
        NegotiationMessage acceptedOffer = findAcceptedOffer(acceptMsg);
        if (acceptedOffer != null && acceptedOffer.getPrice() > 0
                && Math.abs(acceptedOffer.getPrice() - acceptMsg.getPrice()) > 0.01) {
            log.info("Corrected accepted price for [" + acceptMsg.getNegotiationId() + "]"
                    + " from RM " + String.format("%.0f", acceptMsg.getPrice())
                    + " to last counterparty offer RM " + String.format("%.0f", acceptedOffer.getPrice()));
            acceptMsg.setPrice(acceptedOffer.getPrice());
        }

        Assignment a = assignments.get(acceptMsg.getNegotiationId());
        if (a != null) {
            ConversationLogger.logMessage(acceptMsg, a);
        }

        onDealComplete(acceptMsg);

        String dealerAID = a != null ? a.getDealerAID() : acceptMsg.getToAID();
        String buyerAID  = a != null ? a.getBuyerAID()  : acceptMsg.getFromAID();
        String dealerName = a != null ? a.getDealerName() : "dealer";
        String buyerName  = a != null ? a.getBuyerName()  : "buyer";

        // Log the completed conversation
        if (a != null) {
            ConversationLogger.markCompleted(acceptMsg.getNegotiationId(), acceptMsg, a);
            refreshNegotiationTestReport();
            log.info("Conversation saved: " + acceptMsg.getNegotiationId());
        }

        for (String[] party : new String[][]{{dealerAID, dealerName}, {buyerAID, buyerName}}) {
            ACLMessage notif = new ACLMessage(ACLMessage.INFORM);
            notif.addReceiver(new AID(party[0], true));
            notif.setOntology(Ontology.TYPE_DEAL_COMPLETE);
            notif.setConversationId(Ontology.CONV_NEGOTIATION + "-" + acceptMsg.getNegotiationId());
            notif.setContent(gson.toJson(acceptMsg));
            send(notif);
            log.send(party[1], Ontology.TYPE_DEAL_COMPLETE,
                    "[" + acceptMsg.getNegotiationId() + "] ACL.INFORM Final price: RM "
                            + String.format("%.0f", acceptMsg.getPrice()));
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

    private NegotiationMessage findAcceptedOffer(NegotiationMessage acceptMsg) {
        List<NegotiationMessage> messages = history.get(acceptMsg.getNegotiationId());
        if (messages == null || messages.isEmpty()) return null;

        for (int i = messages.size() - 1; i >= 0; i--) {
            NegotiationMessage msg = messages.get(i);
            if (msg.getType() != NegotiationMessage.Type.OFFER) continue;
            if (acceptMsg.getFromAID() != null && acceptMsg.getFromAID().equals(msg.getFromAID())) continue;
            if (acceptMsg.getFromRole() != null && acceptMsg.getFromRole().equalsIgnoreCase(msg.getFromRole())) continue;
            return msg;
        }
        return null;
    }

    private static String aclLabel(NegotiationMessage msg) {
        String performative = msg.getAclPerformative();
        return performative == null || performative.isBlank()
                ? "ACL.UNKNOWN"
                : "ACL." + performative;
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
            log.error("Failed to launch " + className, e);
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
