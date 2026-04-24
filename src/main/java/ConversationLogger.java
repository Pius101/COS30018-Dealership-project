package negotiation.util;

import negotiation.models.NegotiationMessage;
import negotiation.models.Assignment;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logger for saving negotiation conversations to files.
 * Creates organized file structure for conversation history.
 */
public class ConversationLogger {
    
    private static final String CONVERSATIONS_DIR = "conversations";
    private static final SimpleDateFormat FILE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    private static final SimpleDateFormat MESSAGE_DATE_FORMAT = new SimpleDateFormat("HH:mm:ss");
    
    // Track active conversations to avoid duplicate files
    private static final Map<String, String> activeConversations = new ConcurrentHashMap<>();
    
    static {
        // Create conversations directory if it doesn't exist
        File dir = new File(CONVERSATIONS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
    
    /**
     * Log a negotiation message to the appropriate conversation file.
     */
    public static void logMessage(NegotiationMessage message, Assignment assignment) {
        String conversationId = assignment.getNegotiationId();
        String filename = getConversationFilename(conversationId, assignment);
        
        try {
            File file = new File(CONVERSATIONS_DIR, filename);
            boolean isNew = !file.exists();
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                if (isNew) {
                    writeHeader(writer, assignment);
                }
                
                String logLine = formatMessage(message);
                writer.println(logLine);
                writer.flush();
            }
        } catch (IOException e) {
            System.err.println("Failed to log conversation: " + e.getMessage());
        }
    }
    
    /**
     * Generate a unique filename for the conversation.
     */
    private static String getConversationFilename(String negotiationId, Assignment assignment) {
        // Check if we already have a filename for this conversation
        String existingFilename = activeConversations.get(negotiationId);
        if (existingFilename != null) {
            return existingFilename;
        }
        
        // Create new filename
        String timestamp = FILE_DATE_FORMAT.format(new Date());
        String buyerName = assignment.getBuyerName().replaceAll("[^a-zA-Z0-9_-]", "_");
        String dealerName = assignment.getDealerName().replaceAll("[^a-zA-Z0-9_-]", "_");
        String carInfo = formatCarInfo(assignment.getListing());
        
        String filename = String.format("%s_%s_vs_%s_%s.txt", 
            timestamp, buyerName, dealerName, carInfo);
        
        // Store for future use
        activeConversations.put(negotiationId, filename);
        return filename;
    }
    
    /**
     * Write conversation header with session details.
     */
    private static void writeHeader(PrintWriter writer, Assignment assignment) {
        writer.println("=".repeat(80));
        writer.println("CAR NEGOTIATION CONVERSATION");
        writer.println("=".repeat(80));
        writer.println("Session ID: " + assignment.getNegotiationId());
        writer.println("Started: " + FILE_DATE_FORMAT.format(new Date()));
        writer.println();
        writer.println("PARTICIPANTS:");
        writer.println("  Buyer: " + assignment.getBuyerName() + " (AID: " + assignment.getBuyerAID() + ")");
        writer.println("  Dealer: " + assignment.getDealerName() + " (AID: " + assignment.getDealerAID() + ")");
        writer.println();
        writer.println("ITEM DETAILS:");
        writer.println("  Car: " + assignment.getListing().getMake() + " " + assignment.getListing().getModel());
        writer.println("  Year: " + assignment.getListing().getYear());
        writer.println("  Price: RM " + String.format("%.0f", assignment.getListing().getRetailPrice()));
        writer.println("  Condition: " + assignment.getListing().getCondition());
        writer.println();
        writer.println("Buyer Requirements:");
        writer.println("  Max Price: RM " + String.format("%.0f", assignment.getRequirement().getMaxPrice()));
        writer.println("  Notes: " + assignment.getRequirement().getNotes());
        writer.println();
        writer.println("CONVERSATION:");
        writer.println("-".repeat(80));
    }
    
    /**
     * Format a negotiation message for logging.
     */
    private static String formatMessage(NegotiationMessage message) {
        String timestamp = MESSAGE_DATE_FORMAT.format(new Date(message.getTimestamp()));
        String direction = getDirection(message);
        
        return String.format("[%s] %s %s (%s): %s | Price: RM %s", 
            timestamp,
            direction,
            message.getFromName(),
            message.getFromRole(),
            message.getType(),
            String.format("%.0f", message.getPrice()));
    }
    
    /**
     * Determine message direction (incoming/outgoing from perspective).
     */
    private static String getDirection(NegotiationMessage message) {
        // This could be enhanced to show perspective based on who's logging
        return "SEND";
    }
    
    /**
     * Format car information for filename.
     */
    private static String formatCarInfo(negotiation.models.CarListing listing) {
        String make = listing.getMake().replaceAll("[^a-zA-Z0-9_-]", "_");
        String model = listing.getModel().replaceAll("[^a-zA-Z0-9_-]", "_");
        String year = String.valueOf(listing.getYear());
        return make + "_" + model + "_" + year;
    }
    
    /**
     * Mark conversation as completed.
     */
    public static void markCompleted(String negotiationId) {
        String filename = activeConversations.get(negotiationId);
        if (filename != null) {
            try {
                File file = new File(CONVERSATIONS_DIR, filename);
                try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                    writer.println();
                    writer.println("-".repeat(80));
                    writer.println("COMPLETED: " + MESSAGE_DATE_FORMAT.format(new Date()));
                    writer.println("=".repeat(80));
                    writer.flush();
                }
            } catch (IOException e) {
                System.err.println("Failed to mark conversation as completed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Emergency save when agent disconnects unexpectedly.
     */
    public static void markInterrupted(String negotiationId, String agentName, String reason) {
        String filename = activeConversations.get(negotiationId);
        if (filename != null) {
            try {
                File file = new File(CONVERSATIONS_DIR, filename);
                try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                    writer.println();
                    writer.println("-".repeat(80));
                    writer.println("INTERRUPTED: " + MESSAGE_DATE_FORMAT.format(new Date()));
                    writer.println("REASON: " + agentName + " " + reason);
                    writer.println("STATUS: Conversation saved but incomplete");
                    writer.println("=".repeat(80));
                    writer.flush();
                }
            } catch (IOException e) {
                System.err.println("Failed to mark conversation as interrupted: " + e.getMessage());
            }
        }
    }
    
    /**
     * Generate unique negotiation ID to handle re-negotiations.
     */
    public static String generateNegotiationId(String buyerAID, String dealerAID, String listingId) {
        // Include timestamp to make it unique even for same parties/car
        String timestamp = String.valueOf(System.currentTimeMillis());
        return String.format("neg-%s-%s-%s-%s", 
            buyerAID.replaceAll("[^a-zA-Z0-9_-]", "_"),
            dealerAID.replaceAll("[^a-zA-Z0-9_-]", "_"), 
            listingId,
            timestamp.substring(timestamp.length() - 6)); // Last 6 digits of timestamp
    }
    
    /**
     * Get list of all conversation files.
     */
    public static File[] getConversationFiles() {
        File dir = new File(CONVERSATIONS_DIR);
        return dir.listFiles((dir1, name) -> name.endsWith(".txt"));
    }
}
