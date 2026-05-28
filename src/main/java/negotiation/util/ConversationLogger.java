package negotiation.util;

import negotiation.models.Assignment;
import negotiation.models.CarListing;
import negotiation.models.NegotiationMessage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logger for human-readable negotiation transcripts.
 *
 * Machine-readable CSV/JSONL records are written through NegotiationAuditService
 * from the same broker-side logging call, so each routed negotiation message is
 * captured once in both formats.
 */
public class ConversationLogger {

    private static final String CONVERSATIONS_DIR = "conversations";
    private static final SimpleDateFormat FILE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    private static final SimpleDateFormat FULL_TIMESTAMP_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> activeConversations = new ConcurrentHashMap<>();
    private static final Map<String, Integer> fallbackRounds = new ConcurrentHashMap<>();
    private static final Map<String, String> strategyInfo = new ConcurrentHashMap<>();
    private static final Map<String, StrategyMetadata> strategyMetadata = new ConcurrentHashMap<>();

    static {
        try {
            conversationsDirectory();
        } catch (IOException e) {
            System.err.println("Failed to create conversations directory: " + e.getMessage());
        }
    }

    public static void logBuyerStrategyInfo(String negotiationId,
                                            String buyerStrategy,
                                            double buyerFirstOffer,
                                            double buyerReservation,
                                            int maxRounds) {
        StrategyMetadata metadata = strategyMetadata.compute(negotiationId, (id, existing) -> {
            StrategyMetadata updated = existing != null ? existing : new StrategyMetadata();
            if (!isPlaceholderStrategy(buyerStrategy)) {
                updated.buyerStrategy = buyerStrategy;
            }
            if (buyerFirstOffer > 0) {
                updated.buyerFirstOffer = buyerFirstOffer;
            }
            if (buyerReservation > 0) {
                updated.buyerReservation = buyerReservation;
            }
            if (maxRounds > 0) {
                updated.maxRounds = maxRounds;
            }
            return updated;
        });
        strategyInfo.put(negotiationId, formatStrategyInfo(metadata));
    }

    public static void logDealerStrategyInfo(String negotiationId,
                                             String dealerStrategy,
                                             double dealerFirstOffer,
                                             double dealerReservation,
                                             int maxRounds) {
        StrategyMetadata metadata = strategyMetadata.compute(negotiationId, (id, existing) -> {
            StrategyMetadata updated = existing != null ? existing : new StrategyMetadata();
            if (!isPlaceholderStrategy(dealerStrategy)) {
                updated.dealerStrategy = dealerStrategy;
            }
            if (dealerFirstOffer > 0) {
                updated.dealerFirstOffer = dealerFirstOffer;
            }
            if (dealerReservation > 0) {
                updated.dealerReservation = dealerReservation;
            }
            if (maxRounds > 0) {
                updated.maxRounds = maxRounds;
            }
            return updated;
        });
        strategyInfo.put(negotiationId, formatStrategyInfo(metadata));
    }

    /**
     * Backwards-compatible strategy logger used by older code paths.
     */
    public static void logStrategyInfo(String negotiationId,
                                       String buyerStrategy,
                                       String dealerStrategy,
                                       double buyerFirstOffer,
                                       double buyerReservation,
                                       int maxRounds) {
        if (!isPlaceholderStrategy(buyerStrategy)) {
            logBuyerStrategyInfo(negotiationId, buyerStrategy, buyerFirstOffer, buyerReservation, maxRounds);
        }
        if (!isPlaceholderStrategy(dealerStrategy)) {
            logDealerStrategyInfo(negotiationId, dealerStrategy, 0, 0, maxRounds);
        }
        StrategyMetadata metadata = strategyMetadata.computeIfAbsent(negotiationId, id -> new StrategyMetadata());
        if (buyerFirstOffer > 0 && metadata.buyerFirstOffer <= 0) {
            metadata.buyerFirstOffer = buyerFirstOffer;
        }
        if (buyerReservation > 0 && metadata.buyerReservation <= 0) {
            metadata.buyerReservation = buyerReservation;
        }
        if (maxRounds > 0 && metadata.maxRounds <= 0) {
            metadata.maxRounds = maxRounds;
        }
        strategyInfo.put(negotiationId, formatStrategyInfo(metadata));
    }

    public static String getStrategyInfo(String negotiationId) {
        return strategyInfo.get(negotiationId);
    }

    public static void logMessage(NegotiationMessage message, Assignment assignment) {
        String negotiationId = assignment.getNegotiationId();
        String filename = getConversationFilename(negotiationId, assignment);

        int round = resolveRound(message);
        String strategy = resolveStrategy(message);
        String outcome = resolveOutcome(message);
        Double buyerUtility = buyerUtility(negotiationId, message.getPrice());
        Double dealerUtility = dealerUtility(negotiationId, message.getPrice());

        message.setRound(round);
        if (isBlank(message.getStrategy()) && !isBlank(strategy)) {
            message.setStrategy(strategy);
        }
        if (isBlank(message.getOutcome())) {
            message.setOutcome(outcome);
        }
        if (message.getUtility() == null) {
            message.setUtility(senderUtility(message, buyerUtility, dealerUtility));
        }

        try {
            File file = new File(conversationsDirectory(), filename);
            boolean isNew = !file.exists();

            try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                if (isNew) {
                    writeHeader(writer, assignment);
                }

                writer.println(formatMessage(message, round, strategy, outcome));
                writer.flush();
            }

            NegotiationAuditService.recordMessage(
                    message,
                    assignment,
                    round,
                    strategy,
                    outcome,
                    buyerUtility,
                    dealerUtility,
                    baseName(filename));
        } catch (IOException e) {
            System.err.println("Failed to log conversation: " + e.getMessage());
        }
    }

    private static String getConversationFilename(String negotiationId, Assignment assignment) {
        String existingFilename = activeConversations.get(negotiationId);
        if (existingFilename != null) {
            return existingFilename;
        }

        String safeNegotiationId = sanitize(negotiationId);
        String buyerName = sanitize(assignment.getBuyerName());
        String dealerName = sanitize(assignment.getDealerName());
        String carInfo = formatCarInfo(assignment.getListing());

        String filename = String.format("%s_%s_vs_%s_%s.txt",
                safeNegotiationId, buyerName, dealerName, carInfo);

        activeConversations.put(negotiationId, filename);
        return filename;
    }

    private static void writeHeader(PrintWriter writer, Assignment assignment) {
        writer.println("=".repeat(80));
        writer.println("CAR NEGOTIATION CONVERSATION");
        writer.println("=".repeat(80));
        writer.println("Session ID: " + assignment.getNegotiationId());
        writer.println("Started: " + FULL_TIMESTAMP_FORMAT.format(new Date()));
        writer.println();
        writer.println("PARTICIPANTS:");
        writer.println("  Buyer:  " + assignment.getBuyerName()
                + " (AID: " + assignment.getBuyerAID() + ")");
        writer.println("  Dealer: " + assignment.getDealerName()
                + " (AID: " + assignment.getDealerAID() + ")");
        writer.println();

        String strategies = strategyInfo.get(assignment.getNegotiationId());
        if (strategies != null) {
            writer.println("NEGOTIATION STRATEGIES:");
            for (String part : strategies.split("\\|")) {
                writer.println("  " + part.trim());
            }
            writer.println();
        }

        writer.println("ITEM DETAILS:");
        writer.println("  Car:       " + assignment.getListing().getMake()
                + " " + assignment.getListing().getModel());
        writer.println("  Year:      " + assignment.getListing().getYear());
        writer.println("  Asking:    RM " + String.format(Locale.ROOT, "%.0f",
                assignment.getListing().getRetailPrice()));
        writer.println("  Condition: " + assignment.getListing().getCondition());
        writer.println();
        writer.println("BUYER REQUIREMENTS:");
        writer.println("  Max Price: RM " + String.format(Locale.ROOT, "%.0f",
                assignment.getRequirement().getMaxPrice()));
        if (assignment.getRequirement().getNotes() != null
                && !assignment.getRequirement().getNotes().isBlank()) {
            writer.println("  Notes:     " + assignment.getRequirement().getNotes());
        }
        writer.println();
        writer.println("CONVERSATION:");
        writer.println("-".repeat(80));
    }

    private static String formatMessage(NegotiationMessage message,
                                        int round,
                                        String strategy,
                                        String outcome) {
        String timestamp = FULL_TIMESTAMP_FORMAT.format(new Date(message.getTimestamp()));
        String direction = getDirection(message);
        String acl = !isBlank(message.getAclPerformative())
                ? " | ACL." + message.getAclPerformative()
                : "";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "[%s] Round %d | %s %s (%s): %s%s",
                timestamp,
                round,
                direction,
                nvl(message.getFromName()),
                nvl(message.getFromRole()),
                message.getType(),
                acl));

        if (message.getPrice() > 0) {
            sb.append(" | Price: RM ").append(String.format(Locale.ROOT, "%.0f", message.getPrice()));
        }
        if (!isBlank(strategy)) {
            sb.append(" | Strategy: ").append(strategy);
        }
        if (message.getUtility() != null) {
            sb.append(" | Utility: ").append(String.format(Locale.ROOT, "%.2f", message.getUtility()));
        }
        if (!isBlank(outcome)) {
            sb.append(" | Outcome: ").append(outcome);
        }
        String reason = firstNonBlank(message.getReason(), message.getMessage());
        if (!isBlank(reason)) {
            sb.append(System.lineSeparator())
                    .append("           Reason: \"").append(reason).append("\"");
        }
        return sb.toString();
    }

    public static void logReasoning(String negotiationId, String reasoning) {
        String filename = activeConversations.get(negotiationId);
        if (filename == null) return;
        try {
            File file = new File(conversationsDirectory(), filename);
            try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                writer.println("  REASON: " + reasoning);
                writer.flush();
            }
        } catch (IOException e) {
            System.err.println("Failed to log reasoning: " + e.getMessage());
        }
    }

    private static String getDirection(NegotiationMessage message) {
        String role = message.getFromRole();
        if (role == null) return "UNKNOWN";
        return switch (role.toUpperCase(Locale.ROOT)) {
            case "DEALER" -> "DEALER->BUYER";
            case "BUYER" -> "BUYER->DEALER";
            default -> role;
        };
    }

    private static String formatCarInfo(CarListing listing) {
        String make = sanitize(listing.getMake());
        String model = sanitize(listing.getModel());
        String year = String.valueOf(listing.getYear());
        return make + "_" + model + "_" + year;
    }

    public static void markCompleted(String negotiationId) {
        markCompleted(negotiationId, null, null);
    }

    public static void markCompleted(String negotiationId,
                                     NegotiationMessage finalMessage,
                                     Assignment assignment) {
        String filename = activeConversations.get(negotiationId);
        if (filename != null) {
            try {
                File file = new File(conversationsDirectory(), filename);
                try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                    writer.println();
                    writer.println("-".repeat(80));
                    writer.println("NEGOTIATION COMPLETED");
                    writer.println("Completed: " + FULL_TIMESTAMP_FORMAT.format(new Date()));
                    if (finalMessage != null && finalMessage.getPrice() > 0) {
                        writer.println("Final price: RM "
                                + String.format(Locale.ROOT, "%.0f", finalMessage.getPrice()));
                    }
                    if (assignment != null) {
                        writer.println("Buyer: " + assignment.getBuyerName());
                        writer.println("Dealer: " + assignment.getDealerName());
                    }
                    writer.println("Rounds: " + fallbackRounds.getOrDefault(negotiationId, 0));
                    writer.println("Outcome: Accepted");
                    writer.println("=".repeat(80));
                    writer.flush();
                }
            } catch (IOException e) {
                System.err.println("Failed to mark conversation as completed: " + e.getMessage());
            }
        }
    }

    public static void markInterrupted(String negotiationId, String agentName, String reason) {
        String filename = activeConversations.get(negotiationId);
        if (filename != null) {
            try {
                File file = new File(conversationsDirectory(), filename);
                try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                    writer.println();
                    writer.println("-".repeat(80));
                    writer.println("NEGOTIATION FAILED");
                    writer.println("Failed: " + FULL_TIMESTAMP_FORMAT.format(new Date()));
                    writer.println("Reason: " + agentName + " " + reason);
                    writer.println("Rounds: " + fallbackRounds.getOrDefault(negotiationId, 0));
                    writer.println("Outcome: Interrupted");
                    writer.println("=".repeat(80));
                    writer.flush();
                }
                NegotiationAuditService.recordFailure(
                        negotiationId,
                        null,
                        agentName + " " + reason,
                        fallbackRounds.getOrDefault(negotiationId, 0),
                        baseName(filename));
            } catch (IOException e) {
                System.err.println("Failed to mark conversation as interrupted: " + e.getMessage());
            }
        }
    }

    public static String generateNegotiationId(String buyerAID, String dealerAID, String listingId) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        return String.format("neg-%s-%s-%s-%s",
                sanitize(buyerAID),
                sanitize(dealerAID),
                sanitize(listingId),
                timestamp.substring(timestamp.length() - 6));
    }

    public static File[] getConversationFiles() {
        try {
            File[] files = conversationsDirectory().listFiles((dir1, name) -> name.endsWith(".txt"));
            return files != null ? files : new File[0];
        } catch (IOException e) {
            System.err.println("Failed to list conversation files: " + e.getMessage());
            return new File[0];
        }
    }

    private static int resolveRound(NegotiationMessage message) {
        String negotiationId = message.getNegotiationId();
        Integer explicitRound = message.getRound();
        if (explicitRound != null && explicitRound > 0) {
            fallbackRounds.merge(negotiationId, explicitRound, Math::max);
            return explicitRound;
        }

        int current = fallbackRounds.getOrDefault(negotiationId, 0);
        if (message.getType() == NegotiationMessage.Type.OFFER) {
            current = fallbackRounds.merge(negotiationId, 1, Integer::sum);
        } else if (current <= 0) {
            current = 1;
            fallbackRounds.put(negotiationId, current);
        }
        return current;
    }

    private static String resolveStrategy(NegotiationMessage message) {
        if (!isBlank(message.getStrategy())) {
            return message.getStrategy();
        }
        StrategyMetadata metadata = strategyMetadata.get(message.getNegotiationId());
        if (metadata == null) {
            return "";
        }
        String role = message.getFromRole();
        if ("BUYER".equalsIgnoreCase(role)) {
            return nvl(metadata.buyerStrategy);
        }
        if ("DEALER".equalsIgnoreCase(role)) {
            return nvl(metadata.dealerStrategy);
        }
        return "";
    }

    private static String resolveOutcome(NegotiationMessage message) {
        if (!isBlank(message.getOutcome())) {
            return message.getOutcome();
        }
        if (message.getType() == null) {
            return "";
        }
        return switch (message.getType()) {
            case OFFER -> "ONGOING";
            case ACCEPT -> "COMPLETED";
            case REJECT -> "FAILED";
        };
    }

    private static Double buyerUtility(String negotiationId, double price) {
        StrategyMetadata metadata = strategyMetadata.get(negotiationId);
        if (metadata == null || price <= 0
                || metadata.buyerFirstOffer <= 0
                || metadata.buyerReservation <= 0
                || Math.abs(metadata.buyerReservation - metadata.buyerFirstOffer) < 0.0001) {
            return null;
        }
        if (price <= metadata.buyerFirstOffer) return 1.0;
        if (price >= metadata.buyerReservation) return 0.0;
        return clamp01((metadata.buyerReservation - price)
                / (metadata.buyerReservation - metadata.buyerFirstOffer));
    }

    private static Double dealerUtility(String negotiationId, double price) {
        StrategyMetadata metadata = strategyMetadata.get(negotiationId);
        if (metadata == null || price <= 0
                || metadata.dealerFirstOffer <= 0
                || metadata.dealerReservation <= 0
                || Math.abs(metadata.dealerFirstOffer - metadata.dealerReservation) < 0.0001) {
            return null;
        }
        if (price >= metadata.dealerFirstOffer) return 1.0;
        if (price <= metadata.dealerReservation) return 0.0;
        return clamp01((price - metadata.dealerReservation)
                / (metadata.dealerFirstOffer - metadata.dealerReservation));
    }

    private static Double senderUtility(NegotiationMessage message,
                                        Double buyerUtility,
                                        Double dealerUtility) {
        String role = message.getFromRole();
        if ("BUYER".equalsIgnoreCase(role)) {
            return buyerUtility;
        }
        if ("DEALER".equalsIgnoreCase(role)) {
            return dealerUtility;
        }
        return null;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String formatStrategyInfo(StrategyMetadata metadata) {
        return String.format(Locale.ROOT,
                "BUYER_STRATEGY=%s | DEALER_STRATEGY=%s | "
                        + "BuyerFirstOffer=RM%.0f | BuyerBudget=RM%.0f | "
                        + "DealerFirstOffer=RM%.0f | DealerMinimum=RM%.0f | MaxRounds=%d",
                defaultStrategy(metadata.buyerStrategy),
                defaultStrategy(metadata.dealerStrategy),
                metadata.buyerFirstOffer,
                metadata.buyerReservation,
                metadata.dealerFirstOffer,
                metadata.dealerReservation,
                metadata.maxRounds);
    }

    private static String defaultStrategy(String strategy) {
        return isBlank(strategy) ? "Unknown" : strategy;
    }

    private static boolean isPlaceholderStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) return true;
        String normalized = strategy.toLowerCase(Locale.ROOT);
        return normalized.contains("unknown")
                || normalized.contains("pending")
                || normalized.contains("see buyer log");
    }

    private static File conversationsDirectory() throws IOException {
        File dir = new File(CONVERSATIONS_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IOException(dir.getAbsolutePath() + " is not a directory");
        }
        return dir;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return second;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class StrategyMetadata {
        String buyerStrategy;
        String dealerStrategy;
        double buyerFirstOffer;
        double buyerReservation;
        double dealerFirstOffer;
        double dealerReservation;
        int maxRounds;
    }
}
