package negotiation.report;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates an HTML visualisation report after each negotiation completes.
 *
 * Output: reports/NEG_<id>_<buyer>_vs_<dealer>.html
 *
 * Report contains:
 *   - Header: car, parties, result, final price, rounds taken
 *   - Line chart (Chart.js): buyer price vs dealer price over rounds
 *   - Rounds table: each round's offers + strategy reasoning
 *   - Full thinking log at the bottom
 *
 * Usage:
 *   NegotiationReport report = new NegotiationReport();
 *   report.setHeader(...);
 *   // For each round:
 *   report.addRound(round, dealerOffer, buyerOffer, buyerReasoning);
 *   // At the end:
 *   report.finalise(outcome, finalPrice);
 *   String path = NegotiationReportGenerator.save(report);
 */
public class NegotiationReportGenerator {

    public static final String REPORTS_DIR = "reports";

    static {
        try {
            Path reportsPath = Path.of(System.getProperty("user.dir"), REPORTS_DIR);
            Files.createDirectories(reportsPath);
            System.out.println("[Report] Reports directory: " + reportsPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Report] Failed to create reports directory: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data model for one negotiation session
    // ─────────────────────────────────────────────────────────────────────────

    public static class NegotiationReport {

        // Header
        public String negotiationId;
        public String buyerName;
        public String dealerName;
        public String carDescription;
        public double askingPrice;
        public double buyerFirstOffer;
        public double buyerReservationPrice;
        public int    maxRounds;
        public String strategyName;
        public String startTime;

        // Per-round data
        public final List<RoundEntry> rounds = new ArrayList<>();

        // Final result
        public String  outcome;    // "DEAL", "NO_DEAL_BUYER_REJECTED", "NO_DEAL_DEALER_REJECTED", "NO_DEAL_DEADLINE"
        public double  finalPrice;
        public String  endTime;
        public boolean autoNegotiated; // true if buyer used auto strategy

        public void addRound(int roundNum, double dealerOffer, double buyerOffer, String reasoning) {
            rounds.add(new RoundEntry(roundNum, dealerOffer, buyerOffer, reasoning));
        }

        public void finalise(String outcome, double finalPrice) {
            this.outcome    = outcome;
            this.finalPrice = finalPrice;
            this.endTime    = new SimpleDateFormat("HH:mm:ss dd MMM yyyy").format(new Date());
        }
    }

    public static class RoundEntry {
        public final int    roundNum;
        public final double dealerOffer;
        public final double buyerOffer;
        public final String reasoning;  // from NegotiationStrategy.getLastReasoning()
        public final long   timestamp;

        RoundEntry(int r, double d, double b, String reasoning) {
            this.roundNum   = r;
            this.dealerOffer = d;
            this.buyerOffer  = b;
            this.reasoning   = reasoning;
            this.timestamp   = System.currentTimeMillis();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate and save the HTML report.
     *
     * @return the file path where the report was saved
     */
    public static String save(NegotiationReport r) {
        String filename = String.format("%s_%s_vs_%s.html",
                r.negotiationId != null ? r.negotiationId : "NEG",
                sanitize(r.buyerName),
                sanitize(r.dealerName));
        Path path = Path.of(System.getProperty("user.dir"), REPORTS_DIR, filename);
        try {
            String html = buildHtml(r);
            Files.writeString(path, html);
            System.out.println("[Report] Saved: " + path.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Report] Failed to save: " + e.getMessage());
        }
        return path.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML generation
    // ─────────────────────────────────────────────────────────────────────────

    private static String buildHtml(NegotiationReport r) {
        boolean isDeal = "DEAL".equals(r.outcome);

        // Build chart data
        List<String> labels     = new ArrayList<>();
        List<Double>  dealerData = new ArrayList<>();
        List<Double>  buyerData  = new ArrayList<>();

        labels.add("\"Start\"");
        dealerData.add(r.askingPrice);
        buyerData.add(r.buyerFirstOffer > 0 ? r.buyerFirstOffer : r.buyerReservationPrice * 0.8);

        for (RoundEntry entry : r.rounds) {
            labels.add("\"R" + entry.roundNum + "\"");
            dealerData.add(entry.dealerOffer);
            buyerData.add(entry.buyerOffer);
        }
        if (isDeal && r.finalPrice > 0) {
            labels.add("\"Deal\"");
            dealerData.add(r.finalPrice);
            buyerData.add(r.finalPrice);
        }

        String labelsJson  = String.join(", ", labels);
        String dealerJson  = dealerData.stream().map(d -> String.format("%.0f", d))
                .collect(java.util.stream.Collectors.joining(", "));
        String buyerJson   = buyerData.stream().map(d -> String.format("%.0f", d))
                .collect(java.util.stream.Collectors.joining(", "));

        double saving    = r.askingPrice - r.finalPrice;
        double savingPct = r.askingPrice > 0 ? saving / r.askingPrice * 100.0 : 0;

        StringBuilder rows = new StringBuilder();
        for (RoundEntry entry : r.rounds) {
            rows.append("<tr><td>").append(entry.roundNum).append("</td>")
                    .append("<td style=\'color:#f97316\'>RM ").append(String.format("%,.0f", entry.dealerOffer)).append("</td>")
                    .append("<td style=\'color:#60a5fa\'>RM ").append(String.format("%,.0f", entry.buyerOffer)).append("</td>")
                    .append("<td>RM ").append(String.format("%,.0f", Math.abs(entry.dealerOffer - entry.buyerOffer))).append("</td>")
                    .append("<td style=\'font-family:monospace;font-size:11px;color:#94a3b8\'>")
                    .append(escapeHtml(entry.reasoning)).append("</td></tr>\n");
        }

        StringBuilder log = new StringBuilder();
        for (RoundEntry entry : r.rounds) {
            log.append("[Round ").append(entry.roundNum).append("]  ").append(entry.reasoning).append("\n");
        }
        if (isDeal) log.append("[DEAL]  Final price: RM ").append(String.format("%,.0f", r.finalPrice)).append("\n");
        else        log.append("[END]   Outcome: ").append(r.outcome).append("\n");

        // Use StringBuilder to avoid % interpretation issues in formatted()
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\'en\'>\n<head>\n");
        html.append("<meta charset=\'UTF-8\'>\n");
        html.append("<title>Negotiation Report — ").append(escapeHtml(r.carDescription)).append("</title>\n");
        html.append("<script src=\'https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js\'></script>\n");
        html.append("<style>\n");
        html.append("* { box-sizing: border-box; margin: 0; padding: 0; }\n");
        html.append("body { background: #0f1117; color: #e0e0e0; font-family: \'Segoe UI\', sans-serif;");
        html.append(" font-size: 14px; line-height: 1.6; padding: 24px 32px; }\n");
        html.append("h1 { color: #fff; font-size: 22px; margin-bottom: 4px; }\n");
        html.append("h2 { color: #93c5fd; font-size: 15px; margin: 28px 0 12px;");
        html.append(" border-bottom: 1px solid #2a2d3a; padding-bottom: 6px; }\n");
        html.append(".card { background: #1a1d26; border: 1px solid #2a2d3a; border-radius: 10px;");
        html.append(" padding: 16px 20px; margin-bottom: 16px; }\n");
        html.append(".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px,1fr)); gap: 12px; margin-bottom: 20px; }\n");
        html.append(".stat .label { font-size: 11px; color: #888; text-transform: uppercase; }\n");
        html.append(".stat .value { font-size: 20px; font-weight: 700; margin-top: 4px; }\n");
        html.append("table { width: 100%; border-collapse: collapse; background: #1a1d26;");
        html.append(" border: 1px solid #2a2d3a; border-radius: 8px; overflow: hidden; }\n");
        html.append("th { background: #252830; color: #93c5fd; font-size: 12px; padding: 10px 14px; text-align: left; }\n");
        html.append("td { padding: 9px 14px; border-bottom: 1px solid #1e2130; }\n");
        html.append("tr:last-child td { border-bottom: none; }\n");
        html.append(".log { background: #0a0c10; border: 1px solid #1e2130; border-radius: 6px;");
        html.append(" padding: 14px; font-family: monospace; font-size: 11px; line-height: 1.8;");
        html.append(" white-space: pre-wrap; color: #94a3b8; max-height: 400px; overflow-y: auto; }\n");
        html.append("</style>\n</head>\n<body>\n");

        html.append("<h1>&#x1F697; Negotiation Report</h1>\n");
        html.append("<p style=\'color:#888;margin-bottom:20px\'>")
                .append(escapeHtml(r.carDescription)).append(" &nbsp;|&nbsp; ")
                .append(escapeHtml(r.buyerName)).append(" vs ").append(escapeHtml(r.dealerName))
                .append(" &nbsp;|&nbsp; ").append(r.startTime != null ? r.startTime : "").append("</p>\n");

        // Outcome banner
        String bannerColor = isDeal ? "#052e16" : "#2a0f0f";
        String borderColor = isDeal ? "#16a34a" : "#dc2626";
        String outcomeText = isDeal
                ? "&#x2705; DEAL — Final price: RM " + String.format("%,.0f", r.finalPrice)
                + " &nbsp;|&nbsp; Saved: RM " + String.format("%,.0f", saving)
                + " (" + String.format("%.1f", savingPct) + "%)"
                : "&#x274C; NO DEAL";
        html.append("<div style=\'background:").append(bannerColor)
                .append(";border:1px solid ").append(borderColor)
                .append(";border-radius:10px;padding:14px 20px;margin-bottom:20px;font-size:16px;font-weight:700\'>")
                .append(outcomeText).append("</div>\n");

        // Stat cards
        html.append("<div class=\'grid\'>\n");
        appendStat(html, "Asking Price",       "color:#f97316", "RM " + String.format("%,.0f", r.askingPrice));
        appendStat(html, "Buyer First Offer",  "color:#60a5fa", r.buyerFirstOffer > 0 ? "RM " + String.format("%,.0f", r.buyerFirstOffer) : "—");
        appendStat(html, "Buyer Budget",       "color:#a5b4fc", r.buyerReservationPrice > 0 ? "RM " + String.format("%,.0f", r.buyerReservationPrice) : "—");
        appendStat(html, "Rounds",             "color:#fff",    r.rounds.size() + " / " + r.maxRounds);
        appendStat(html, "Strategy",           "color:#fbbf24", r.strategyName != null ? escapeHtml(r.strategyName) : "Manual");
        appendStat(html, "Final Price",        isDeal ? "color:#4ade80" : "color:#f87171",
                isDeal ? "RM " + String.format("%,.0f", r.finalPrice) : "—");
        html.append("</div>\n");

        // Chart
        html.append("<h2>&#x1F4C8; Price Negotiation Chart</h2>\n");
        html.append("<div class=\'card\'><canvas id=\'negChart\'></canvas></div>\n");

        // Table
        html.append("<h2>&#x1F4CB; Round-by-Round Breakdown</h2>\n");
        html.append("<table><thead><tr>")
                .append("<th>Round</th><th>Dealer Offer</th><th>Buyer Offer</th>")
                .append("<th>Gap</th><th>Strategy Reasoning</th>")
                .append("</tr></thead><tbody>\n")
                .append(rows)
                .append("</tbody></table>\n");

        // Thinking log
        html.append("<h2>&#x1F9E0; Full Thinking Log</h2>\n");
        html.append("<div class=\'log\'>").append(escapeHtml(log.toString())).append("</div>\n");

        html.append("<p style=\'color:#555;font-size:11px;margin-top:24px\'>")
                .append("Report generated: ").append(new SimpleDateFormat("HH:mm:ss dd MMM yyyy").format(new Date()))
                .append(" &nbsp;|&nbsp; ID: ").append(r.negotiationId != null ? r.negotiationId : "—")
                .append(" &nbsp;|&nbsp; Mode: ").append(r.autoNegotiated ? "Automated" : "Manual")
                .append("</p>\n");

        // Chart script — no % signs here, just numbers
        html.append("<script>\nnew Chart(document.getElementById(\'negChart\').getContext(\'2d\'), {\n");
        html.append("  type: \'line\',\n");
        html.append("  data: { labels: [").append(labelsJson).append("],\n");
        html.append("    datasets: [\n");
        html.append("      { label: \'Dealer Offer (RM)\', data: [").append(dealerJson).append("],");
        html.append(" borderColor: \'#f97316\', backgroundColor: \'rgba(249,115,22,0.08)\',");
        html.append(" borderWidth: 2.5, pointRadius: 5, tension: 0.3, fill: true },\n");
        html.append("      { label: \'Buyer Offer (RM)\', data: [").append(buyerJson).append("],");
        html.append(" borderColor: \'#60a5fa\', backgroundColor: \'rgba(96,165,250,0.08)\',");
        html.append(" borderWidth: 2.5, pointRadius: 5, tension: 0.3, fill: true }\n");
        html.append("    ]\n  },\n");
        html.append("  options: { responsive: true,\n");
        html.append("    plugins: { legend: { labels: { color: \'#e0e0e0\' } },\n");
        html.append("      tooltip: { callbacks: { label: c => c.dataset.label + \': RM \' + c.parsed.y.toLocaleString() } }\n");
        html.append("    },\n");
        html.append("    scales: {\n");
        html.append("      x: { ticks: { color: \'#888\' }, grid: { color: \'#1e2130\' } },\n");
        html.append("      y: { ticks: { color: \'#888\', callback: v => \'RM \' + v.toLocaleString() }, grid: { color: \'#1e2130\' } }\n");
        html.append("    }\n  }\n});\n</script>\n");
        html.append("</body>\n</html>\n");

        return html.toString();
    }

    private static void appendStat(StringBuilder sb, String label, String style, String value) {
        sb.append("<div class=\'card stat\'><div class=\'label\'>").append(label).append("</div>")
                .append("<div class=\'value\' style=\'").append(style).append("\'>").append(value).append("</div></div>\n");
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}