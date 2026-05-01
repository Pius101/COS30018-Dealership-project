package negotiation.strategy;

import java.util.*;
import java.util.function.Supplier;

/**
 * Central registry for all negotiation strategies.
 *
 * Built-in strategies are registered in the static block.
 * To add a custom strategy, call register() anywhere before the GUI opens:
 *
 *   StrategyRegistry.register("MY_KEY", MyStrategy::new);
 *
 * Or with parameters:
 *
 *   StrategyRegistry.register("FIXED_3PCT",
 *       () -> new FixedIncrementStrategy(3.0));
 *
 * The GUI dropdown is populated from this registry automatically.
 */
public final class StrategyRegistry {

    // Ordered map so strategies appear in GUI in registration order
    private static final Map<String, Supplier<NegotiationStrategy>> REGISTRY =
            new LinkedHashMap<>();

    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();

    static {
        // ── Built-in strategies (from lecture slides) ────────────────────────

        register("BAYESIAN",
                BayesianLearnerStrategy::new,
                "Bayesian Learner");

        register("TIME_DEPENDENT_CONCEDER",
                () -> new TimeDependentStrategy(TimeDependentStrategy.Curve.CONCEDER),
                "Time-Dependent (Conceder)");

        register("TIME_DEPENDENT_BOULWARE",
                () -> new TimeDependentStrategy(TimeDependentStrategy.Curve.BOULWARE),
                "Time-Dependent (Boulware)");

        register("TIT_FOR_TAT",
                TitForTatStrategy::new,
                "Tit-for-Tat");

        // ── Example custom strategies (template for your groupmates) ─────────

        register("FIXED_INCREMENT_2PCT",
                () -> new FixedIncrementStrategy(2.0),
                "Fixed Increment (2% per round)");

        register("FIXED_INCREMENT_5PCT",
                () -> new FixedIncrementStrategy(5.0),
                "Fixed Increment (5% per round)");

        // ── To add YOUR strategy: uncomment and modify ───────────────────────
        // register("MY_STRATEGY", MyStrategy::new, "My Custom Strategy");
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Register a new strategy.
     *
     * @param key         machine-readable key used in config files ("MY_STRATEGY")
     * @param factory     supplier that creates a new instance each time
     * @param displayName shown in the GUI dropdown
     */
    public static void register(String key,
                                Supplier<NegotiationStrategy> factory,
                                String displayName) {
        REGISTRY.put(key, factory);
        DISPLAY_NAMES.put(key, displayName);
    }

    /**
     * Shorthand register that uses the strategy's own getDisplayName().
     */
    public static void register(String key, Supplier<NegotiationStrategy> factory) {
        NegotiationStrategy sample = factory.get();
        register(key, factory, sample.getDisplayName());
    }

    /**
     * Create a fresh strategy instance by key.
     *
     * @throws IllegalArgumentException if the key is not registered
     */
    public static NegotiationStrategy create(String key) {
        Supplier<NegotiationStrategy> factory = REGISTRY.get(key);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "Unknown strategy: '" + key + "'. Registered: " + REGISTRY.keySet());
        }
        return factory.get();
    }

    /**
     * All registered strategy keys, in registration order.
     * Used to populate the GUI dropdown.
     */
    public static List<String> getKeys() {
        return new ArrayList<>(REGISTRY.keySet());
    }

    /**
     * Display name for a given key.
     * Falls back to the key itself if no display name was registered.
     */
    public static String getDisplayName(String key) {
        return DISPLAY_NAMES.getOrDefault(key, key);
    }

    /**
     * All display names, in registration order.
     * Parallel to getKeys() — use together to build a JComboBox.
     */
    public static String[] getDisplayNames() {
        return DISPLAY_NAMES.values().toArray(new String[0]);
    }

    private StrategyRegistry() {}
}