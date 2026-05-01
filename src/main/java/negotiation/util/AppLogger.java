package negotiation.util;

import javax.swing.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Simple timestamped logger that writes to both System.out and a Swing JTextArea.
 *
 * Usage in an agent:
 *   AppLogger log = new AppLogger("Broker");
 *   // ... after GUI is created:
 *   log.setLogArea(gui.getLogArea());
 *   // Then anywhere:
 *   log.info("Platform started");
 *   log.send("Dealer1", "LISTING_ACK", "listingId=A3F9B2C1");
 *   log.recv("Buyer1",  "BUYER_REQUIREMENTS", "Toyota Camry ≤ RM 80,000");
 */
public class AppLogger {

    private final String             agentRole;
    private       JTextArea          logArea;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

    public AppLogger(String agentRole) {
        this.agentRole = agentRole;
    }

    /** Attaches this logger to a GUI text area so messages appear in the UI. */
    public void setLogArea(JTextArea area) {
        this.logArea = area;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public logging methods
    // ─────────────────────────────────────────────────────────────────────────

    /** General informational message. */
    public void info(String message) {
        write("   " + message);
    }

    /** Marks a message SENT to another agent. */
    public void send(String toAgent, String msgType, String detail) {
        write(String.format("→  to %-12s  [%-28s]  %s", toAgent, msgType, nvl(detail)));
    }

    /** Marks a message RECEIVED from another agent. */
    public void recv(String fromAgent, String msgType, String detail) {
        write(String.format("←  from %-10s  [%-28s]  %s", fromAgent, msgType, nvl(detail)));
    }

    /** Highlights a significant event (deal, assignment, etc.). */
    public void event(String message) {
        write("★  " + message);
    }

    /** Marks an error or warning. */
    public void error(String message) {
        write("✗  " + message);
        System.err.println("[" + agentRole + "] ERROR: " + message);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private void write(String text) {
        String line = "[" + SDF.format(new Date()) + "]  " + text;
        System.out.println("[" + agentRole + "]  " + line);

        if (logArea != null) {
            // Always update Swing on the EDT
            SwingUtilities.invokeLater(() -> {
                logArea.append(line + "\n");
                // Auto-scroll to the latest line
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
