package negotiation.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import negotiation.models.Assignment;
import negotiation.models.NegotiationMessage;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes machine-readable negotiation audit records.
 *
 * Text conversation files remain useful for humans; this service appends the
 * same negotiation events to CSV and JSONL files for analysis and reports.
 */
public final class NegotiationAuditService {

    private static final Path CONVERSATIONS_DIR = Path.of("conversations");
    private static final Path HISTORY_CSV = CONVERSATIONS_DIR.resolve("negotiation-history.csv");
    private static final Path HISTORY_JSONL = CONVERSATIONS_DIR.resolve("negotiation-history.jsonl");
    private static final String CSV_HEADER =
            "timestamp,negotiationId,conversationId,round,sender,receiver,type,aclPerformative,"
                    + "price,strategy,outcome,utility,buyerUtility,dealerUtility,reason";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Object LOCK = new Object();

    private NegotiationAuditService() {
    }

    public static void recordMessage(NegotiationMessage message,
                                     Assignment assignment,
                                     int round,
                                     String strategy,
                                     String outcome,
                                     Double buyerUtility,
                                     Double dealerUtility,
                                     String perNegotiationBaseName) throws IOException {
        Files.createDirectories(CONVERSATIONS_DIR);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", timestamp(message));
        record.put("negotiationId", nvl(message.getNegotiationId()));
        record.put("conversationId", firstNonBlank(message.getConversationId(), message.getNegotiationId()));
        record.put("round", round > 0 ? round : null);
        record.put("sender", nvl(message.getFromName()));
        record.put("receiver", nvl(message.getToName()));
        record.put("type", message.getType() != null ? message.getType().name() : "");
        record.put("aclPerformative", nvl(message.getAclPerformative()));
        record.put("price", message.getPrice() > 0 ? roundMoney(message.getPrice()) : null);
        record.put("strategy", nvl(strategy));
        record.put("outcome", nvl(outcome));
        record.put("utility", roundUtility(message.getUtility()));
        record.put("buyerUtility", roundUtility(buyerUtility));
        record.put("dealerUtility", roundUtility(dealerUtility));
        record.put("reason", firstNonBlank(message.getReason(), message.getMessage()));

        synchronized (LOCK) {
            appendCsv(HISTORY_CSV, record);
            appendJsonl(HISTORY_JSONL, record);

            if (perNegotiationBaseName != null && !perNegotiationBaseName.isBlank()) {
                Path perCsv = CONVERSATIONS_DIR.resolve(perNegotiationBaseName + ".csv");
                Path perJson = CONVERSATIONS_DIR.resolve(perNegotiationBaseName + ".jsonl");
                appendCsv(perCsv, record);
                appendJsonl(perJson, record);
            }
        }
    }

    public static void recordDealComplete(String negotiationId,
                                          Assignment assignment,
                                          double finalPrice,
                                          int round,
                                          String perNegotiationBaseName) throws IOException {
        NegotiationMessage message = new NegotiationMessage();
        message.setNegotiationId(negotiationId);
        message.setConversationId(negotiationId);
        message.setFromName(assignment != null ? assignment.getBuyerName() : "");
        message.setToName(assignment != null ? assignment.getDealerName() : "");
        message.setType(NegotiationMessage.Type.ACCEPT);
        message.setPrice(finalPrice);
        message.setRound(round);
        message.setOutcome("COMPLETED");
        message.setReason("Deal completed");
        recordMessage(message, assignment, round, "", "COMPLETED", null, null, perNegotiationBaseName);
    }

    public static void recordFailure(String negotiationId,
                                     Assignment assignment,
                                     String reason,
                                     int round,
                                     String perNegotiationBaseName) throws IOException {
        NegotiationMessage message = new NegotiationMessage();
        message.setNegotiationId(negotiationId);
        message.setConversationId(negotiationId);
        message.setFromName(assignment != null ? assignment.getBuyerName() : "");
        message.setToName(assignment != null ? assignment.getDealerName() : "");
        message.setType(NegotiationMessage.Type.REJECT);
        message.setRound(round);
        message.setOutcome("FAILED");
        message.setReason(reason);
        recordMessage(message, assignment, round, "", "FAILED", null, null, perNegotiationBaseName);
    }

    private static void appendCsv(Path file, Map<String, Object> record) throws IOException {
        boolean writeHeader = !Files.exists(file) || Files.size(file) == 0;
        StringBuilder line = new StringBuilder();
        if (writeHeader) {
            line.append(CSV_HEADER).append(System.lineSeparator());
        }
        line.append(csv(record.get("timestamp"))).append(',')
                .append(csv(record.get("negotiationId"))).append(',')
                .append(csv(record.get("conversationId"))).append(',')
                .append(csv(record.get("round"))).append(',')
                .append(csv(record.get("sender"))).append(',')
                .append(csv(record.get("receiver"))).append(',')
                .append(csv(record.get("type"))).append(',')
                .append(csv(record.get("aclPerformative"))).append(',')
                .append(csv(record.get("price"))).append(',')
                .append(csv(record.get("strategy"))).append(',')
                .append(csv(record.get("outcome"))).append(',')
                .append(csv(record.get("utility"))).append(',')
                .append(csv(record.get("buyerUtility"))).append(',')
                .append(csv(record.get("dealerUtility"))).append(',')
                .append(csv(record.get("reason"))).append(System.lineSeparator());
        Files.writeString(file, line.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void appendJsonl(Path file, Map<String, Object> record) throws IOException {
        Files.writeString(file, GSON.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String timestamp(NegotiationMessage message) {
        long epochMillis = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIMESTAMP_FORMAT);
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static BigDecimal roundMoney(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundUtility(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
