package negotiation.models;

import java.util.UUID;

/**
 * A buyer's car search requirements submitted to the Broker.
 *
 * Wildcard convention (means "no constraint"):
 *   String fields  → null or blank
 *   int fields     → 0
 *   double fields  → 0.0
 *   condition      → "Any"
 */
public class CarRequirement {

    private String requirementId; // auto-generated
    private String buyerAID;      // set by KA on receipt
    private String buyerName;     // buyer's local agent name — for display

    // ── Search criteria ───────────────────────────────────────────────────────
    private String make;          // blank = any make
    private String model;         // blank = any model
    private int    yearMin;       // 0 = no lower bound
    private int    yearMax;       // 0 = no upper bound
    private double maxPrice;      // 0 = no price ceiling
    private String condition;     // "New" | "Used" | "Any"
    private int    maxMileage;    // 0 = no mileage limit
    private String notes;         // buyer's free-text notes to broker

    /** No-arg constructor required by Gson. */
    public CarRequirement() {
        this.requirementId = UUID.randomUUID().toString()
                                 .replace("-", "").substring(0, 8).toUpperCase();
        this.condition = "Any";
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getRequirementId()              { return requirementId; }
    public void   setRequirementId(String v)      { this.requirementId = v; }

    public String getBuyerAID()                   { return buyerAID; }
    public void   setBuyerAID(String v)           { this.buyerAID = v; }

    public String getBuyerName()                  { return buyerName; }
    public void   setBuyerName(String v)          { this.buyerName = v; }

    public String getMake()                       { return make; }
    public void   setMake(String v)               { this.make = v; }

    public String getModel()                      { return model; }
    public void   setModel(String v)              { this.model = v; }

    public int    getYearMin()                    { return yearMin; }
    public void   setYearMin(int v)               { this.yearMin = v; }

    public int    getYearMax()                    { return yearMax; }
    public void   setYearMax(int v)               { this.yearMax = v; }

    public double getMaxPrice()                   { return maxPrice; }
    public void   setMaxPrice(double v)           { this.maxPrice = v; }

    public String getCondition()                  { return condition; }
    public void   setCondition(String v)          { this.condition = v; }

    public int    getMaxMileage()                 { return maxMileage; }
    public void   setMaxMileage(int v)            { this.maxMileage = v; }

    public String getNotes()                      { return notes; }
    public void   setNotes(String v)              { this.notes = v; }

    /** Human-readable summary for display in the broker's requirements table. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (make != null && !make.isBlank()) sb.append(make).append(" ");
        if (model != null && !model.isBlank()) sb.append(model).append(" ");
        if (yearMin > 0 || yearMax > 0) {
            sb.append("(")
              .append(yearMin > 0 ? yearMin : "any")
              .append("–")
              .append(yearMax > 0 ? yearMax : "any")
              .append(") ");
        }
        if (!condition.equalsIgnoreCase("Any")) sb.append(condition).append(" ");
        if (maxPrice > 0) sb.append("≤ RM ").append(String.format("%.0f", maxPrice));
        return sb.toString().trim().isEmpty() ? "(any car)" : sb.toString().trim();
    }
}
