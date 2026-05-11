package negotiation.testing;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline tester for completed negotiation conversation logs.
 *
 * It scans conversations/*.txt, calculates how long each negotiation took,
 * counts offers/messages, estimates the final price, and writes reports to logs/.
 *
 * Run after one or more negotiations have produced conversation files:
 *   mvn exec:java -Dexec.mainClass=negotiation.testing.NegotiationTestLogger
 *
 * Or after packaging:
 *   java -cp target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar negotiation.testing.NegotiationTestLogger
 */
public final class NegotiationTestLogger {

    private static final Path CONVERSATIONS_DIR = Path.of("conversations");
    private static final Path LOG_DIR = Path.of("logs");
    private static final Path REPORTS_DIR = Path.of("reports");
    private static final Path EXPERIMENT_RESULTS_DIR = Path.of("experiment_results");
    private static final Path TEXT_REPORT = LOG_DIR.resolve("negotiation-test-report.txt");
    private static final Path CSV_REPORT = LOG_DIR.resolve("negotiation-test-report.csv");
    private static final Path HTML_REPORT = EXPERIMENT_RESULTS_DIR.resolve("negotiation-test-report.html");

    private static final DateTimeFormatter START_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter FULL_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter REPORT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private static final Pattern LINE_TIME =
            Pattern.compile("^\\[(?:(\\d{4}-\\d{2}-\\d{2})\\s+)?(\\d{2}:\\d{2}:\\d{2})]");
    private static final Pattern PRICE = Pattern.compile("Price:\\s*RM\\s*([0-9,.]+)");
    private static final Pattern EXPERIMENT_OFFER =
            Pattern.compile("^Round\\s+\\d+\\s+\\|\\s+(BUYER|DEALER)\\s+\\|\\s+OFFER\\s+\\|\\s+RM\\s*([0-9,.]+)");

    private NegotiationTestLogger() {
    }

    public static void main(String[] args) throws IOException {
        Path conversationsDir = args.length > 0 ? Path.of(args[0]) : CONVERSATIONS_DIR;
        List<NegotiationMetrics> results = refreshReports(conversationsDir);
        printSummary(results);
    }

    public static List<NegotiationMetrics> refreshDefaultReports() throws IOException {
        return refreshReports(CONVERSATIONS_DIR);
    }

    public static List<NegotiationMetrics> refreshReports(Path conversationsDir) throws IOException {
        ensureDirectories(conversationsDir);
        List<NegotiationMetrics> results = analyse(conversationsDir);
        String sourceLabel = conversationsDir.toString().replace('\\', '/') + "/*.txt";
        if (results.isEmpty()) {
            Optional<Path> experimentSummary = findLatestExperimentSummary();
            if (experimentSummary.isPresent()) {
                results = analyseExperimentSummary(experimentSummary.get());
                sourceLabel = experimentSummary.get().toString().replace('\\', '/');
            }
        }
        writeReports(results, sourceLabel);
        return results;
    }

    public static List<NegotiationMetrics> refreshReportsFromExperimentSummary(Path summaryCsv) throws IOException {
        ensureDirectories(CONVERSATIONS_DIR);
        List<NegotiationMetrics> results = analyseExperimentSummary(summaryCsv);
        writeReports(results, summaryCsv.toString().replace('\\', '/'));
        return results;
    }

    public static List<NegotiationMetrics> analyse(Path conversationsDir) throws IOException {
        Files.createDirectories(conversationsDir);
        File[] files = conversationsDir.toFile().listFiles((ignored, name) -> name.endsWith(".txt"));
        if (files == null) {
            return List.of();
        }

        List<NegotiationMetrics> results = new ArrayList<>();
        for (File file : files) {
            results.add(parse(file.toPath()));
        }
        results.sort(Comparator
                .comparing((NegotiationMetrics m) -> m.startedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(m -> m.fileName));
        return results;
    }

    private static Optional<Path> findLatestExperimentSummary() throws IOException {
        Files.createDirectories(EXPERIMENT_RESULTS_DIR);
        try (var entries = Files.list(EXPERIMENT_RESULTS_DIR)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(dir -> dir.resolve("summary.csv"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(NegotiationTestLogger::lastModifiedMillis));
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static List<NegotiationMetrics> analyseExperimentSummary(Path summaryCsv) throws IOException {
        if (summaryCsv == null || !Files.isRegularFile(summaryCsv)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(summaryCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }

        List<String> headers = parseCsvLine(lines.get(0));
        List<NegotiationMetrics> results = new ArrayList<>();
        Path sessionDir = summaryCsv.getParent();
        String sessionName = sessionDir != null && sessionDir.getFileName() != null
                ? sessionDir.getFileName().toString()
                : "experiment_session";

        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            Map<String, String> row = toCsvRow(headers, parseCsvLine(lines.get(i)));
            NegotiationMetrics metrics = fromExperimentRow(row, sessionDir, sessionName);
            results.add(metrics);
        }
        results.sort(Comparator.comparingInt(m -> parseInt(m.negotiationId)));
        return results;
    }

    private static NegotiationMetrics fromExperimentRow(Map<String, String> row, Path sessionDir, String sessionName)
            throws IOException {
        NegotiationMetrics metrics = new NegotiationMetrics();
        String folder = value(row, "folder");
        String caseNumber = value(row, "case");
        metrics.fileName = sessionName + "/" + nullToDash(folder);
        metrics.negotiationId = "experiment-" + caseNumber;
        metrics.buyerName = value(row, "buyer_strategy");
        metrics.dealerName = value(row, "dealer_strategy");
        metrics.car = value(row, "scenario");
        metrics.outcome = "DEAL".equalsIgnoreCase(value(row, "outcome"))
                ? "COMPLETED"
                : nullToDash(value(row, "outcome"));
        metrics.rounds = parseInt(value(row, "rounds"));
        metrics.messageCount = parseInt(value(row, "messages"));
        metrics.askingPrice = parseMoney(value(row, "asking_price"));
        metrics.buyerMaxPrice = parseMoney(value(row, "buyer_reservation_price"));
        metrics.finalPrice = parseMoney(value(row, "final_price"));
        metrics.priceDifferenceFromAsk = parseMoney(value(row, "saving_from_ask"));
        metrics.priceDifferencePct = parseMoney(value(row, "saving_pct"));
        metrics.firstBuyerOffer = parseMoney(value(row, "buyer_first_offer"));
        metrics.firstDealerOffer = metrics.askingPrice;
        metrics.acceptCount = "COMPLETED".equals(metrics.outcome) ? 1 : 0;
        metrics.rejectCount = "COMPLETED".equals(metrics.outcome) ? 0 : 1;

        if (sessionDir != null && folder != null && !folder.isBlank()) {
            parseExperimentTranscript(metrics, sessionDir.resolve(folder).resolve("transcript.txt"));
        }
        if (metrics.offerCount <= 0) {
            metrics.offerCount = Math.max(0, metrics.messageCount - metrics.acceptCount - metrics.rejectCount);
        }
        if (metrics.lastBuyerOffer <= 0) {
            metrics.lastBuyerOffer = metrics.finalPrice;
        }
        if (metrics.lastDealerOffer <= 0) {
            metrics.lastDealerOffer = metrics.finalPrice;
        }
        if (metrics.lastOfferPrice <= 0) {
            metrics.lastOfferPrice = metrics.finalPrice;
        }
        return metrics;
    }

    private static void parseExperimentTranscript(NegotiationMetrics metrics, Path transcript) throws IOException {
        if (!Files.isRegularFile(transcript)) {
            return;
        }
        for (String line : Files.readAllLines(transcript, StandardCharsets.UTF_8)) {
            Matcher matcher = EXPERIMENT_OFFER.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            double price = parseMoney(matcher.group(2));
            if (price <= 0) {
                continue;
            }
            metrics.offerCount++;
            metrics.lastOfferPrice = price;
            if ("BUYER".equals(matcher.group(1))) {
                metrics.buyerOfferCount++;
                if (metrics.firstBuyerOffer <= 0) {
                    metrics.firstBuyerOffer = price;
                }
                metrics.lastBuyerOffer = price;
            } else {
                metrics.dealerOfferCount++;
                if (metrics.firstDealerOffer <= 0) {
                    metrics.firstDealerOffer = price;
                }
                metrics.lastDealerOffer = price;
            }
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static Map<String, String> toCsvRow(List<String> headers, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            row.put(headers.get(i), i < values.size() ? values.get(i) : "");
        }
        return row;
    }

    private static String value(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? "" : value.trim();
    }

    private static NegotiationMetrics parse(Path file) throws IOException {
        NegotiationMetrics metrics = new NegotiationMetrics();
        metrics.fileName = file.getFileName().toString();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        LocalDate startedDate = null;
        LocalDateTime lastMessageAt = null;

        for (String line : lines) {
            if (line.startsWith("Session ID:")) {
                metrics.negotiationId = valueAfterColon(line);
            } else if (line.startsWith("Started:")) {
                metrics.startedAt = parseStart(valueAfterColon(line));
                if (metrics.startedAt != null) {
                    startedDate = metrics.startedAt.toLocalDate();
                }
            } else if (line.trim().startsWith("Buyer:")) {
                metrics.buyerName = parseParticipantName(line);
            } else if (line.trim().startsWith("Dealer:")) {
                metrics.dealerName = parseParticipantName(line);
            } else if (line.trim().startsWith("Car:")) {
                metrics.car = valueAfterColon(line);
            } else if (line.trim().startsWith("Year:")) {
                metrics.year = parseInt(valueAfterColon(line));
            } else if (line.trim().startsWith("Asking:")) {
                metrics.askingPrice = parseMoney(valueAfterColon(line));
            } else if (line.trim().startsWith("Max Price:")) {
                metrics.buyerMaxPrice = parseMoney(valueAfterColon(line));
            } else if (line.startsWith("COMPLETED:") || line.startsWith("Completed:")) {
                metrics.completedAt = combineDateAndTime(startedDate, valueAfterColon(line), metrics.startedAt);
                metrics.outcome = "COMPLETED";
            } else if (line.startsWith("INTERRUPTED:") || line.startsWith("Failed:")) {
                metrics.interruptedCount++;
                if (!"COMPLETED".equals(metrics.outcome)) {
                    metrics.outcome = "INTERRUPTED";
                    metrics.completedAt = combineDateAndTime(startedDate, valueAfterColon(line), metrics.startedAt);
                }
            } else if (line.startsWith("NEGOTIATION FAILED")) {
                if (!"COMPLETED".equals(metrics.outcome)) {
                    metrics.outcome = "INTERRUPTED";
                }
            } else if (line.startsWith("[") && line.contains("): ")) {
                metrics.messageCount++;
                lastMessageAt = parseMessageTime(startedDate, line, metrics.startedAt).orElse(lastMessageAt);
                parseMessageLine(metrics, line);
            }
        }

        if (metrics.completedAt == null) {
            metrics.completedAt = lastMessageAt;
        }
        if (metrics.startedAt != null && metrics.completedAt != null) {
            metrics.duration = Duration.between(metrics.startedAt, metrics.completedAt);
        }
        if (metrics.finalPrice <= 0) {
            metrics.finalPrice = metrics.lastOfferPrice;
        }
        if (metrics.askingPrice > 0 && metrics.finalPrice > 0) {
            metrics.priceDifferenceFromAsk = metrics.askingPrice - metrics.finalPrice;
            metrics.priceDifferencePct = metrics.priceDifferenceFromAsk / metrics.askingPrice * 100.0;
        }
        metrics.rounds = Math.max(metrics.buyerOfferCount, metrics.dealerOfferCount);
        if (metrics.outcome == null || metrics.outcome.isBlank()) {
            metrics.outcome = "UNKNOWN";
        }

        return metrics;
    }

    private static void parseMessageLine(NegotiationMetrics metrics, String line) {
        boolean offer = line.contains(": OFFER");
        if (offer) {
            metrics.offerCount++;
        } else if (line.contains(": ACCEPT")) {
            metrics.acceptCount++;
            metrics.outcome = "COMPLETED";
        } else if (line.contains(": REJECT")) {
            metrics.rejectCount++;
            metrics.outcome = "REJECTED";
        }

        double price = parsePriceFromMessage(line);
        if (price > 0) {
            metrics.lastOfferPrice = price;
            if (offer && line.contains("(BUYER)")) {
                metrics.buyerOfferCount++;
                if (metrics.firstBuyerOffer <= 0) {
                    metrics.firstBuyerOffer = price;
                }
                metrics.lastBuyerOffer = price;
            } else if (offer && line.contains("(DEALER)")) {
                metrics.dealerOfferCount++;
                if (metrics.firstDealerOffer <= 0) {
                    metrics.firstDealerOffer = price;
                }
                metrics.lastDealerOffer = price;
            }
        }
    }

    private static void writeReports(List<NegotiationMetrics> results, String sourceLabel) throws IOException {
        ensureDirectories(CONVERSATIONS_DIR);
        String textReport = buildTextReport(results);
        String csvReport = buildCsvReport(results);
        Files.writeString(TEXT_REPORT, textReport, StandardCharsets.UTF_8);
        Files.writeString(CSV_REPORT, csvReport, StandardCharsets.UTF_8);
        Files.writeString(HTML_REPORT, buildHtmlReport(csvReport, sourceLabel), StandardCharsets.UTF_8);
    }

    private static void ensureDirectories(Path conversationsDir) throws IOException {
        Files.createDirectories(CONVERSATIONS_DIR);
        if (!CONVERSATIONS_DIR.equals(conversationsDir)) {
            Files.createDirectories(conversationsDir);
        }
        Files.createDirectories(LOG_DIR);
        Files.createDirectories(REPORTS_DIR);
        Files.createDirectories(EXPERIMENT_RESULTS_DIR);
    }

    private static String buildTextReport(List<NegotiationMetrics> results) {
        StringBuilder out = new StringBuilder();
        out.append("NEGOTIATION TEST REPORT\n");
        out.append("=======================\n");
        out.append("Negotiations tested: ").append(results.size()).append("\n\n");

        for (NegotiationMetrics m : results) {
            out.append("ID: ").append(nullToDash(m.negotiationId)).append("\n");
            out.append("File: ").append(m.fileName).append("\n");
            out.append("Pair: ").append(nullToDash(m.buyerName)).append(" vs ")
                    .append(nullToDash(m.dealerName)).append("\n");
            out.append("Car: ").append(nullToDash(m.car));
            if (m.year > 0) out.append(" ").append(m.year);
            out.append("\n");
            out.append("Outcome: ").append(m.outcome).append("\n");
            out.append("Started: ").append(m.startedAt != null ? START_FORMAT.format(m.startedAt) : "-").append("\n");
            out.append("Ended: ").append(m.completedAt != null ? START_FORMAT.format(m.completedAt) : "-").append("\n");
            out.append("Duration: ").append(formatDuration(m.duration)).append("\n");
            out.append("Messages: ").append(m.messageCount)
                    .append(" | Offers: ").append(m.offerCount)
                    .append(" | Buyer offers: ").append(m.buyerOfferCount)
                    .append(" | Dealer offers: ").append(m.dealerOfferCount)
                    .append(" | Rounds: ").append(m.rounds).append("\n");
            out.append("Asking price: ").append(formatMoney(m.askingPrice))
                    .append(" | Buyer max: ").append(formatMoney(m.buyerMaxPrice))
                    .append(" | Final/last price: ").append(formatMoney(m.finalPrice)).append("\n");
            out.append("Difference from asking: ").append(formatMoney(m.priceDifferenceFromAsk))
                    .append(" (").append(formatDecimal(m.priceDifferencePct)).append("%)\n");
            out.append("First buyer/dealer offers: ").append(formatMoney(m.firstBuyerOffer))
                    .append(" / ").append(formatMoney(m.firstDealerOffer)).append("\n");
            out.append("Last buyer/dealer offers: ").append(formatMoney(m.lastBuyerOffer))
                    .append(" / ").append(formatMoney(m.lastDealerOffer)).append("\n");
            if (m.interruptedCount > 0) {
                out.append("Interrupted markers after/within session: ").append(m.interruptedCount).append("\n");
            }
            out.append("\n");
        }

        appendAggregateSummary(out, results);
        return out.toString();
    }

    private static String buildCsvReport(List<NegotiationMetrics> results) {
        StringBuilder out = new StringBuilder();
        out.append("file,negotiation_id,buyer,dealer,car,year,outcome,start,end,duration_ms,")
                .append("duration,rounds,messages,offers,buyer_offers,dealer_offers,accepts,rejects,")
                .append("asking_price,buyer_max_price,final_or_last_price,difference_from_asking,")
                .append("difference_from_asking_pct,first_buyer_offer,first_dealer_offer,")
                .append("last_buyer_offer,last_dealer_offer,interrupted_markers\n");
        for (NegotiationMetrics m : results) {
            out.append(csv(m.fileName)).append(',')
                    .append(csv(m.negotiationId)).append(',')
                    .append(csv(m.buyerName)).append(',')
                    .append(csv(m.dealerName)).append(',')
                    .append(csv(m.car)).append(',')
                    .append(m.year).append(',')
                    .append(csv(m.outcome)).append(',')
                    .append(csv(m.startedAt != null ? START_FORMAT.format(m.startedAt) : "")).append(',')
                    .append(csv(m.completedAt != null ? START_FORMAT.format(m.completedAt) : "")).append(',')
                    .append(m.duration != null ? m.duration.toMillis() : 0).append(',')
                    .append(csv(formatDuration(m.duration))).append(',')
                    .append(m.rounds).append(',')
                    .append(m.messageCount).append(',')
                    .append(m.offerCount).append(',')
                    .append(m.buyerOfferCount).append(',')
                    .append(m.dealerOfferCount).append(',')
                    .append(m.acceptCount).append(',')
                    .append(m.rejectCount).append(',')
                    .append(formatRawNumber(m.askingPrice)).append(',')
                    .append(formatRawNumber(m.buyerMaxPrice)).append(',')
                    .append(formatRawNumber(m.finalPrice)).append(',')
                    .append(formatRawNumber(m.priceDifferenceFromAsk)).append(',')
                    .append(formatRawNumber(m.priceDifferencePct)).append(',')
                    .append(formatRawNumber(m.firstBuyerOffer)).append(',')
                    .append(formatRawNumber(m.firstDealerOffer)).append(',')
                    .append(formatRawNumber(m.lastBuyerOffer)).append(',')
                    .append(formatRawNumber(m.lastDealerOffer)).append(',')
                    .append(m.interruptedCount)
                    .append('\n');
        }
        return out.toString();
    }

    private static String buildHtmlReport(String csvReport, String sourceLabel) {
        String generatedAt = LocalDateTime.now().format(REPORT_TIMESTAMP);
        String embeddedCsv = Base64.getEncoder().encodeToString(csvReport.getBytes(StandardCharsets.UTF_8));
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Negotiation Test Report</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
:root {
  --bg: #101114;
  --panel: #1a1d22;
  --panel-2: #242831;
  --border: #303542;
  --border-soft: #222733;
  --text: #e7eaf0;
  --muted: #98a2b3;
  --blue: #60a5fa;
  --green: #4ade80;
  --orange: #fb923c;
  --red: #f87171;
  --cyan: #67e8f9;
  --violet: #a78bfa;
}
body {
  background: var(--bg);
  color: var(--text);
  font-family: "Segoe UI", Arial, sans-serif;
  font-size: 14px;
  line-height: 1.6;
  padding: 24px 32px 36px;
}
a { color: var(--blue); text-decoration: none; }
a:hover { color: #93c5fd; text-decoration: underline; }
h1 { color: #fff; font-size: 22px; margin-bottom: 4px; }
h2 {
  color: #93c5fd;
  font-size: 15px;
  margin: 28px 0 12px;
  border-bottom: 1px solid var(--border);
  padding-bottom: 6px;
}
button {
  background: #2b3340;
  border: 1px solid var(--border);
  border-radius: 6px;
  color: #f8fafc;
  cursor: pointer;
  font: inherit;
  padding: 6px 12px;
}
button:hover { background: #354052; }
.topbar {
  align-items: flex-start;
  display: flex;
  gap: 16px;
  justify-content: space-between;
  margin-bottom: 20px;
}
.subtitle { color: var(--muted); }
.mono { font-family: Consolas, "Courier New", monospace; font-size: 12px; }
.status-line { color: #cbd5e1; font-size: 12px; margin-top: 4px; }
.banner {
  background: #052e16;
  border: 1px solid #16a34a;
  border-radius: 8px;
  padding: 14px 20px;
  margin-bottom: 20px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.banner strong { color: #86efac; font-size: 16px; }
.banner span { color: #bbf7d0; }
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}
.card {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 16px 20px;
}
.label {
  color: var(--muted);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: .45px;
}
.value {
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  margin-top: 4px;
}
.hint { color: #94a3b8; font-size: 12px; margin-top: 4px; }
.split {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(300px, .95fr);
  gap: 16px;
}
.summary-list { display: grid; gap: 10px; }
.summary-row {
  background: #101114;
  border: 1px solid var(--border);
  border-radius: 8px;
  display: grid;
  grid-template-columns: 170px 1fr;
  gap: 10px;
  padding: 10px 14px;
}
.summary-row .k { color: var(--muted); font-size: 12px; }
.summary-row .v { color: #fff; font-weight: 600; }
.bars { display: grid; gap: 10px; }
.bar-row {
  display: grid;
  grid-template-columns: 130px 1fr 80px;
  gap: 10px;
  align-items: center;
}
.bar-label { color: #cbd5e1; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track {
  background: #0b0d10;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  height: 12px;
  overflow: hidden;
}
.bar {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #2563eb, #7c3aed);
}
.bar-value { color: var(--cyan); font-family: Consolas, monospace; font-size: 12px; text-align: right; }
.table-wrap {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow-x: auto;
  background: var(--panel);
}
table { width: 100%; min-width: 1080px; border-collapse: collapse; }
th {
  background: var(--panel-2);
  color: #93c5fd;
  font-size: 12px;
  padding: 10px 14px;
  text-align: left;
  white-space: nowrap;
}
td {
  padding: 9px 14px;
  border-bottom: 1px solid var(--border-soft);
  vertical-align: top;
}
tr:last-child td { border-bottom: none; }
tbody tr:hover { background: #171b24; }
.price { color: var(--green); font-weight: 700; white-space: nowrap; }
.saving { color: var(--cyan); font-weight: 700; white-space: nowrap; }
.over { color: var(--red); font-weight: 700; white-space: nowrap; }
.dealer { color: var(--orange); white-space: nowrap; }
.buyer { color: var(--blue); white-space: nowrap; }
.pill {
  display: inline-block;
  border-radius: 999px;
  border: 1px solid;
  padding: 2px 9px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
.done { background: #052e16; border-color: #16a34a; color: #86efac; }
.warn { background: #3b2a14; border-color: #d97706; color: #fcd34d; }
.bad { background: #3f1111; border-color: #dc2626; color: #fca5a5; }
.neutral { background: #1f2937; border-color: #64748b; color: #cbd5e1; }
.empty { color: var(--muted); padding: 18px; text-align: center; }
.footer { color: #64748b; font-size: 11px; margin-top: 24px; }
@media (max-width: 760px) {
  body { padding: 18px; }
  .topbar { display: block; }
  .topbar button { margin-top: 12px; }
  .split { grid-template-columns: 1fr; }
  .summary-row { grid-template-columns: 1fr; gap: 2px; }
  .bar-row { grid-template-columns: 96px 1fr 72px; }
}
</style>
</head>
<body>
<div class="topbar">
  <div>
    <h1>Negotiation Test Report</h1>
    <p class="subtitle">Current metrics from <span class="mono">logs/negotiation-test-report.csv</span>, generated from <span class="mono">__SOURCE_LABEL__</span>.</p>
    <p class="status-line" id="last-updated">Generated at __GENERATED_AT__</p>
  </div>
  <button id="reload" type="button">Reload data</button>
</div>

<div class="banner" id="banner"></div>
<div class="grid" id="metrics"></div>

<div class="split">
  <div class="card">
    <h2 style="margin-top:0">Session Highlights</h2>
    <div class="summary-list" id="highlights"></div>
  </div>
  <div class="card">
    <h2 style="margin-top:0" id="bars-title">Duration By Session</h2>
    <div class="bars" id="durations"></div>
  </div>
</div>

<h2>Round And Price Breakdown</h2>
<div class="table-wrap">
  <table>
    <thead>
      <tr>
        <th>Pair</th>
        <th>Car</th>
        <th>Outcome</th>
        <th>Duration</th>
        <th>Rounds</th>
        <th>Messages</th>
        <th>Asking</th>
        <th>Buyer Max</th>
        <th>Final Price</th>
        <th>Difference</th>
        <th>First Offers</th>
        <th>Last Offers</th>
        <th>Report</th>
      </tr>
    </thead>
    <tbody id="rows"></tbody>
  </table>
</div>

<h2>Source Files</h2>
<div class="grid">
  <div class="card"><div class="label">Text Source</div><div class="value" style="font-size:15px"><a href="../logs/negotiation-test-report.txt">negotiation-test-report.txt</a></div><div class="hint">Readable generated report.</div></div>
  <div class="card"><div class="label">CSV Source</div><div class="value" style="font-size:15px"><a href="../logs/negotiation-test-report.csv">negotiation-test-report.csv</a></div><div class="hint">Data source used by this page.</div></div>
  <div class="card"><div class="label">Detailed Reports</div><div class="value" style="font-size:15px"><a href="../reports/">reports/</a></div><div class="hint">Individual negotiation report pages.</div></div>
</div>

<p class="footer">The page tries to refresh the CSV every 5 seconds. If browser security blocks local file loading, it falls back to the CSV embedded when this HTML was generated.</p>

<script type="text/plain" id="embedded-csv-b64">
__EMBEDDED_CSV__
</script>
<script>
(() => {
  const CSV_URL = '../logs/negotiation-test-report.csv';
  const GENERATED_AT = '__GENERATED_AT__';
  const embeddedCsv = new TextDecoder().decode(Uint8Array.from(
    atob(document.getElementById('embedded-csv-b64').textContent.trim()),
    char => char.charCodeAt(0)
  ));
  const moneyFormat = new Intl.NumberFormat('en-MY', { maximumFractionDigits: 0 });

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function parseCsv(text) {
    const records = [];
    let row = [];
    let value = '';
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (quoted) {
        if (ch === '"') {
          if (text[i + 1] === '"') {
            value += '"';
            i++;
          } else {
            quoted = false;
          }
        } else {
          value += ch;
        }
      } else if (ch === '"') {
        quoted = true;
      } else if (ch === ',') {
        row.push(value);
        value = '';
      } else if (ch === '\\r') {
        continue;
      } else if (ch === '\\n') {
        row.push(value);
        records.push(row);
        row = [];
        value = '';
      } else {
        value += ch;
      }
    }
    if (value.length > 0 || row.length > 0) {
      row.push(value);
      records.push(row);
    }
    if (records.length === 0) return [];
    const headers = records.shift().map(header => header.trim());
    return records
      .filter(record => record.some(cell => cell.trim() !== ''))
      .map(record => {
        const item = {};
        headers.forEach((header, index) => item[header] = record[index] || '');
        return item;
      });
  }

  function num(row, key) {
    const value = Number.parseFloat(row[key] || '0');
    return Number.isFinite(value) ? value : 0;
  }

  function average(values, includeZero) {
    const filtered = values.filter(value => Number.isFinite(value) && (includeZero || value > 0));
    if (filtered.length === 0) return 0;
    return filtered.reduce((sum, value) => sum + value, 0) / filtered.length;
  }

  function money(value) {
    if (!Number.isFinite(value) || Math.abs(value) < 0.0001) return '-';
    return 'RM ' + moneyFormat.format(Math.round(value));
  }

  function percent(value) {
    if (!Number.isFinite(value)) return '0.00%';
    return value.toFixed(2) + '%';
  }

  function duration(ms) {
    if (!Number.isFinite(ms) || ms <= 0) return '-';
    const whole = Math.max(0, Math.round(ms));
    const totalSeconds = Math.floor(whole / 1000);
    const millis = String(whole % 1000).padStart(3, '0');
    const seconds = totalSeconds % 60;
    const minutes = Math.floor(totalSeconds / 60) % 60;
    const hours = Math.floor(totalSeconds / 3600);
    if (hours > 0) return `${hours}h ${String(minutes).padStart(2, '0')}m ${String(seconds).padStart(2, '0')}.${millis}s`;
    if (minutes > 0) return `${minutes}m ${String(seconds).padStart(2, '0')}.${millis}s`;
    return `${seconds}.${millis}s`;
  }

  function pair(row) {
    return `${row.buyer || '-'} vs ${row.dealer || '-'}`;
  }

  function car(row) {
    const year = num(row, 'year');
    return `${row.car || '-'}${year > 0 ? ' ' + year : ''}`;
  }

  function sanitize(value) {
    const cleaned = String(value || 'unknown').replace(/[^a-zA-Z0-9_-]/g, '_');
    return cleaned || 'unknown';
  }

  function reportHref(row) {
    if (row.file && row.file.startsWith('session_')) {
      const path = row.file.split('/').map(part => encodeURIComponent(part)).join('/');
      return '../experiment_results/' + path + '/transcript.txt';
    }
    if (!row.negotiation_id) return '';
    return '../reports/' + encodeURIComponent(row.negotiation_id) + '_' + sanitize(row.buyer) + '_vs_' + sanitize(row.dealer) + '.html';
  }

  function statusPill(row) {
    const status = String(row.outcome || 'UNKNOWN').toUpperCase();
    const className = status === 'COMPLETED' ? 'done' : status === 'REJECTED' ? 'bad' : status === 'INTERRUPTED' ? 'warn' : 'neutral';
    let html = `<span class="pill ${className}">${esc(status)}</span>`;
    const markers = num(row, 'interrupted_markers');
    if (markers > 0) {
      html += ` <span class="pill warn">${markers} marker${markers === 1 ? '' : 's'}</span>`;
    }
    return html;
  }

  function renderBanner(rows) {
    const total = rows.length;
    const completed = rows.filter(row => row.outcome === 'COMPLETED').length;
    const rejected = rows.filter(row => row.outcome === 'REJECTED').length;
    const interrupted = rows.filter(row => row.outcome === 'INTERRUPTED').length;
    const markers = rows.reduce((sum, row) => sum + num(row, 'interrupted_markers'), 0);
    const avgDuration = average(rows.map(row => num(row, 'duration_ms')), false);
    const banner = document.getElementById('banner');
    if (total === 0) {
      banner.innerHTML = '<strong>No negotiations found</strong><span>Waiting for conversation logs.</span>';
      return;
    }
    banner.innerHTML = `<strong>${completed} of ${total} negotiations completed</strong><span>Rejected: ${rejected} | Interrupted outcomes: ${interrupted} | Markers: ${markers} | Average duration: ${duration(avgDuration)}</span>`;
  }

  function renderCards(rows) {
    const total = rows.length;
    const completed = rows.filter(row => row.outcome === 'COMPLETED').length;
    const completionRate = total > 0 ? completed / total * 100 : 0;
    const totalRounds = rows.reduce((sum, row) => sum + num(row, 'rounds'), 0);
    const totalMessages = rows.reduce((sum, row) => sum + num(row, 'messages'), 0);
    const totalOffers = rows.reduce((sum, row) => sum + num(row, 'offers'), 0);
    const avgFinal = average(rows.map(row => num(row, 'final_or_last_price')), false);
    const totalDifference = rows.reduce((sum, row) => sum + num(row, 'difference_from_asking'), 0);
    const cards = [
      ['Negotiations Tested', String(total), 'Current CSV rows', ''],
      ['Completion Rate', percent(completionRate), `${completed} completed`, 'color: var(--green)'],
      ['Average Rounds', total > 0 ? (totalRounds / total).toFixed(2) : '0.00', `${totalRounds} rounds total`, 'color: var(--violet)'],
      ['Messages Logged', String(totalMessages), `${totalOffers} offer messages`, 'color: var(--blue)'],
      ['Average Final Price', money(avgFinal), 'Across priced sessions', 'color: var(--green)'],
      ['Total Difference', money(totalDifference), 'Compared with asking prices', 'color: var(--cyan)']
    ];
    document.getElementById('metrics').innerHTML = cards.map(card => `
      <div class="card">
        <div class="label">${esc(card[0])}</div>
        <div class="value" style="${esc(card[3])}">${esc(card[1])}</div>
        <div class="hint">${esc(card[2])}</div>
      </div>
    `).join('');
  }

  function renderHighlights(rows) {
    const target = document.getElementById('highlights');
    if (rows.length === 0) {
      target.innerHTML = '<div class="empty">No conversation files found in the source folder.</div>';
      return;
    }
    const timed = rows.filter(row => num(row, 'duration_ms') > 0);
    const fastest = timed.reduce((best, row) => !best || num(row, 'duration_ms') < num(best, 'duration_ms') ? row : best, null);
    const slowest = timed.reduce((best, row) => !best || num(row, 'duration_ms') > num(best, 'duration_ms') ? row : best, null);
    const largestRm = rows.reduce((best, row) => !best || num(row, 'difference_from_asking') > num(best, 'difference_from_asking') ? row : best, null);
    const largestPct = rows.reduce((best, row) => !best || num(row, 'difference_from_asking_pct') > num(best, 'difference_from_asking_pct') ? row : best, null);
    const maxRounds = Math.max(...rows.map(row => num(row, 'rounds')));
    const mostRounds = rows.filter(row => num(row, 'rounds') === maxRounds).slice(0, 4).map(pair).join(', ');
    const items = [
      ['Fastest negotiation', fastest ? `${pair(fastest)}, ${duration(num(fastest, 'duration_ms'))}` : '-'],
      ['Slowest negotiation', slowest ? `${pair(slowest)}, ${duration(num(slowest, 'duration_ms'))}` : '-'],
      ['Largest RM difference', largestRm ? `${pair(largestRm)}, ${money(num(largestRm, 'difference_from_asking'))}` : '-'],
      ['Largest percentage difference', largestPct ? `${pair(largestPct)}, ${percent(num(largestPct, 'difference_from_asking_pct'))}` : '-'],
      ['Most rounds', maxRounds > 0 ? `${mostRounds} (${maxRounds})` : '-']
    ];
    target.innerHTML = items.map(item => `<div class="summary-row"><div class="k">${esc(item[0])}</div><div class="v">${esc(item[1])}</div></div>`).join('');
  }

  function renderDurations(rows) {
    const target = document.getElementById('durations');
    const title = document.getElementById('bars-title');
    const timed = rows.filter(row => num(row, 'duration_ms') > 0).sort((a, b) => num(a, 'duration_ms') - num(b, 'duration_ms'));
    if (timed.length === 0) {
      const rounded = rows.filter(row => num(row, 'rounds') > 0).sort((a, b) => num(a, 'rounds') - num(b, 'rounds'));
      if (rounded.length === 0) {
        if (title) title.textContent = 'Duration By Session';
        target.innerHTML = '<div class="empty">No completed duration data yet.</div>';
        return;
      }
      if (title) title.textContent = 'Rounds By Case';
      const maxRounds = Math.max(...rounded.map(row => num(row, 'rounds')));
      target.innerHTML = rounded.slice(0, 18).map(row => {
        const width = Math.max(4, num(row, 'rounds') / maxRounds * 100);
        return `<div class="bar-row"><div class="bar-label">${esc(row.negotiation_id || row.file || '-')}</div><div class="bar-track"><div class="bar" style="width:${width.toFixed(1)}%"></div></div><div class="bar-value">${esc(row.rounds)} rds</div></div>`;
      }).join('');
      return;
    }
    if (title) title.textContent = 'Duration By Session';
    const maxDuration = Math.max(...timed.map(row => num(row, 'duration_ms')));
    target.innerHTML = timed.map(row => {
      const width = Math.max(4, num(row, 'duration_ms') / maxDuration * 100);
      return `<div class="bar-row"><div class="bar-label">${esc(row.buyer || row.file || '-')}</div><div class="bar-track"><div class="bar" style="width:${width.toFixed(1)}%"></div></div><div class="bar-value">${duration(num(row, 'duration_ms'))}</div></div>`;
    }).join('');
  }

  function renderRows(rows) {
    const target = document.getElementById('rows');
    if (rows.length === 0) {
      target.innerHTML = '<tr><td colspan="13" class="empty">No conversation files found. Run negotiations first, then run NegotiationTestLogger again.</td></tr>';
      return;
    }
    target.innerHTML = rows.map(row => {
      const difference = num(row, 'difference_from_asking');
      const pct = num(row, 'difference_from_asking_pct');
      const differenceClass = difference < 0 ? 'over' : 'saving';
      const report = reportHref(row);
      return `<tr>
        <td><span class="buyer">${esc(row.buyer || '-')}</span> vs <span class="dealer">${esc(row.dealer || '-')}</span></td>
        <td>${esc(car(row))}</td>
        <td>${statusPill(row)}</td>
        <td class="mono">${esc(row.duration || duration(num(row, 'duration_ms')))}</td>
        <td>${esc(row.rounds || '0')}</td>
        <td>${esc(row.messages || '0')}</td>
        <td>${esc(money(num(row, 'asking_price')))}</td>
        <td>${esc(money(num(row, 'buyer_max_price')))}</td>
        <td class="price">${esc(money(num(row, 'final_or_last_price')))}</td>
        <td class="${differenceClass}">${esc(money(difference))} (${esc(percent(pct))})</td>
        <td><span class="buyer">${esc(money(num(row, 'first_buyer_offer')))}</span> / <span class="dealer">${esc(money(num(row, 'first_dealer_offer')))}</span></td>
        <td><span class="buyer">${esc(money(num(row, 'last_buyer_offer')))}</span> / <span class="dealer">${esc(money(num(row, 'last_dealer_offer')))}</span></td>
        <td>${report ? `<a href="${esc(report)}">Open</a>` : '-'}</td>
      </tr>`;
    }).join('');
  }

  function render(rows, sourceLabel) {
    renderBanner(rows);
    renderCards(rows);
    renderHighlights(rows);
    renderDurations(rows);
    renderRows(rows);
    document.getElementById('last-updated').textContent = `${sourceLabel} | Generated at ${GENERATED_AT} | Display refreshed ${new Date().toLocaleTimeString()}`;
  }

  async function loadData() {
    try {
      const response = await fetch(CSV_URL + '?t=' + Date.now(), { cache: 'no-store' });
      if (!response.ok) throw new Error('CSV unavailable');
      const text = await response.text();
      render(parseCsv(text), 'Live CSV');
    } catch (error) {
      render(parseCsv(embeddedCsv), 'Embedded snapshot');
    }
  }

  document.getElementById('reload').addEventListener('click', loadData);
  loadData();
  window.setInterval(loadData, 5000);
})();
</script>
</body>
</html>
""".replace("__SOURCE_LABEL__", escapeHtml(sourceLabel))
                .replace("__GENERATED_AT__", escapeHtml(generatedAt))
                .replace("__EMBEDDED_CSV__", embeddedCsv);
    }

    private static void appendAggregateSummary(StringBuilder out, List<NegotiationMetrics> results) {
        List<NegotiationMetrics> timed = results.stream()
                .filter(m -> m.duration != null)
                .toList();
        long completed = results.stream().filter(m -> "COMPLETED".equals(m.outcome)).count();
        long rejected = results.stream().filter(m -> "REJECTED".equals(m.outcome)).count();
        long interrupted = results.stream().filter(m -> "INTERRUPTED".equals(m.outcome)).count();
        long failed = results.size() - completed;
        double averageRounds = results.stream().mapToInt(m -> m.rounds).average().orElse(0);
        double averageFinalPrice = results.stream()
                .filter(m -> m.finalPrice > 0)
                .mapToDouble(m -> m.finalPrice)
                .average()
                .orElse(0);

        out.append("SUMMARY\n");
        out.append("-------\n");
        out.append("Total negotiations: ").append(results.size()).append("\n");
        out.append("Successful negotiations: ").append(completed)
                .append(" | Failed negotiations: ").append(failed).append("\n");
        out.append("Completed: ").append(completed)
                .append(" | Rejected: ").append(rejected)
                .append(" | Interrupted: ").append(interrupted).append("\n");
        out.append("Average rounds: ").append(formatDecimal(averageRounds)).append("\n");
        out.append("Average final price: ").append(formatMoney(averageFinalPrice)).append("\n");
        if (!timed.isEmpty()) {
            double averageMillis = timed.stream()
                    .mapToLong(m -> m.duration.toMillis())
                    .average()
                    .orElse(0);
            NegotiationMetrics fastest = timed.stream()
                    .min(Comparator.comparingLong(m -> m.duration.toMillis()))
                    .orElse(null);
            NegotiationMetrics slowest = timed.stream()
                    .max(Comparator.comparingLong(m -> m.duration.toMillis()))
                    .orElse(null);

            out.append("Average duration: ")
                    .append(formatDuration(Duration.ofMillis(Math.round(averageMillis)))).append("\n");
            out.append("Fastest: ").append(fastest != null ? fastest.fileName : "-")
                    .append(" (").append(fastest != null ? formatDuration(fastest.duration) : "-").append(")\n");
            out.append("Slowest: ").append(slowest != null ? slowest.fileName : "-")
                    .append(" (").append(slowest != null ? formatDuration(slowest.duration) : "-").append(")\n");
        }
    }

    private static void printSummary(List<NegotiationMetrics> results) {
        System.out.println("Negotiation test complete.");
        System.out.println("Files analysed: " + results.size());
        System.out.println("Text report: " + TEXT_REPORT.toAbsolutePath());
        System.out.println("CSV report:  " + CSV_REPORT.toAbsolutePath());
        System.out.println("HTML report: " + HTML_REPORT.toAbsolutePath());
    }

    private static Optional<LocalDateTime> parseMessageTime(LocalDate startDate, String line, LocalDateTime startedAt) {
        Matcher matcher = LINE_TIME.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        LocalDate date = startDate;
        if (matcher.group(1) != null) {
            date = LocalDate.parse(matcher.group(1), DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (date == null) {
            return Optional.empty();
        }
        LocalDateTime value = LocalDateTime.of(date, LocalTime.parse(matcher.group(2), TIME_FORMAT));
        if (startedAt != null && value.isBefore(startedAt)) {
            value = value.plusDays(1);
        }
        return Optional.of(value);
    }

    private static LocalDateTime parseStart(String value) {
        try {
            return LocalDateTime.parse(value.trim(), FULL_TIMESTAMP_FORMAT);
        } catch (RuntimeException ignored) {
            // Try legacy filename-safe timestamp below.
        }
        try {
            return LocalDateTime.parse(value.trim(), START_FORMAT);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static LocalDateTime combineDateAndTime(LocalDate startDate, String time, LocalDateTime startedAt) {
        if (startDate == null || time == null || time.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(time.trim(), FULL_TIMESTAMP_FORMAT);
        } catch (RuntimeException ignored) {
            // Try legacy time-only value below.
        }
        try {
            LocalDateTime value = LocalDateTime.of(startDate, LocalTime.parse(time.trim(), TIME_FORMAT));
            if (startedAt != null && value.isBefore(startedAt)) {
                value = value.plusDays(1);
            }
            return value;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static double parsePriceFromMessage(String line) {
        Matcher matcher = PRICE.matcher(line);
        if (!matcher.find()) {
            return 0;
        }
        return parseMoney(matcher.group(1));
    }

    private static double parseMoney(String value) {
        if (value == null) {
            return 0;
        }
        String cleaned = value.replace("RM", "")
                .replace(",", "")
                .replaceAll("[^0-9.\\-]", "")
                .trim();
        if (cleaned.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String parseParticipantName(String line) {
        String value = valueAfterColon(line).trim();
        int aidIndex = value.indexOf(" (AID:");
        return aidIndex >= 0 ? value.substring(0, aidIndex).trim() : value;
    }

    private static String valueAfterColon(String line) {
        int index = line.indexOf(':');
        return index >= 0 ? line.substring(index + 1).trim() : "";
    }

    private static String formatDuration(Duration duration) {
        if (duration == null) {
            return "-";
        }
        long millis = Math.max(0, duration.toMillis());
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        long remainingMillis = millis % 1000;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02d.%03ds", hours, minutes, seconds, remainingMillis);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %02d.%03ds", minutes, seconds, remainingMillis);
        }
        return String.format(Locale.ROOT, "%d.%03ds", seconds, remainingMillis);
    }

    private static String formatMoney(double value) {
        if (Math.abs(value) < 0.0001) {
            return "-";
        }
        return "RM " + String.format(Locale.ROOT, "%,.0f", value);
    }

    private static String formatDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatRawNumber(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public static final class NegotiationMetrics {
        public String fileName;
        public String negotiationId;
        public String buyerName;
        public String dealerName;
        public String car;
        public int year;
        public String outcome;
        public LocalDateTime startedAt;
        public LocalDateTime completedAt;
        public Duration duration;
        public int rounds;
        public int messageCount;
        public int offerCount;
        public int buyerOfferCount;
        public int dealerOfferCount;
        public int acceptCount;
        public int rejectCount;
        public int interruptedCount;
        public double askingPrice;
        public double buyerMaxPrice;
        public double finalPrice;
        public double firstBuyerOffer;
        public double firstDealerOffer;
        public double lastBuyerOffer;
        public double lastDealerOffer;
        public double lastOfferPrice;
        public double priceDifferenceFromAsk;
        public double priceDifferencePct;
    }
}
