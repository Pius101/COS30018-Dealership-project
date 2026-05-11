package negotiation.util;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Configures per-process log files for headless launches.
 *
 * The Python spawner only decides where each Java process should write logs;
 * this Java class owns the actual stdout/stderr redirection.
 */
public final class HeadlessLogConfigurer {

    private HeadlessLogConfigurer() {
    }

    public static void configure(String logDir, String logName) throws IOException {
        if (logDir == null || logDir.isBlank()) {
            return;
        }

        Path dir = Paths.get(logDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);

        String safeName = sanitizeFileName(logName == null || logName.isBlank() ? "agent" : logName);
        Path logFile = dir.resolve(safeName + ".log");

        PrintStream stream = new PrintStream(
                new BufferedOutputStream(Files.newOutputStream(logFile, CREATE, TRUNCATE_EXISTING, WRITE)),
                true,
                StandardCharsets.UTF_8);

        System.setOut(stream);
        System.setErr(stream);
        Runtime.getRuntime().addShutdownHook(new Thread(stream::flush, "HeadlessLogFlush"));

        System.out.println("[Launcher] Logging to " + logFile);
    }

    private static String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
