package negotiation.experiments;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import negotiation.strategy.NegotiationContext;
import negotiation.strategy.NegotiationStrategy;
import negotiation.strategy.StrategyRegistry;
import negotiation.testing.NegotiationTestLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
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
 *     run_config.json
 *     summary.txt
 *     summary.csv
 *     strategy_pair_summary.csv
 *     exp_001_BALANCED_BAYESIAN_vs_TIT_FOR_TAT/
 *       transcript.txt
 *       result.csv
 *       result.json
 */
public final class StrategyExperimentRunner {

    private static final DateTimeFormatter SESSION_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StrategyExperimentRunner() {
    }

    public static void main(String[] args) throws Exception {
        ExperimentOptions options = ExperimentOptions.parse(args);
        List<ScenarioTemplate> templates = loadScenarioTemplates(options);
        Path sessionDir = createSessionDirectory(options.outputRoot);

        List<ExperimentCase> cases = buildCases(options, templates);
        writeRunConfig(sessionDir, options, templates, cases.size(), args);

        System.out.println("Experiment session: " + sessionDir.toAbsolutePath());
        System.out.println("Experiments queued: " + cases.size());
        System.out.println("Threads: " + options.threads);
        System.out.println("Seed: " + options.seed);
        System.out.println("Repeat variation: "
                + (options.repeatVariation && options.repeats > 1 ? formatPct(options.variationPct * 100.0) + "%" : "disabled"));

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
        writeStrategyPairSummary(sessionDir, results);
        NegotiationTestLogger.refreshReportsFromExperimentSummary(sessionDir.resolve("summary.csv"));

        System.out.println("Session complete.");
        System.out.println("Run config: " + sessionDir.resolve("run_config.json").toAbsolutePath());
        System.out.println("Summary:    " + sessionDir.resolve("summary.txt").toAbsolutePath());
        System.out.println("CSV:        " + sessionDir.resolve("summary.csv").toAbsolutePath());
        System.out.println("Pair stats: " + sessionDir.resolve("strategy_pair_summary.csv").toAbsolutePath());
        System.out.println("HTML:       " + sessionDir.getParent().resolve("negotiation-test-report.html").toAbsolutePath());
    }

    private static List<ExperimentCase> buildCases(ExperimentOptions options, List<ScenarioTemplate> templates) {
        List<String> buyerKeys = options.buyerKeys.isEmpty()
                ? StrategyRegistry.getKeys()
                : validateStrategyKeys(options.buyerKeys);
        List<String> dealerKeys = options.dealerKeys.isEmpty()
                ? StrategyRegistry.getKeys()
                : validateStrategyKeys(options.dealerKeys);

        List<ExperimentCase> cases = new ArrayList<>();
        int caseNumber = 1;
        for (int repeat = 1; repeat <= options.repeats; repeat++) {
            for (int scenarioIndex = 0; scenarioIndex < templates.size(); scenarioIndex++) {
                Scenario scenario = scenarioForRepeat(templates.get(scenarioIndex), options, repeat, scenarioIndex);
                for (String buyerKey : buyerKeys) {
                    for (String dealerKey : dealerKeys) {
                        long caseSeed = mixSeed(scenario.seed(), buyerKey.hashCode(), dealerKey.hashCode(), caseNumber);
                        cases.add(new ExperimentCase(caseNumber++, repeat, scenario, buyerKey, dealerKey, caseSeed));
                    }
                }
            }
        }
        return cases;
    }

    private static List<String> validateStrategyKeys(List<String> keys) {
        List<String> registered = StrategyRegistry.getKeys();
        for (String key : keys) {
            if (!registered.contains(key)) {
                throw new IllegalArgumentException("Unknown strategy '" + key + "'. Registered: " + registered);
            }
        }
        return keys;
    }

    private static List<ScenarioTemplate> loadScenarioTemplates(ExperimentOptions options) throws IOException {
        if (options.scenariosPath == null) {
            return defaultScenarioTemplates(options.maxRounds);
        }

        JsonElement root = JsonParser.parseString(Files.readString(options.scenariosPath, StandardCharsets.UTF_8));
        JsonArray scenarios;
        if (root.isJsonArray()) {
            scenarios = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("scenarios")) {
            scenarios = root.getAsJsonObject().getAsJsonArray("scenarios");
        } else {
            throw new IllegalArgumentException("Scenario file must be a JSON array or an object with a scenarios array.");
        }

        List<ScenarioTemplate> templates = new ArrayList<>();
        for (JsonElement element : scenarios) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String name = jsonString(object, "name", "scenario");
            double askingPrice = jsonDouble(object, 80_000, "askingPrice", "asking_price");
            double buyerFirstPct = jsonPercent(object, 0.80, "buyerFirstPct", "buyer_first_pct");
            double buyerReservationPct = jsonPercent(object, 0.98, "buyerReservationPct", "buyer_reservation_pct");
            double dealerFloorPct = jsonPercent(object, 0.88, "dealerFloorPct", "dealer_floor_pct");
            int maxRounds = options.roundsOverridden
                    ? options.maxRounds
                    : jsonInt(object, options.maxRounds, "maxRounds", "max_rounds");
            templates.add(new ScenarioTemplate(name, askingPrice, buyerFirstPct, buyerReservationPct,
                    dealerFloorPct, maxRounds, options.scenariosPath.toString()));
        }
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("Scenario file did not contain any valid scenarios.");
        }
        return templates;
    }

    private static List<ScenarioTemplate> defaultScenarioTemplates(int maxRounds) {
        return List.of(
                new ScenarioTemplate("BALANCED", 80_000, 0.80, 0.98, 0.88, maxRounds, "built-in"),
                new ScenarioTemplate("TIGHT_GAP", 80_000, 0.78, 0.91, 0.89, maxRounds, "built-in"),
                new ScenarioTemplate("WIDE_GAP", 80_000, 0.76, 1.03, 0.84, maxRounds, "built-in")
        );
    }

    private static Scenario scenarioForRepeat(ScenarioTemplate template, ExperimentOptions options,
                                              int repeat, int scenarioIndex) {
        long seed = mixSeed(options.seed, repeat, scenarioIndex, template.name().hashCode());
        boolean vary = options.repeatVariation && options.repeats > 1 && options.variationPct > 0;
        if (!vary) {
            return new Scenario(template.name(), roundMoney(template.askingPrice()), template.buyerFirstPct(),
                    template.buyerReservationPct(), template.dealerFloorPct(), template.maxRounds(), seed, false);
        }

        Random rng = new Random(seed);
        double askingPrice = roundMoney(template.askingPrice() * jitterFactor(rng, options.variationPct));
        double buyerFirstPct = clamp(template.buyerFirstPct() + jitterAmount(rng, options.variationPct * 0.60), 0.50, 1.15);
        double buyerReservationPct = clamp(template.buyerReservationPct() + jitterAmount(rng, options.variationPct * 0.75), 0.55, 1.20);
        double dealerFloorPct = clamp(template.dealerFloorPct() + jitterAmount(rng, options.variationPct * 0.75), 0.50, 1.15);

        if (buyerReservationPct < buyerFirstPct + 0.01) {
            buyerReservationPct = Math.min(1.20, buyerFirstPct + 0.01);
        }
        if (dealerFloorPct > buyerReservationPct - 0.005) {
            dealerFloorPct = Math.max(0.50, buyerReservationPct - 0.005);
        }

        return new Scenario(template.name(), askingPrice, buyerFirstPct, buyerReservationPct,
                dealerFloorPct, template.maxRounds(), seed, true);
    }

    private static double jitterFactor(Random rng, double maxFraction) {
        return 1.0 + jitterAmount(rng, maxFraction);
    }

    private static double jitterAmount(Random rng, double maxFraction) {
        return (rng.nextDouble() * 2.0 - 1.0) * maxFraction;
    }

    private static long mixSeed(long seed, long... values) {
        long mixed = seed ^ 0x9E3779B97F4A7C15L;
        for (long value : values) {
            mixed ^= value + 0x9E3779B97F4A7C15L + (mixed << 6) + (mixed >>> 2);
        }
        return mixed;
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

    private static void writeRunConfig(Path sessionDir, ExperimentOptions options,
                                       List<ScenarioTemplate> templates, int caseCount,
                                       String[] args) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.put("commandArgs", List.of(args));
        root.put("gitCommit", detectGitCommit());
        root.put("javaVersion", System.getProperty("java.version"));
        root.put("threads", options.threads);
        root.put("rounds", options.maxRounds);
        root.put("roundsOverridden", options.roundsOverridden);
        root.put("repeats", options.repeats);
        root.put("seed", options.seed);
        root.put("repeatVariation", options.repeatVariation && options.repeats > 1);
        root.put("variationPct", options.variationPct);
        root.put("outputRoot", options.outputRoot.toString());
        root.put("scenarioFile", options.scenariosPath == null ? "built-in" : options.scenariosPath.toString());
        root.put("registeredStrategies", StrategyRegistry.getKeys());
        root.put("buyerStrategies", options.buyerKeys.isEmpty() ? List.of("all") : options.buyerKeys);
        root.put("dealerStrategies", options.dealerKeys.isEmpty() ? List.of("all") : options.dealerKeys);
        root.put("experimentCaseCount", caseCount);
        root.put("scenarioDefinitions", templates.stream().map(ScenarioTemplate::toMap).toList());
        Files.writeString(sessionDir.resolve("run_config.json"), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
    }

    private static String detectGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !output.isBlank() ? output : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
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
        computeDerivedMetrics(result);
        return result;
    }

    private static void computeDerivedMetrics(ExperimentResult result) {
        for (TranscriptLine line : result.lines) {
            if (line.price() <= 0) {
                continue;
            }
            if ("BUYER".equals(line.actor())) {
                result.lastBuyerOffer = line.price();
            } else if ("DEALER".equals(line.actor())) {
                result.lastDealerOffer = line.price();
            }
        }
        if (result.lastBuyerOffer <= 0) {
            result.lastBuyerOffer = result.buyerFirstOffer;
        }
        if (result.lastDealerOffer <= 0) {
            result.lastDealerOffer = result.askingPrice;
        }

        result.savingFromAsk = result.finalPrice > 0 ? result.askingPrice - result.finalPrice : 0;
        result.savingPct = result.finalPrice > 0 ? result.savingFromAsk / result.askingPrice * 100.0 : 0;
        if (result.askingPrice > 0 && result.buyerReservationPrice >= result.askingPrice) {
            result.baselineOutcome = "BUY_AT_ASKING";
            result.baselinePrice = result.askingPrice;
        }
        result.baselineSavingFromAsk = result.baselinePrice > 0 ? result.askingPrice - result.baselinePrice : 0;
        result.negotiationGainVsBaseline = result.baselinePrice > 0 && result.finalPrice > 0
                ? result.baselinePrice - result.finalPrice
                : 0;
        result.agreementSurplus = result.buyerReservationPrice - result.dealerReservationPrice;
        result.buyerConcessionPct = percentOfRange(result.lastBuyerOffer - result.buyerFirstOffer,
                result.buyerReservationPrice - result.buyerFirstOffer);
        result.dealerConcessionPct = percentOfRange(result.askingPrice - result.lastDealerOffer,
                result.askingPrice - result.dealerReservationPrice);

        if (!"DEAL".equals(result.outcome) || result.finalPrice <= 0) {
            result.benefitSide = "NO_DEAL";
            return;
        }

        result.buyerSurplus = result.buyerReservationPrice - result.finalPrice;
        result.dealerProfit = result.finalPrice - result.dealerReservationPrice;
        result.buyerDistanceFromReservation = result.buyerReservationPrice - result.finalPrice;
        result.dealerDistanceFromReservation = result.finalPrice - result.dealerReservationPrice;
        result.buyerUtility = clamp01((result.buyerReservationPrice - result.finalPrice)
                / Math.max(1.0, result.buyerReservationPrice - result.buyerFirstOffer));
        result.dealerUtility = clamp01((result.finalPrice - result.dealerReservationPrice)
                / Math.max(1.0, result.askingPrice - result.dealerReservationPrice));
        result.jointUtility = result.buyerUtility + result.dealerUtility;

        double delta = result.buyerUtility - result.dealerUtility;
        if (delta > 0.05) {
            result.benefitSide = "BUYER";
        } else if (delta < -0.05) {
            result.benefitSide = "DEALER";
        } else {
            result.benefitSide = "BALANCED";
        }
    }

    private static double percentOfRange(double value, double range) {
        if (Math.abs(range) < 0.0001) {
            return 0;
        }
        return clamp(value / range * 100.0, 0, 100);
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
        Files.writeString(experimentDir.resolve("report.html"), buildExperimentHtml(result), StandardCharsets.UTF_8);
    }

    private static void writeSessionReports(Path sessionDir, List<ExperimentResult> results) throws IOException {
        StringBuilder csv = new StringBuilder(csvHeader());
        for (ExperimentResult result : results) {
            csv.append(result.toCsvRow()).append('\n');
        }
        Files.writeString(sessionDir.resolve("summary.csv"), csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(sessionDir.resolve("summary.txt"), buildSummary(results), StandardCharsets.UTF_8);
    }

    private static void writeStrategyPairSummary(Path sessionDir, List<ExperimentResult> results) throws IOException {
        Map<String, StrategyPairStats> stats = new LinkedHashMap<>();
        for (ExperimentResult result : results) {
            String key = result.buyerStrategyKey + "\t" + result.dealerStrategyKey;
            stats.computeIfAbsent(key, ignored -> new StrategyPairStats(result.buyerStrategyKey, result.dealerStrategyKey))
                    .add(result);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("buyer_strategy,dealer_strategy,cases,deals,deal_rate,avg_rounds,")
                .append("avg_final_price,final_price_stddev,best_final_price,worst_final_price,")
                .append("avg_buyer_utility,avg_dealer_utility,avg_joint_utility,")
                .append("avg_buyer_concession_pct,avg_dealer_concession_pct,")
                .append("baseline_deals,baseline_deal_rate,deal_rate_delta_vs_baseline,")
                .append("avg_baseline_price,avg_negotiation_gain_vs_baseline\n");
        stats.values().stream()
                .sorted(Comparator
                        .comparingDouble(StrategyPairStats::dealRate).reversed()
                        .thenComparingDouble(StrategyPairStats::averageFinalPrice)
                        .thenComparing(s -> s.buyerStrategy))
                .forEach(s -> csv.append(s.toCsvRow()).append('\n'));
        Files.writeString(sessionDir.resolve("strategy_pair_summary.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    private static String buildTranscript(ExperimentResult result) {
        StringBuilder out = new StringBuilder();
        out.append("EXPERIMENT ").append(result.caseNumber).append('\n');
        out.append("================\n");
        out.append("Scenario: ").append(result.scenarioName).append('\n');
        out.append("Repeat: ").append(result.repeat)
                .append(" | Scenario seed: ").append(result.scenarioSeed)
                .append(" | Case seed: ").append(result.caseSeed)
                .append(" | Varied scenario: ").append(result.scenarioVaried).append('\n');
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
        out.append("Utility: buyer=").append(formatPct(result.buyerUtility))
                .append(" dealer=").append(formatPct(result.dealerUtility))
                .append(" joint=").append(formatPct(result.jointUtility))
                .append(" | Benefit side: ").append(result.benefitSide).append('\n');
        out.append("Surplus: agreement=").append(formatMoneyOrDash(result.agreementSurplus))
                .append(" buyer=").append(formatMoneyOrDash(result.buyerSurplus))
                .append(" dealer=").append(formatMoneyOrDash(result.dealerProfit)).append('\n');
        out.append("Baseline: ").append(result.baselineOutcome)
                .append(" at ").append(formatMoneyOrDash(result.baselinePrice))
                .append(" | Negotiation gain vs baseline: ")
                .append(formatMoneyOrDash(result.negotiationGainVsBaseline)).append('\n');
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

    private static String buildExperimentHtml(ExperimentResult result) {
        StringBuilder transcriptRows = new StringBuilder();
        StringBuilder thinkingLog = new StringBuilder();
        double lastBuyerOffer = 0;
        double lastDealerOffer = 0;

        for (TranscriptLine line : result.lines) {
            String price = line.price() > 0 ? "RM " + money(line.price()) : "-";
            boolean buyerTurn = "BUYER".equals(line.actor());
            String partyClass = buyerTurn ? "buyer" : "dealer";
            String partyLabel = buyerTurn
                    ? "Buyer (" + result.buyerStrategyName + ")"
                    : "Dealer (" + result.dealerStrategyName + ")";
            double comparisonOffer = buyerTurn ? lastDealerOffer : lastBuyerOffer;
            String gap = "-";
            if (line.price() > 0 && comparisonOffer > 0) {
                double gapValue = Math.abs(line.price() - comparisonOffer);
                gap = gapValue < 0.01 ? "Matched" : "RM " + money(gapValue);
            }

            transcriptRows.append("<tr>")
                    .append("<td>").append(line.round()).append("</td>")
                    .append("<td><span class=\"").append(partyClass)
                    .append("\">").append(escapeHtml(partyLabel)).append("</span></td>")
                    .append("<td><span class=\"pill neutral\">").append(escapeHtml(line.action())).append("</span></td>")
                    .append("<td class=\"price\">").append(escapeHtml(price)).append("</td>")
                    .append("<td>").append(escapeHtml(gap)).append("</td>")
                    .append("<td>").append(escapeHtml(line.reasoning())).append("</td>")
                    .append("</tr>\n");

            thinkingLog.append("[Round ").append(line.round()).append("] ")
                    .append(line.actor()).append(" ").append(line.action()).append(" ")
                    .append(price).append(" - ")
                    .append(line.reasoning() == null ? "" : line.reasoning()).append('\n');

            if (line.price() > 0) {
                if (buyerTurn) {
                    lastBuyerOffer = line.price();
                } else {
                    lastDealerOffer = line.price();
                }
            }
        }
        if ("DEAL".equals(result.outcome)) {
            thinkingLog.append("[DEAL] Final price: RM ").append(money(result.finalPrice)).append('\n');
        } else {
            thinkingLog.append("[END] Outcome: ").append(result.outcome).append('\n');
        }

        String statusClass = "DEAL".equals(result.outcome) ? "done" : "warn";
        String finalPrice = result.finalPrice > 0 ? "RM " + money(result.finalPrice) : "-";
        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT));
        PriceSeries priceSeries = buildPriceSeries(result);
        String bannerStyle = "DEAL".equals(result.outcome) ? "deal" : "no-deal";

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Experiment __CASE__ Report</title>
<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js"></script>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
:root {
  --bg: #0f1117;
  --panel: #1a1d26;
  --panel-2: #252830;
  --border: #2a2d3a;
  --border-soft: #1e2130;
  --text: #e0e0e0;
  --muted: #888;
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
  font-family: "Segoe UI", sans-serif;
  font-size: 14px;
  line-height: 1.6;
  padding: 24px 32px;
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
.topbar {
  align-items: flex-start;
  display: flex;
  gap: 16px;
  justify-content: space-between;
  margin-bottom: 20px;
}
.subtitle { color: var(--muted); }
.mono { font-family: Consolas, "Courier New", monospace; font-size: 12px; }
.banner {
  border-radius: 10px;
  padding: 14px 20px;
  margin-bottom: 20px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.banner.deal { background: #052e16; border: 1px solid #16a34a; }
.banner.no-deal { background: #2a0f0f; border: 1px solid #dc2626; }
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
  border-radius: 10px;
  padding: 16px 20px;
  margin-bottom: 16px;
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
  overflow-wrap: anywhere;
}
.hint { color: #94a3b8; font-size: 12px; margin-top: 4px; }
.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(280px, .8fr);
  gap: 16px;
}
.split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, .8fr);
  gap: 16px;
}
.summary-list { display: grid; gap: 10px; }
.summary-row {
  background: #101114;
  border: 1px solid var(--border);
  border-radius: 8px;
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 10px;
  padding: 10px 14px;
}
.summary-row .k { color: var(--muted); font-size: 12px; }
.summary-row .v { color: #fff; font-weight: 600; }
.table-wrap {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow-x: auto;
  background: var(--panel);
}
table { width: 100%; min-width: 920px; border-collapse: collapse; }
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
.neutral { background: #1f2937; border-color: #64748b; color: #cbd5e1; }
.log {
  background: #0a0c10;
  border: 1px solid var(--border-soft);
  border-radius: 6px;
  color: #94a3b8;
  font-family: Consolas, "Courier New", monospace;
  font-size: 11px;
  line-height: 1.8;
  max-height: 400px;
  overflow-y: auto;
  padding: 14px;
  white-space: pre-wrap;
}
.footer { color: #64748b; font-size: 11px; margin-top: 24px; }
@media (max-width: 760px) {
  body { padding: 18px; }
  .topbar { display: block; }
  .chart-grid { grid-template-columns: 1fr; }
  .split { grid-template-columns: 1fr; }
  .summary-row { grid-template-columns: 1fr; gap: 2px; }
}
</style>
</head>
<body>
<div class="topbar">
  <div>
    <h1>Experiment __CASE__ Report</h1>
    <p class="subtitle"><span class="mono">__SCENARIO__</span> | <span class="mono">__BUYER__</span> vs <span class="mono">__DEALER__</span></p>
    <p class="subtitle">Generated at __GENERATED_AT__</p>
  </div>
  <div><a href="../../negotiation-test-report.html">Back to summary</a></div>
</div>

<div class="banner __BANNER_STYLE__">
  <strong><span class="pill __STATUS_CLASS__">__OUTCOME__</span> Final price: __FINAL_PRICE__</strong>
  <span>Accepted by: __ACCEPTED_BY__ | Rounds: __ROUNDS__ | Benefit side: __BENEFIT_SIDE__</span>
</div>

<div class="grid">
  __CARDS__
</div>

<h2>Price Negotiation Chart</h2>
<div class="chart-grid">
  <div class="card"><canvas id="priceChart"></canvas></div>
  <div class="card"><canvas id="utilityChart"></canvas></div>
</div>

<h2>Surplus And Concession View</h2>
<div class="chart-grid">
  <div class="card"><canvas id="surplusChart"></canvas></div>
  <div class="card"><canvas id="concessionChart"></canvas></div>
</div>

<div class="split">
  <div class="card">
    <h2 style="margin-top:0">Experiment Details</h2>
    <div class="summary-list">
      <div class="summary-row"><div class="k">Repeat</div><div class="v">__REPEAT__</div></div>
      <div class="summary-row"><div class="k">Scenario seed</div><div class="v">__SCENARIO_SEED__</div></div>
      <div class="summary-row"><div class="k">Case seed</div><div class="v">__CASE_SEED__</div></div>
      <div class="summary-row"><div class="k">Final reason</div><div class="v">__FINAL_REASON__</div></div>
    </div>
  </div>
  <div class="card">
    <h2 style="margin-top:0">Source Files</h2>
    <div class="summary-list">
      <div class="summary-row"><div class="k">Transcript</div><div class="v"><a href="transcript.txt">transcript.txt</a></div></div>
      <div class="summary-row"><div class="k">CSV</div><div class="v"><a href="result.csv">result.csv</a></div></div>
      <div class="summary-row"><div class="k">JSON</div><div class="v"><a href="result.json">result.json</a></div></div>
    </div>
  </div>
</div>

<h2>Transcript</h2>
<div class="table-wrap">
  <table>
    <thead>
      <tr>
        <th>Round</th>
        <th>Party</th>
        <th>Action</th>
        <th>Price</th>
        <th>Gap To Previous Offer</th>
        <th>Reasoning</th>
      </tr>
    </thead>
    <tbody>
__TRANSCRIPT_ROWS__
    </tbody>
  </table>
</div>

<h2>Full Thinking Log</h2>
<div class="log">__THINKING_LOG__</div>

<p class="footer">Generated from the offline strategy experiment runner.</p>
<script>
const moneyTicks = value => 'RM ' + Number(value).toLocaleString();
new Chart(document.getElementById('priceChart').getContext('2d'), {
  type: 'line',
  data: {
    labels: [__PRICE_LABELS__],
    datasets: [
      { label: 'Dealer Offer (RM)', data: [__DEALER_DATA__], borderColor: '#f97316', backgroundColor: 'rgba(249,115,22,0.08)', borderWidth: 2.5, pointRadius: 5, tension: 0.3, spanGaps: true, fill: true },
      { label: 'Buyer Offer (RM)', data: [__BUYER_DATA__], borderColor: '#60a5fa', backgroundColor: 'rgba(96,165,250,0.08)', borderWidth: 2.5, pointRadius: 5, tension: 0.3, spanGaps: true, fill: true },
      { label: 'Dealer Floor', data: [__DEALER_FLOOR_DATA__], borderColor: '#fbbf24', borderDash: [6, 6], borderWidth: 1.5, pointRadius: 0 },
      { label: 'Buyer Max', data: [__BUYER_MAX_DATA__], borderColor: '#a5b4fc', borderDash: [4, 5], borderWidth: 1.5, pointRadius: 0 }
    ]
  },
  options: {
    responsive: true,
    plugins: {
      legend: { labels: { color: '#e0e0e0' } },
      tooltip: { callbacks: { label: c => c.dataset.label + ': ' + moneyTicks(c.parsed.y) } }
    },
    scales: {
      x: { ticks: { color: '#888' }, grid: { color: '#1e2130' } },
      y: { ticks: { color: '#888', callback: moneyTicks }, grid: { color: '#1e2130' } }
    }
  }
});
new Chart(document.getElementById('utilityChart').getContext('2d'), {
  type: 'bar',
  data: {
    labels: ['Buyer Utility', 'Dealer Utility', 'Joint Utility'],
    datasets: [{ label: 'Utility', data: [__BUYER_UTILITY__, __DEALER_UTILITY__, __JOINT_UTILITY__], backgroundColor: ['#60a5fa', '#fb923c', '#a78bfa'], borderWidth: 0 }]
  },
  options: {
    responsive: true,
    plugins: { legend: { display: false } },
    scales: {
      x: { ticks: { color: '#888' }, grid: { color: '#1e2130' } },
      y: { min: 0, max: 2, ticks: { color: '#888' }, grid: { color: '#1e2130' } }
    }
  }
});
new Chart(document.getElementById('surplusChart').getContext('2d'), {
  type: 'bar',
  data: {
    labels: ['Agreement Surplus', 'Buyer Surplus', 'Dealer Profit', 'Saving From Ask'],
    datasets: [{ label: 'RM', data: [__AGREEMENT_SURPLUS__, __BUYER_SURPLUS__, __DEALER_PROFIT__, __SAVING_FROM_ASK__], backgroundColor: ['#a78bfa', '#60a5fa', '#fb923c', '#67e8f9'], borderWidth: 0 }]
  },
  options: {
    responsive: true,
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: { label: c => moneyTicks(c.parsed.y) } }
    },
    scales: {
      x: { ticks: { color: '#888' }, grid: { color: '#1e2130' } },
      y: { ticks: { color: '#888', callback: moneyTicks }, grid: { color: '#1e2130' } }
    }
  }
});
new Chart(document.getElementById('concessionChart').getContext('2d'), {
  type: 'bar',
  data: {
    labels: ['Buyer Concession %', 'Dealer Concession %'],
    datasets: [{ label: 'Concession %', data: [__BUYER_CONCESSION__, __DEALER_CONCESSION__], backgroundColor: ['#60a5fa', '#fb923c'], borderWidth: 0 }]
  },
  options: {
    indexAxis: 'y',
    responsive: true,
    plugins: { legend: { display: false } },
    scales: {
      x: { min: 0, max: 100, ticks: { color: '#888', callback: v => v + '%' }, grid: { color: '#1e2130' } },
      y: { ticks: { color: '#888' }, grid: { color: '#1e2130' } }
    }
  }
});
</script>
</body>
</html>
"""
                .replace("__CASE__", String.valueOf(result.caseNumber))
                .replace("__SCENARIO__", escapeHtml(result.scenarioName))
                .replace("__BUYER__", escapeHtml(result.buyerStrategyKey))
                .replace("__DEALER__", escapeHtml(result.dealerStrategyKey))
                .replace("__GENERATED_AT__", escapeHtml(generatedAt))
                .replace("__BANNER_STYLE__", bannerStyle)
                .replace("__STATUS_CLASS__", statusClass)
                .replace("__OUTCOME__", escapeHtml(result.outcome))
                .replace("__FINAL_PRICE__", escapeHtml(finalPrice))
                .replace("__ACCEPTED_BY__", escapeHtml(result.acceptedBy))
                .replace("__ROUNDS__", String.valueOf(result.roundsTaken))
                .replace("__BENEFIT_SIDE__", escapeHtml(result.benefitSide))
                .replace("__CARDS__", buildExperimentCards(result))
                .replace("__REPEAT__", String.valueOf(result.repeat))
                .replace("__SCENARIO_SEED__", String.valueOf(result.scenarioSeed))
                .replace("__CASE_SEED__", String.valueOf(result.caseSeed))
                .replace("__FINAL_REASON__", escapeHtml(result.finalReason))
                .replace("__TRANSCRIPT_ROWS__", transcriptRows.toString())
                .replace("__THINKING_LOG__", escapeHtml(thinkingLog.toString()))
                .replace("__PRICE_LABELS__", priceSeries.labels())
                .replace("__BUYER_DATA__", priceSeries.buyerData())
                .replace("__DEALER_DATA__", priceSeries.dealerData())
                .replace("__BUYER_MAX_DATA__", repeatChartValue(result.buyerReservationPrice, priceSeries.pointCount()))
                .replace("__DEALER_FLOOR_DATA__", repeatChartValue(result.dealerReservationPrice, priceSeries.pointCount()))
                .replace("__BUYER_UTILITY__", chartNumber(result.buyerUtility))
                .replace("__DEALER_UTILITY__", chartNumber(result.dealerUtility))
                .replace("__JOINT_UTILITY__", chartNumber(result.jointUtility))
                .replace("__AGREEMENT_SURPLUS__", chartNumber(result.agreementSurplus))
                .replace("__BUYER_SURPLUS__", chartNumber(result.buyerSurplus))
                .replace("__DEALER_PROFIT__", chartNumber(result.dealerProfit))
                .replace("__SAVING_FROM_ASK__", chartNumber(result.savingFromAsk))
                .replace("__BUYER_CONCESSION__", chartNumber(result.buyerConcessionPct))
                .replace("__DEALER_CONCESSION__", chartNumber(result.dealerConcessionPct));
    }

    private static String buildExperimentCards(ExperimentResult result) {
        return metricCard("Asking Price", formatMoneyOrDash(result.askingPrice), "Dealer opening price", "color: var(--green)")
                + metricCard("Buyer Max", formatMoneyOrDash(result.buyerReservationPrice), "Buyer reservation price", "color: var(--blue)")
                + metricCard("Dealer Floor", formatMoneyOrDash(result.dealerReservationPrice), "Dealer reservation price", "color: var(--orange)")
                + metricCard("Buyer Saving", formatMoneyOrDash(result.savingFromAsk), formatPct(result.savingPct) + "% from asking", "color: var(--cyan)")
                + metricCard("Vs Baseline", formatMoneyOrDash(result.negotiationGainVsBaseline), result.baselineOutcome, "color: var(--cyan)")
                + metricCard("Buyer Utility", formatPct(result.buyerUtility), "0.00 to 1.00", "color: var(--blue)")
                + metricCard("Dealer Utility", formatPct(result.dealerUtility), "0.00 to 1.00", "color: var(--orange)")
                + metricCard("Joint Utility", formatPct(result.jointUtility), "Buyer + dealer utility", "color: var(--violet)")
                + metricCard("Dealer Profit", formatMoneyOrDash(result.dealerProfit), "Above dealer floor", "color: var(--green)");
    }

    private static String metricCard(String label, String value, String hint, String style) {
        return "  <div class=\"card\"><div class=\"label\">" + escapeHtml(label)
                + "</div><div class=\"value\" style=\"" + escapeHtml(style) + "\">" + escapeHtml(value)
                + "</div><div class=\"hint\">" + escapeHtml(hint) + "</div></div>\n";
    }

    private static PriceSeries buildPriceSeries(ExperimentResult result) {
        List<String> labels = new ArrayList<>();
        List<String> buyerData = new ArrayList<>();
        List<String> dealerData = new ArrayList<>();

        labels.add(jsString("Start"));
        buyerData.add(chartMoneyValue(result.buyerFirstOffer));
        dealerData.add(chartMoneyValue(result.askingPrice));

        for (TranscriptLine line : result.lines) {
            if (line.price() <= 0) {
                continue;
            }
            boolean buyerTurn = "BUYER".equals(line.actor());
            boolean accepted = "ACCEPT".equals(line.action());
            labels.add(jsString(accepted ? "Deal" : "R" + line.round() + " " + titleCase(line.actor())));
            if (accepted) {
                buyerData.add(chartMoneyValue(line.price()));
                dealerData.add(chartMoneyValue(line.price()));
            } else if (buyerTurn) {
                buyerData.add(chartMoneyValue(line.price()));
                dealerData.add("null");
            } else {
                buyerData.add("null");
                dealerData.add(chartMoneyValue(line.price()));
            }
        }

        return new PriceSeries(
                String.join(", ", labels),
                String.join(", ", buyerData),
                String.join(", ", dealerData),
                labels.size());
    }

    private static String repeatChartValue(double value, int count) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(chartMoneyValue(value));
        }
        return String.join(", ", values);
    }

    private static String chartMoneyValue(double value) {
        return value > 0 ? money(value) : "null";
    }

    private static String chartNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String jsString(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
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
        double avgBuyerUtility = results.stream()
                .filter(r -> "DEAL".equals(r.outcome))
                .mapToDouble(r -> r.buyerUtility)
                .average()
                .orElse(0);
        double avgDealerUtility = results.stream()
                .filter(r -> "DEAL".equals(r.outcome))
                .mapToDouble(r -> r.dealerUtility)
                .average()
                .orElse(0);
        long baselineDeals = results.stream().filter(ExperimentResult::baselineDeal).count();
        double baselineDealRate = results.isEmpty() ? 0 : baselineDeals * 100.0 / results.size();
        double avgNegotiationGainVsBaseline = results.stream()
                .filter(ExperimentResult::comparableBaselineDeal)
                .mapToDouble(r -> r.negotiationGainVsBaseline)
                .average()
                .orElse(0);

        StringBuilder out = new StringBuilder();
        out.append("STRATEGY EXPERIMENT SESSION\n");
        out.append("===========================\n");
        out.append("Experiments: ").append(results.size()).append('\n');
        out.append("Deals: ").append(deals)
                .append(" (").append(formatPct(dealRate)).append("%)\n");
        out.append("Baseline buy-at-asking deals: ").append(baselineDeals)
                .append(" (").append(formatPct(baselineDealRate)).append("%)\n");
        out.append("Average negotiation gain vs baseline: ")
                .append(avgNegotiationGainVsBaseline != 0 ? "RM " + money(avgNegotiationGainVsBaseline) : "-")
                .append('\n');
        out.append("Average rounds: ").append(String.format(Locale.ROOT, "%.2f", avgRounds)).append('\n');
        out.append("Average final price: ").append(avgFinal > 0 ? "RM " + money(avgFinal) : "-").append('\n');
        out.append("Average buyer utility: ").append(formatPct(avgBuyerUtility)).append('\n');
        out.append("Average dealer utility: ").append(formatPct(avgDealerUtility)).append("\n\n");

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

        out.append("\nSTRATEGY PAIR SUMMARY\n");
        Map<String, StrategyPairStats> stats = new LinkedHashMap<>();
        for (ExperimentResult result : results) {
            String key = result.buyerStrategyKey + "\t" + result.dealerStrategyKey;
            stats.computeIfAbsent(key, ignored -> new StrategyPairStats(result.buyerStrategyKey, result.dealerStrategyKey))
                    .add(result);
        }
        stats.values().stream()
                .sorted(Comparator
                        .comparingDouble(StrategyPairStats::dealRate).reversed()
                        .thenComparingDouble(StrategyPairStats::averageFinalPrice)
                        .thenComparing(s -> s.buyerStrategy))
                .limit(20)
                .forEach(s -> out.append(s.shortLine()).append('\n'));
        return out.toString();
    }

    private static String shortResult(ExperimentResult r) {
        return String.format(Locale.ROOT,
                "#%03d %-10s buyer=%-24s dealer=%-24s outcome=%-7s rounds=%2d final=%s utility=%s/%s",
                r.caseNumber,
                r.scenarioName,
                r.buyerStrategyKey,
                r.dealerStrategyKey,
                r.outcome,
                r.roundsTaken,
                r.finalPrice > 0 ? "RM " + money(r.finalPrice) : "-",
                formatPct(r.buyerUtility),
                formatPct(r.dealerUtility));
    }

    private static String csvHeader() {
        return "case,repeat,scenario,buyer_strategy,buyer_strategy_name,dealer_strategy,"
                + "dealer_strategy_name,outcome,accepted_by,rounds,messages,asking_price,"
                + "buyer_first_offer,buyer_reservation_price,dealer_reservation_price,"
                + "final_price,saving_from_ask,saving_pct,baseline_outcome,baseline_price,"
                + "baseline_saving_from_ask,negotiation_gain_vs_baseline,folder,scenario_seed,case_seed,"
                + "buyer_utility,dealer_utility,joint_utility,agreement_surplus,buyer_surplus,"
                + "dealer_profit,buyer_distance_from_reservation,dealer_distance_from_reservation,"
                + "buyer_concession_pct,dealer_concession_pct,benefit_side,final_reason\n";
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static String formatMoneyOrDash(double value) {
        return Math.abs(value) < 0.0001 ? "-" : "RM " + money(value);
    }

    private static String formatPct(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\r", " ")
                .replace("\n", " ")
                .replace("\"", "\"\"") + "\"";
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

    private static String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return clamp(value, 0, 1);
    }

    private static String jsonString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsString();
            }
        }
        return "scenario";
    }

    private static double jsonDouble(JsonObject object, double fallback, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsDouble();
            }
        }
        return fallback;
    }

    private static double jsonPercent(JsonObject object, double fallback, String... keys) {
        double value = jsonDouble(object, fallback, keys);
        return value > 1.5 ? value / 100.0 : value;
    }

    private static int jsonInt(JsonObject object, int fallback, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return Math.max(1, object.get(key).getAsInt());
            }
        }
        return fallback;
    }

    private record ScenarioTemplate(String name, double askingPrice, double buyerFirstPct,
                                    double buyerReservationPct, double dealerFloorPct,
                                    int maxRounds, String source) {
        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("askingPrice", askingPrice);
            row.put("buyerFirstPct", buyerFirstPct);
            row.put("buyerReservationPct", buyerReservationPct);
            row.put("dealerFloorPct", dealerFloorPct);
            row.put("maxRounds", maxRounds);
            row.put("source", source);
            return row;
        }
    }

    private record Scenario(String name, double askingPrice, double buyerFirstPct,
                            double buyerReservationPct, double dealerFloorPct,
                            int maxRounds, long seed, boolean varied) {
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
                                  String buyerStrategyKey, String dealerStrategyKey, long caseSeed) {
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

    private record PriceSeries(String labels, String buyerData, String dealerData, int pointCount) {
    }

    private static final class ExperimentResult {
        final int caseNumber;
        final int repeat;
        final String scenarioName;
        final long scenarioSeed;
        final long caseSeed;
        final boolean scenarioVaried;
        final String buyerStrategyKey;
        final String dealerStrategyKey;
        final String folderName;
        final List<TranscriptLine> lines = new ArrayList<>();

        String buyerStrategyName;
        String dealerStrategyName;
        String outcome = "NO_DEAL";
        String acceptedBy = "NONE";
        String finalReason = "";
        String benefitSide = "NO_DEAL";
        int roundsTaken;
        int messageCount;
        double askingPrice;
        double buyerFirstOffer;
        double buyerReservationPrice;
        double dealerReservationPrice;
        double finalPrice;
        double savingFromAsk;
        double savingPct;
        String baselineOutcome = "NO_DEAL";
        double baselinePrice;
        double baselineSavingFromAsk;
        double negotiationGainVsBaseline;
        double lastBuyerOffer;
        double lastDealerOffer;
        double buyerUtility;
        double dealerUtility;
        double jointUtility;
        double agreementSurplus;
        double buyerSurplus;
        double dealerProfit;
        double buyerDistanceFromReservation;
        double dealerDistanceFromReservation;
        double buyerConcessionPct;
        double dealerConcessionPct;

        ExperimentResult(ExperimentCase c) {
            this.caseNumber = c.caseNumber();
            this.repeat = c.repeat();
            this.scenarioName = c.scenario().name();
            this.scenarioSeed = c.scenario().seed();
            this.caseSeed = c.caseSeed();
            this.scenarioVaried = c.scenario().varied();
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

        boolean baselineDeal() {
            return "BUY_AT_ASKING".equals(baselineOutcome);
        }

        boolean comparableBaselineDeal() {
            return baselinePrice > 0 && finalPrice > 0;
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
                    + csv(baselineOutcome) + ","
                    + money(baselinePrice) + ","
                    + money(baselineSavingFromAsk) + ","
                    + money(negotiationGainVsBaseline) + ","
                    + csv(folderName) + ","
                    + scenarioSeed + ","
                    + caseSeed + ","
                    + formatPct(buyerUtility) + ","
                    + formatPct(dealerUtility) + ","
                    + formatPct(jointUtility) + ","
                    + money(agreementSurplus) + ","
                    + money(buyerSurplus) + ","
                    + money(dealerProfit) + ","
                    + money(buyerDistanceFromReservation) + ","
                    + money(dealerDistanceFromReservation) + ","
                    + formatPct(buyerConcessionPct) + ","
                    + formatPct(dealerConcessionPct) + ","
                    + csv(benefitSide) + ","
                    + csv(finalReason);
        }

        String toJson() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("case", caseNumber);
            row.put("repeat", repeat);
            row.put("scenario", scenarioName);
            row.put("scenarioSeed", scenarioSeed);
            row.put("caseSeed", caseSeed);
            row.put("scenarioVaried", scenarioVaried);
            row.put("buyerStrategy", buyerStrategyKey);
            row.put("dealerStrategy", dealerStrategyKey);
            row.put("outcome", outcome);
            row.put("acceptedBy", acceptedBy);
            row.put("roundsTaken", roundsTaken);
            row.put("messageCount", messageCount);
            row.put("askingPrice", askingPrice);
            row.put("buyerFirstOffer", buyerFirstOffer);
            row.put("buyerReservationPrice", buyerReservationPrice);
            row.put("dealerReservationPrice", dealerReservationPrice);
            row.put("finalPrice", finalPrice);
            row.put("savingFromAsk", savingFromAsk);
            row.put("savingPct", savingPct);
            row.put("baselineOutcome", baselineOutcome);
            row.put("baselinePrice", baselinePrice);
            row.put("baselineSavingFromAsk", baselineSavingFromAsk);
            row.put("negotiationGainVsBaseline", negotiationGainVsBaseline);
            row.put("lastBuyerOffer", lastBuyerOffer);
            row.put("lastDealerOffer", lastDealerOffer);
            row.put("buyerUtility", buyerUtility);
            row.put("dealerUtility", dealerUtility);
            row.put("jointUtility", jointUtility);
            row.put("agreementSurplus", agreementSurplus);
            row.put("buyerSurplus", buyerSurplus);
            row.put("dealerProfit", dealerProfit);
            row.put("buyerDistanceFromReservation", buyerDistanceFromReservation);
            row.put("dealerDistanceFromReservation", dealerDistanceFromReservation);
            row.put("buyerConcessionPct", buyerConcessionPct);
            row.put("dealerConcessionPct", dealerConcessionPct);
            row.put("benefitSide", benefitSide);
            row.put("finalReason", finalReason);
            row.put("transcript", lines);
            return GSON.toJson(row) + "\n";
        }
    }

    private static final class StrategyPairStats {
        final String buyerStrategy;
        final String dealerStrategy;
        final List<ExperimentResult> results = new ArrayList<>();

        StrategyPairStats(String buyerStrategy, String dealerStrategy) {
            this.buyerStrategy = buyerStrategy;
            this.dealerStrategy = dealerStrategy;
        }

        void add(ExperimentResult result) {
            results.add(result);
        }

        long deals() {
            return results.stream().filter(r -> "DEAL".equals(r.outcome)).count();
        }

        double dealRate() {
            return results.isEmpty() ? 0 : deals() * 100.0 / results.size();
        }

        double averageRounds() {
            return results.stream().mapToInt(r -> r.roundsTaken).average().orElse(0);
        }

        double averageFinalPrice() {
            return dealResults().stream().mapToDouble(r -> r.finalPrice).average().orElse(0);
        }

        double finalPriceStdDev() {
            List<ExperimentResult> deals = dealResults();
            if (deals.size() <= 1) {
                return 0;
            }
            double average = averageFinalPrice();
            double variance = deals.stream()
                    .mapToDouble(r -> Math.pow(r.finalPrice - average, 2))
                    .sum() / (deals.size() - 1);
            return Math.sqrt(variance);
        }

        double bestFinalPrice() {
            return dealResults().stream().mapToDouble(r -> r.finalPrice).min().orElse(0);
        }

        double worstFinalPrice() {
            return dealResults().stream().mapToDouble(r -> r.finalPrice).max().orElse(0);
        }

        double averageBuyerUtility() {
            return dealResults().stream().mapToDouble(r -> r.buyerUtility).average().orElse(0);
        }

        double averageDealerUtility() {
            return dealResults().stream().mapToDouble(r -> r.dealerUtility).average().orElse(0);
        }

        double averageJointUtility() {
            return dealResults().stream().mapToDouble(r -> r.jointUtility).average().orElse(0);
        }

        double averageBuyerConcessionPct() {
            return results.stream().mapToDouble(r -> r.buyerConcessionPct).average().orElse(0);
        }

        double averageDealerConcessionPct() {
            return results.stream().mapToDouble(r -> r.dealerConcessionPct).average().orElse(0);
        }

        long baselineDeals() {
            return results.stream().filter(ExperimentResult::baselineDeal).count();
        }

        double baselineDealRate() {
            return results.isEmpty() ? 0 : baselineDeals() * 100.0 / results.size();
        }

        double dealRateDeltaVsBaseline() {
            return dealRate() - baselineDealRate();
        }

        double averageBaselinePrice() {
            return results.stream()
                    .filter(ExperimentResult::baselineDeal)
                    .mapToDouble(r -> r.baselinePrice)
                    .average()
                    .orElse(0);
        }

        double averageNegotiationGainVsBaseline() {
            return comparableBaselineDeals().stream()
                    .mapToDouble(r -> r.negotiationGainVsBaseline)
                    .average()
                    .orElse(0);
        }

        String toCsvRow() {
            return csv(buyerStrategy) + ","
                    + csv(dealerStrategy) + ","
                    + results.size() + ","
                    + deals() + ","
                    + formatPct(dealRate()) + ","
                    + formatPct(averageRounds()) + ","
                    + money(averageFinalPrice()) + ","
                    + money(finalPriceStdDev()) + ","
                    + money(bestFinalPrice()) + ","
                    + money(worstFinalPrice()) + ","
                    + formatPct(averageBuyerUtility()) + ","
                    + formatPct(averageDealerUtility()) + ","
                    + formatPct(averageJointUtility()) + ","
                    + formatPct(averageBuyerConcessionPct()) + ","
                    + formatPct(averageDealerConcessionPct()) + ","
                    + baselineDeals() + ","
                    + formatPct(baselineDealRate()) + ","
                    + formatPct(dealRateDeltaVsBaseline()) + ","
                    + money(averageBaselinePrice()) + ","
                    + money(averageNegotiationGainVsBaseline());
        }

        String shortLine() {
            return String.format(Locale.ROOT,
                    "buyer=%-24s dealer=%-24s cases=%2d deals=%2d rate=%6.2f%% baseline=%6.2f%% avg_final=%s gain=%s avg_rounds=%5.2f utility=%s/%s",
                    buyerStrategy,
                    dealerStrategy,
                    results.size(),
                    deals(),
                    dealRate(),
                    baselineDealRate(),
                    averageFinalPrice() > 0 ? "RM " + money(averageFinalPrice()) : "-",
                    averageNegotiationGainVsBaseline() != 0 ? "RM " + money(averageNegotiationGainVsBaseline()) : "-",
                    averageRounds(),
                    formatPct(averageBuyerUtility()),
                    formatPct(averageDealerUtility()));
        }

        private List<ExperimentResult> dealResults() {
            return results.stream().filter(r -> "DEAL".equals(r.outcome)).toList();
        }

        private List<ExperimentResult> comparableBaselineDeals() {
            return results.stream().filter(ExperimentResult::comparableBaselineDeal).toList();
        }
    }

    private static final class ExperimentOptions {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        int maxRounds = 10;
        boolean roundsOverridden;
        int repeats = 1;
        long seed = 42;
        boolean repeatVariation = true;
        double variationPct = 0.03;
        Path outputRoot = Path.of("experiment_results");
        Path scenariosPath;
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
                options.roundsOverridden = true;
            }
            if (values.containsKey("repeats")) {
                options.repeats = Math.max(1, Integer.parseInt(values.get("repeats")));
            }
            if (values.containsKey("seed")) {
                options.seed = Long.parseLong(values.get("seed"));
            }
            if (values.containsKey("variation")) {
                options.variationPct = parsePercentLike(values.get("variation"));
            }
            if (values.containsKey("no-variation")) {
                options.repeatVariation = false;
            }
            if (values.containsKey("out")) {
                options.outputRoot = Path.of(values.get("out"));
            }
            if (values.containsKey("scenarios")) {
                options.scenariosPath = Path.of(values.get("scenarios"));
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

        private static double parsePercentLike(String value) {
            double parsed = Double.parseDouble(value);
            return parsed > 1.0 ? parsed / 100.0 : parsed;
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
