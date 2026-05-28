package negotiation.experiments;

import negotiation.strategy.NegotiationContext;
import negotiation.strategy.NegotiationStrategy;
import negotiation.strategy.StrategyRegistry;
import negotiation.testing.NegotiationTestLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs offline strategy-vs-strategy negotiation experiments.
 *
 * This does not start JADE agents. It reuses the strategy classes directly so
 * many experiments can run concurrently and produce repeatable report folders.
 *
 * Example:
 *   java -cp target/classes negotiation.experiments.StrategyExperimentRunner --threads 6
 *
 * Output:
 *   experiment_results/session_yyyy-MM-dd_HH-mm-ss/
 *     summary.txt
 *     summary.csv
 *     exp_001_BALANCED_BAYESIAN_vs_TIT_FOR_TAT/
 *       transcript.txt
 *       result.csv
 *       result.json
 */
public final class StrategyExperimentRunner {

    private static final DateTimeFormatter SESSION_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final DecimalFormat MONEY = new DecimalFormat("0");

    private StrategyExperimentRunner() {
    }

    public static void main(String[] args) throws Exception {
        ExperimentOptions options = ExperimentOptions.parse(args);
        Path sessionDir = createSessionDirectory(options.outputRoot);

        List<ExperimentCase> cases = buildCases(options);
        System.out.println("Experiment session: " + sessionDir.toAbsolutePath());
        System.out.println("Experiments queued: " + cases.size());
        System.out.println("Threads: " + options.threads);

        ExecutorService executor = Executors.newFixedThreadPool(options.threads);
        List<Future<ExperimentResult>> futures = new ArrayList<>();
        AtomicInteger finished = new AtomicInteger();

        for (ExperimentCase experimentCase : cases) {
            futures.add(executor.submit(new ExperimentTask(experimentCase, sessionDir, finished, cases.size())));
        }

        List<ExperimentResult> results = new ArrayList<>();
        try {
            for (Future<ExperimentResult> future : futures) {
                results.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        results.sort(Comparator.comparingInt(r -> r.caseNumber));
        writeSessionReports(sessionDir, results);
        NegotiationTestLogger.refreshReportsFromExperimentSummary(sessionDir.resolve("summary.csv"));
        System.out.println("Session complete.");
        System.out.println("Summary: " + sessionDir.resolve("summary.txt").toAbsolutePath());
        System.out.println("CSV:     " + sessionDir.resolve("summary.csv").toAbsolutePath());
        System.out.println("HTML:    " + sessionDir.getParent().resolve("negotiation-test-report.html").toAbsolutePath());
    }

    private static List<ExperimentCase> buildCases(ExperimentOptions options) {
        List<String> buyerKeys = options.buyerKeys.isEmpty()
                ? StrategyRegistry.getKeys()
                : options.buyerKeys;
        List<String> dealerKeys = options.dealerKeys.isEmpty()
                ? StrategyRegistry.getKeys()
                : options.dealerKeys;

        List<Scenario> scenarios = List.of(
                new Scenario("BALANCED", 80000, 0.80, 0.98, 0.88, options.maxRounds),
                new Scenario("TIGHT_GAP", 80000, 0.78, 0.91, 0.89, options.maxRounds),
                new Scenario("WIDE_GAP", 80000, 0.76, 1.03, 0.84, options.maxRounds)
        );

        List<ExperimentCase> cases = new ArrayList<>();
        int caseNumber = 1;
        for (int repeat = 1; repeat <= options.repeats; repeat++) {
            for (Scenario scenario : scenarios) {
                for (String buyerKey : buyerKeys) {
                    for (String dealerKey : dealerKeys) {
                        cases.add(new ExperimentCase(caseNumber++, repeat, scenario, buyerKey, dealerKey));
                    }
                }
            }
        }
        return cases;
    }

    private static Path createSessionDirectory(Path outputRoot) throws IOException {
        Files.createDirectories(outputRoot);
        Path sessionDir = outputRoot.resolve("session_" + SESSION_FORMAT.format(LocalDateTime.now()));
        int suffix = 2;
        while (Files.exists(sessionDir)) {
            sessionDir = outputRoot.resolve("session_" + SESSION_FORMAT.format(LocalDateTime.now()) + "_" + suffix++);
        }
        Files.createDirectories(sessionDir);
        return sessionDir;
    }

    private static final class ExperimentTask implements Callable<ExperimentResult> {
        private final ExperimentCase experimentCase;
        private final Path sessionDir;
        private final AtomicInteger finished;
        private final int total;

        private ExperimentTask(ExperimentCase experimentCase, Path sessionDir,
                               AtomicInteger finished, int total) {
            this.experimentCase = experimentCase;
            this.sessionDir = sessionDir;
            this.finished = finished;
            this.total = total;
        }

        @Override
        public ExperimentResult call() throws Exception {
            String label = experimentCase.label();
            System.out.println("START [" + Thread.currentThread().getName() + "] " + label);
            ExperimentResult result = runExperiment(experimentCase);
            Path experimentDir = sessionDir.resolve(experimentCase.folderName());
            Files.createDirectories(experimentDir);
            writeExperimentReports(experimentDir, result);
            int done = finished.incrementAndGet();
            System.out.println("DONE  [" + Thread.currentThread().getName() + "] "
                    + label + " -> " + result.outcome + " (" + done + "/" + total + ")");
            return result;
        }
    }

    private static ExperimentResult runExperiment(ExperimentCase experimentCase) {
        NegotiationStrategy buyerStrategy = StrategyRegistry.create(experimentCase.buyerStrategyKey);
        NegotiationStrategy dealerStrategy = StrategyRegistry.create(experimentCase.dealerStrategyKey);
        Scenario s = experimentCase.scenario;

        NegotiationContext buyerCtx = buyerContext(s, experimentCase);
        NegotiationContext dealerCtx = dealerContext(s, experimentCase);
        buyerStrategy.initialise(buyerCtx);
        dealerStrategy.initialise(dealerCtx);

        ExperimentResult result = new ExperimentResult(experimentCase);
        result.buyerStrategyName = buyerStrategy.getDisplayName();
        result.dealerStrategyName = dealerStrategy.getDisplayName();
        result.askingPrice = s.askingPrice;
        result.buyerFirstOffer = s.buyerFirstOffer();
        result.buyerReservationPrice = s.buyerReservationPrice();
        result.dealerReservationPrice = s.dealerReservationPrice();

        double buyerOffer = roundMoney(s.buyerFirstOffer());
        buyerCtx.currentRound = 1;
        buyerCtx.recordMyOffer(buyerOffer);
        result.lines.add(new TranscriptLine(1, "BUYER", "OFFER", buyerOffer, "Opening offer"));

        for (int round = 1; round <= s.maxRounds; round++) {
            dealerCtx.currentRound = round;
            dealerCtx.recordOpponentOffer(buyerOffer);
            dealerStrategy.onOpponentOffer(buyerOffer, round);

            if (dealerAccepts(buyerOffer, dealerCtx, dealerStrategy)) {
                result.complete("DEAL", "DEALER", buyerOffer, round, dealerStrategy.getLastReasoning());
                break;
            }

            double dealerOffer = clampDealerOffer(dealerStrategy.generateOffer(dealerCtx), dealerCtx);
            if (dealerOffer <= buyerOffer && buyerOffer >= dealerCtx.reservationPrice) {
                result.lines.add(new TranscriptLine(round, "DEALER", "WOULD_ACCEPT", buyerOffer,
                        "Counter would be below buyer offer; accepting buyer price."));
                result.complete("DEAL", "DEALER", buyerOffer, round, dealerStrategy.getLastReasoning());
                break;
            }

            dealerCtx.recordMyOffer(dealerOffer);
            result.lines.add(new TranscriptLine(round, "DEALER", "OFFER", dealerOffer,
                    dealerStrategy.getLastReasoning()));

            buyerCtx.currentRound = round;
            buyerCtx.recordOpponentOffer(dealerOffer);
            buyerStrategy.onOpponentOffer(dealerOffer, round);

            if (buyerAccepts(dealerOffer, buyerCtx, buyerStrategy)) {
                result.complete("DEAL", "BUYER", dealerOffer, round, buyerStrategy.getLastReasoning());
                break;
            }

            if (round == s.maxRounds) {
                result.complete("NO_DEAL", "NONE", 0, round,
                        "Deadline reached without either side accepting.");
                break;
            }

            buyerOffer = clampBuyerOffer(buyerStrategy.generateOffer(buyerCtx), buyerCtx);
            if (buyerOffer >= dealerOffer && dealerOffer <= buyerCtx.reservationPrice) {
                result.lines.add(new TranscriptLine(round, "BUYER", "WOULD_ACCEPT", dealerOffer,
                        "Counter would meet dealer offer; accepting dealer price."));
                result.complete("DEAL", "BUYER", dealerOffer, round, buyerStrategy.getLastReasoning());
                break;
            }

            buyerCtx.recordMyOffer(buyerOffer);
            result.lines.add(new TranscriptLine(round + 1, "BUYER", "OFFER", buyerOffer,
                    buyerStrategy.getLastReasoning()));
        }

        result.messageCount = result.lines.size();
        result.savingFromAsk = result.finalPrice > 0 ? s.askingPrice - result.finalPrice : 0;
        result.savingPct = result.finalPrice > 0 ? result.savingFromAsk / s.askingPrice * 100.0 : 0;
        return result;
    }

    private static boolean buyerAccepts(double dealerOffer, NegotiationContext buyerCtx,
                                        NegotiationStrategy buyerStrategy) {
        if (dealerOffer <= 0) return false;
        if (dealerOffer <= buyerCtx.lastMyOffer) return true;
        if (dealerOffer > buyerCtx.reservationPrice) return false;
        return buyerStrategy.shouldAccept(dealerOffer, buyerCtx)
                || (buyerCtx.isLastRound() && dealerOffer <= buyerCtx.reservationPrice);
    }

    private static boolean dealerAccepts(double buyerOffer, NegotiationContext dealerCtx,
                                         NegotiationStrategy dealerStrategy) {
        if (buyerOffer <= 0) return false;
        if (buyerOffer >= dealerCtx.lastMyOffer) return true;
        if (buyerOffer < dealerCtx.reservationPrice) return false;
        return dealerStrategy.shouldAccept(buyerOffer, dealerCtx)
                || (dealerCtx.isLastRound() && buyerOffer >= dealerCtx.reservationPrice);
    }

    private static NegotiationContext buyerContext(Scenario scenario, ExperimentCase experimentCase) {
        NegotiationContext ctx = new NegotiationContext();
        ctx.negotiationId = experimentCase.label();
        ctx.role = NegotiationContext.Role.BUYER;
        ctx.askingPrice = scenario.askingPrice;
        ctx.firstOffer = roundMoney(scenario.buyerFirstOffer());
        ctx.reservationPrice = roundMoney(scenario.buyerReservationPrice());
        ctx.maxRounds = scenario.maxRounds;
        ctx.carDescription = "Experiment car";
        ctx.opponentName = "Dealer-" + experimentCase.dealerStrategyKey;
        ctx.lastMyOffer = ctx.firstOffer;
        return ctx;
    }

    private static NegotiationContext dealerContext(Scenario scenario, ExperimentCase experimentCase) {
        NegotiationContext ctx = new NegotiationContext();
        ctx.negotiationId = experimentCase.label();
        ctx.role = NegotiationContext.Role.DEALER;
        ctx.askingPrice = scenario.askingPrice;
        ctx.firstOffer = roundMoney(scenario.askingPrice);
        ctx.reservationPrice = roundMoney(scenario.dealerReservationPrice());
        ctx.maxRounds = scenario.maxRounds;
        ctx.carDescription = "Experiment car";
        ctx.opponentName = "Buyer-" + experimentCase.buyerStrategyKey;
        ctx.lastMyOffer = ctx.firstOffer;
        return ctx;
    }

    private static double clampBuyerOffer(double offer, NegotiationContext ctx) {
        offer = Math.max(offer, Math.min(ctx.firstOffer, ctx.reservationPrice));
        offer = Math.min(offer, Math.max(ctx.firstOffer, ctx.reservationPrice));
        return roundMoney(offer);
    }

    private static double clampDealerOffer(double offer, NegotiationContext ctx) {
        offer = Math.max(offer, Math.min(ctx.firstOffer, ctx.reservationPrice));
        offer = Math.min(offer, Math.max(ctx.firstOffer, ctx.reservationPrice));
        return roundMoney(offer);
    }

    private static double roundMoney(double value) {
        return Math.round(value / 100.0) * 100.0;
    }

    private static void writeExperimentReports(Path experimentDir, ExperimentResult result) throws IOException {
        Files.writeString(experimentDir.resolve("transcript.txt"), buildTranscript(result), StandardCharsets.UTF_8);
        Files.writeString(experimentDir.resolve("result.csv"), csvHeader() + result.toCsvRow() + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(experimentDir.resolve("result.json"), result.toJson(), StandardCharsets.UTF_8);
    }

    private static void writeSessionReports(Path sessionDir, List<ExperimentResult> results) throws IOException {
        StringBuilder csv = new StringBuilder(csvHeader());
        for (ExperimentResult result : results) {
            csv.append(result.toCsvRow()).append('\n');
        }
        Files.writeString(sessionDir.resolve("summary.csv"), csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(sessionDir.resolve("summary.txt"), buildSummary(results), StandardCharsets.UTF_8);
    }

    private static String buildTranscript(ExperimentResult result) {
        StringBuilder out = new StringBuilder();
        out.append("EXPERIMENT ").append(result.caseNumber).append('\n');
        out.append("================\n");
        out.append("Scenario: ").append(result.scenarioName).append('\n');
        out.append("Buyer strategy: ").append(result.buyerStrategyKey)
                .append(" (").append(result.buyerStrategyName).append(")\n");
        out.append("Dealer strategy: ").append(result.dealerStrategyKey)
                .append(" (").append(result.dealerStrategyName).append(")\n");
        out.append("Asking: RM ").append(money(result.askingPrice))
                .append(" | Buyer first: RM ").append(money(result.buyerFirstOffer))
                .append(" | Buyer max: RM ").append(money(result.buyerReservationPrice))
                .append(" | Dealer floor: RM ").append(money(result.dealerReservationPrice))
                .append('\n');
        out.append("Outcome: ").append(result.outcome)
                .append(" | Accepted by: ").append(result.acceptedBy)
                .append(" | Final price: ").append(result.finalPrice > 0 ? "RM " + money(result.finalPrice) : "-")
                .append(" | Rounds: ").append(result.roundsTaken).append('\n');
        out.append('\n');
        out.append("TRANSCRIPT\n");
        out.append("----------\n");
        for (TranscriptLine line : result.lines) {
            out.append("Round ").append(line.round)
                    .append(" | ").append(line.actor)
                    .append(" | ").append(line.action)
                    .append(" | ").append(line.price > 0 ? "RM " + money(line.price) : "-")
                    .append(" | ").append(line.reasoning == null ? "" : line.reasoning)
                    .append('\n');
        }
        out.append('\n').append("Final note: ").append(result.finalReason).append('\n');
        return out.toString();
    }

    private static String buildSummary(List<ExperimentResult> results) {
        long deals = results.stream().filter(r -> "DEAL".equals(r.outcome)).count();
        double dealRate = results.isEmpty() ? 0 : deals * 100.0 / results.size();
        double avgRounds = results.stream().mapToInt(r -> r.roundsTaken).average().orElse(0);
        double avgFinal = results.stream()
                .filter(r -> r.finalPrice > 0)
                .mapToDouble(r -> r.finalPrice)
                .average()
                .orElse(0);

        StringBuilder out = new StringBuilder();
        out.append("STRATEGY EXPERIMENT SESSION\n");
        out.append("===========================\n");
        out.append("Experiments: ").append(results.size()).append('\n');
        out.append("Deals: ").append(deals)
                .append(" (").append(formatPct(dealRate)).append("%)\n");
        out.append("Average rounds: ").append(String.format(Locale.ROOT, "%.2f", avgRounds)).append('\n');
        out.append("Average final price: ").append(avgFinal > 0 ? "RM " + money(avgFinal) : "-").append("\n\n");

        out.append("BEST BUYER OUTCOMES BY FINAL PRICE\n");
        results.stream()
                .filter(r -> r.finalPrice > 0)
                .sorted(Comparator.comparingDouble(r -> r.finalPrice))
                .limit(10)
                .forEach(r -> out.append(shortResult(r)).append('\n'));

        out.append("\nFASTEST DEALS\n");
        results.stream()
                .filter(r -> "DEAL".equals(r.outcome))
                .sorted(Comparator.comparingInt(r -> r.roundsTaken))
                .limit(10)
                .forEach(r -> out.append(shortResult(r)).append('\n'));
        return out.toString();
    }

    private static String shortResult(ExperimentResult r) {
        return String.format(Locale.ROOT,
                "#%03d %-10s buyer=%-24s dealer=%-24s outcome=%-7s rounds=%2d final=%s",
                r.caseNumber,
                r.scenarioName,
                r.buyerStrategyKey,
                r.dealerStrategyKey,
                r.outcome,
                r.roundsTaken,
                r.finalPrice > 0 ? "RM " + money(r.finalPrice) : "-");
    }

    private static String csvHeader() {
        return "case,repeat,scenario,buyer_strategy,buyer_strategy_name,dealer_strategy,"
                + "dealer_strategy_name,outcome,accepted_by,rounds,messages,asking_price,"
                + "buyer_first_offer,buyer_reservation_price,dealer_reservation_price,"
                + "final_price,saving_from_ask,saving_pct,folder\n";
    }

    private static String money(double value) {
        return MONEY.format(value);
    }

    private static String formatPct(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private record Scenario(String name, double askingPrice, double buyerFirstPct,
                            double buyerReservationPct, double dealerFloorPct, int maxRounds) {
        double buyerFirstOffer() {
            return askingPrice * buyerFirstPct;
        }

        double buyerReservationPrice() {
            return askingPrice * buyerReservationPct;
        }

        double dealerReservationPrice() {
            return askingPrice * dealerFloorPct;
        }
    }

    private record ExperimentCase(int caseNumber, int repeat, Scenario scenario,
                                  String buyerStrategyKey, String dealerStrategyKey) {
        String label() {
            return String.format(Locale.ROOT, "#%03d %s %s vs %s",
                    caseNumber, scenario.name(), buyerStrategyKey, dealerStrategyKey);
        }

        String folderName() {
            return String.format(Locale.ROOT, "exp_%03d_%s_%s_vs_%s",
                    caseNumber, safeName(scenario.name()), safeName(buyerStrategyKey), safeName(dealerStrategyKey));
        }
    }

    private record TranscriptLine(int round, String actor, String action, double price, String reasoning) {
    }

    private static final class ExperimentResult {
        final int caseNumber;
        final int repeat;
        final String scenarioName;
        final String buyerStrategyKey;
        final String dealerStrategyKey;
        final String folderName;
        final List<TranscriptLine> lines = new ArrayList<>();

        String buyerStrategyName;
        String dealerStrategyName;
        String outcome = "NO_DEAL";
        String acceptedBy = "NONE";
        String finalReason = "";
        int roundsTaken;
        int messageCount;
        double askingPrice;
        double buyerFirstOffer;
        double buyerReservationPrice;
        double dealerReservationPrice;
        double finalPrice;
        double savingFromAsk;
        double savingPct;

        ExperimentResult(ExperimentCase c) {
            this.caseNumber = c.caseNumber();
            this.repeat = c.repeat();
            this.scenarioName = c.scenario().name();
            this.buyerStrategyKey = c.buyerStrategyKey();
            this.dealerStrategyKey = c.dealerStrategyKey();
            this.folderName = c.folderName();
        }

        void complete(String outcome, String acceptedBy, double finalPrice, int roundsTaken, String reason) {
            this.outcome = outcome;
            this.acceptedBy = acceptedBy;
            this.finalPrice = finalPrice;
            this.roundsTaken = roundsTaken;
            this.finalReason = reason == null ? "" : reason;
            if ("DEAL".equals(outcome)) {
                lines.add(new TranscriptLine(roundsTaken, acceptedBy, "ACCEPT", finalPrice, this.finalReason));
            }
        }

        String toCsvRow() {
            return caseNumber + ","
                    + repeat + ","
                    + csv(scenarioName) + ","
                    + csv(buyerStrategyKey) + ","
                    + csv(buyerStrategyName) + ","
                    + csv(dealerStrategyKey) + ","
                    + csv(dealerStrategyName) + ","
                    + csv(outcome) + ","
                    + csv(acceptedBy) + ","
                    + roundsTaken + ","
                    + messageCount + ","
                    + money(askingPrice) + ","
                    + money(buyerFirstOffer) + ","
                    + money(buyerReservationPrice) + ","
                    + money(dealerReservationPrice) + ","
                    + money(finalPrice) + ","
                    + money(savingFromAsk) + ","
                    + formatPct(savingPct) + ","
                    + csv(folderName);
        }

        String toJson() {
            return "{\n"
                    + "  \"case\": " + caseNumber + ",\n"
                    + "  \"repeat\": " + repeat + ",\n"
                    + "  \"scenario\": \"" + json(scenarioName) + "\",\n"
                    + "  \"buyerStrategy\": \"" + json(buyerStrategyKey) + "\",\n"
                    + "  \"dealerStrategy\": \"" + json(dealerStrategyKey) + "\",\n"
                    + "  \"outcome\": \"" + json(outcome) + "\",\n"
                    + "  \"acceptedBy\": \"" + json(acceptedBy) + "\",\n"
                    + "  \"roundsTaken\": " + roundsTaken + ",\n"
                    + "  \"messageCount\": " + messageCount + ",\n"
                    + "  \"askingPrice\": " + money(askingPrice) + ",\n"
                    + "  \"buyerFirstOffer\": " + money(buyerFirstOffer) + ",\n"
                    + "  \"buyerReservationPrice\": " + money(buyerReservationPrice) + ",\n"
                    + "  \"dealerReservationPrice\": " + money(dealerReservationPrice) + ",\n"
                    + "  \"finalPrice\": " + money(finalPrice) + ",\n"
                    + "  \"savingFromAsk\": " + money(savingFromAsk) + ",\n"
                    + "  \"savingPct\": " + formatPct(savingPct) + "\n"
                    + "}\n";
        }
    }

    private static final class ExperimentOptions {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        int maxRounds = 10;
        int repeats = 1;
        Path outputRoot = Path.of("experiment_results");
        List<String> buyerKeys = List.of();
        List<String> dealerKeys = List.of();

        static ExperimentOptions parse(String[] args) {
            ExperimentOptions options = new ExperimentOptions();
            Map<String, String> values = parseArgs(args);
            if (values.containsKey("threads")) {
                options.threads = Math.max(1, Integer.parseInt(values.get("threads")));
            }
            if (values.containsKey("rounds")) {
                options.maxRounds = Math.max(1, Integer.parseInt(values.get("rounds")));
            }
            if (values.containsKey("repeats")) {
                options.repeats = Math.max(1, Integer.parseInt(values.get("repeats")));
            }
            if (values.containsKey("out")) {
                options.outputRoot = Path.of(values.get("out"));
            }
            if (values.containsKey("buyers")) {
                options.buyerKeys = parseKeys(values.get("buyers"));
            }
            if (values.containsKey("dealers")) {
                options.dealerKeys = parseKeys(values.get("dealers"));
            }
            return options;
        }

        private static Map<String, String> parseArgs(String[] args) {
            java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) continue;
                String key = arg.substring(2);
                String value = "true";
                int equals = key.indexOf('=');
                if (equals >= 0) {
                    value = key.substring(equals + 1);
                    key = key.substring(0, equals);
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                }
                values.put(key, value);
            }
            return values;
        }

        private static List<String> parseKeys(String csv) {
            if (csv == null || csv.isBlank() || "all".equalsIgnoreCase(csv.trim())) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            for (String key : csv.split(",")) {
                if (!key.isBlank()) {
                    keys.add(key.trim());
                }
            }
            return keys;
        }
    }
}
