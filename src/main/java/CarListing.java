package negotiation.models;

import java.util.UUID;

/**
 * A car listing submitted by a Dealer Agent.
 * Serialized to/from JSON for transport inside ACL message content fields.
 */
public class CarListing {

    private String listingId;     // short auto-generated ID, e.g. "A3F9B2C1"
    private String dealerAID;     // full JADE AID string — set by KA on receipt
    private String dealerName;    // dealer's local agent name — for display
    private String make;          // e.g. "Toyota"
    private String model;         // e.g. "Camry"
    private int    year;          // e.g. 2022
    private int    mileage;       // kilometres (0 = brand new)
    private String color;
    private double retailPrice;   // asking price in MYR
    private String condition;     // "New" or "Used"
    private String description;   // optional free-text notes

    /** No-arg constructor required by Gson for deserialization. */
    public CarListing() {
        this.listingId = UUID.randomUUID().toString()
                             .replace("-", "").substring(0, 8).toUpperCase();
    }

    /** Convenience constructor — used when building a listing from the Dealer GUI. */
    public CarListing(String make, String model, int year, int mileage,
                      String color, double retailPrice, String condition, String description) {
        this();
        this.make        = make;
        this.model       = model;
        this.year        = year;
        this.mileage     = mileage;
        this.color       = color;
        this.retailPrice = retailPrice;
        this.condition   = condition;
        this.description = description;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getListingId()                     { return listingId; }
    public void   setListingId(String v)             { this.listingId = v; }

    public String getDealerAID()                     { return dealerAID; }
    public void   setDealerAID(String v)             { this.dealerAID = v; }

    public String getDealerName()                    { return dealerName; }
    public void   setDealerName(String v)            { this.dealerName = v; }

    public String getMake()                          { return make; }
    public void   setMake(String v)                  { this.make = v; }

    public String getModel()                         { return model; }
    public void   setModel(String v)                 { this.model = v; }

    public int    getYear()                          { return year; }
    public void   setYear(int v)                     { this.year = v; }

    public int    getMileage()                       { return mileage; }
    public void   setMileage(int v)                  { this.mileage = v; }

    public String getColor()                         { return color; }
    public void   setColor(String v)                 { this.color = v; }

    public double getRetailPrice()                   { return retailPrice; }
    public void   setRetailPrice(double v)           { this.retailPrice = v; }

    public String getCondition()                     { return condition; }
    public void   setCondition(String v)             { this.condition = v; }

    public String getDescription()                   { return description; }
    public void   setDescription(String v)           { this.description = v; }

    @Override
    public String toString() {
        return String.format("[%s] %d %s %s  RM %.0f  (%s, %,d km)",
                listingId, year, make, model, retailPrice, condition, mileage);
    }
}
