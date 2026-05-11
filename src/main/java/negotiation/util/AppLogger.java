package negotiation.util;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Timestamped application logger.
 *
 * Writes each line to the console, the attached Swing text area, shared system
 * logs, and a per-agent log file.
 */
public class AppLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final Path LOG_DIR = Path.of("logs");
    private static final Path AGENT_LOG_DIR = LOG_DIR.resolve("agents");
    private static final Path SYSTEM_LOG = LOG_DIR.resolve("system.log");
    private static final Path ERROR_LOG = LOG_DIR.resolve("error.log");
    private static final Object FILE_LOCK = new Object();
    private static volatile boolean DEBUG_MODE =
            Boolean.parseBoolean(System.getProperty("negotiation.debug", "true"));

    private final String agentRole;
    private final Path agentLogFile;
    private JTextArea logArea;

    public AppLogger(String agentRole) {
        this.agentRole = agentRole;
        this.agentLogFile = AGENT_LOG_DIR.resolve(sanitize(agentRole) + ".log");
    }

    public void setLogArea(JTextArea area) {
        this.logArea = area;
    }

    public static void setDebugMode(boolean enabled) {
        DEBUG_MODE = enabled;
    }

    public static boolean isDebugMode() {
        return DEBUG_MODE;
    }

    public void info(String message) {
        write("INFO", message, false);
    }

    public void debug(String message) {
        if (DEBUG_MODE) {
            write("DEBUG", message, false);
        }
    }

    public void warn(String message) {
        write("WARN", message, false);
    }

    public void send(String toAgent, String msgType, String detail) {
        write("MESSAGE", String.format(Locale.ROOT, "to %-12s [%-28s] %s",
                toAgent, msgType, nvl(detail)), false);
    }

    public void recv(String fromAgent, String msgType, String detail) {
        write("MESSAGE", String.format(Locale.ROOT, "from %-10s [%-28s] %s",
                fromAgent, msgType, nvl(detail)), false);
    }

    public void negotiation(String message) {
        write("NEGOTIATION", message, false);
    }

    public void event(String message) {
        write("EVENT", message, false);
    }

    public void error(String message) {
        write("ERROR", message, true);
    }

    public void error(String message, Exception e) {
        write("ERROR", message, true);
        if (e != null) {
            String stackTrace = stackTrace(e);
            System.err.print(stackTrace);
            appendToFiles(stackTrace, true);
        }
    }

    private void write(String level, String text, boolean stderr) {
        String line = "[" + TIMESTAMP_FORMAT.format(LocalDateTime.now()) + "] "
                + "[" + level + "] " + nvl(text);
        String consoleLine = "[" + agentRole + "] " + line;

        if (stderr) {
            System.err.println(consoleLine);
        } else {
            System.out.println(consoleLine);
        }

        appendToFiles(consoleLine + System.lineSeparator(), stderr);

        if (logArea != null) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(line + System.lineSeparator());
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }

    private void appendToFiles(String text, boolean error) {
        synchronized (FILE_LOCK) {
            try {
                Files.createDirectories(LOG_DIR);
                Files.createDirectories(AGENT_LOG_DIR);
                Files.writeString(SYSTEM_LOG, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.writeString(agentLogFile, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (error) {
                    Files.writeString(ERROR_LOG, text, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (Exception ignored) {
                // Avoid recursive logging failures.
            }
        }
    }

    private static String stackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "agent";
        }
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
