package negotiation.models;

import java.util.UUID;

/**
 * Represents a manual assignment created by the Broker operator.
 *
 * When the KA operator selects a CarListing and a CarRequirement in the
 * Broker GUI and clicks "Assign", a new Assignment is created and its ID
 * becomes the stable negotiationId for all subsequent messages in that session.
 *
 * Both the Dealer and the Buyer receive this object in their ASSIGNMENT_NOTIFY
 * messages so they each know exactly:
 *   - which car is being negotiated
 *   - who the counterpart is
 *   - what the buyer's initial budget is (from their requirement)
 */
public class Assignment {

    private String negotiationId; // stable session ID for all further messages

    // ── Listing side ──────────────────────────────────────────────────────────
    private String    dealerAID;
    private String    dealerName;
    private CarListing listing;

    // ── Buyer side ────────────────────────────────────────────────────────────
    private String         buyerAID;
    private String         buyerName;
    private CarRequirement requirement;

    // ── Broker's note to both parties ─────────────────────────────────────────
    private String brokerNote;

    // ── Negotiation settings ────────────────────────────────────────────────
    /** Maximum rounds for auto-negotiation. 0 = manual (no limit). */
    private int maxRounds = 0;

    // ── Buyer negotiation params (filled by automated matching protocol) ──────
    /** Buyer's opening bid — set from their BUYER_SHORTLIST entry. 0 = not set. */
    private double buyerFirstOffer;
    /** Buyer's reservation (walk-away) price. 0 = not set. */
    private double buyerReservationPrice;
    /** Strategy key the buyer wants to use. null = use random default. */
    private String buyerStrategyKey;
    /** Whether to start auto-negotiation immediately on assignment. */
    private boolean autoNegotiate;

    // ── Extension 2: multi-attribute weights ─────────────────────────────────
    /** Non-zero when buyer specified attribute weights for multi-attribute negotiation. */
    private double weightPrice;
    private double weightYear;
    private double weightMileage;
    private double weightCondition;

    /** No-arg constructor required by Gson. */
    public Assignment() {
        this.negotiationId = UUID.randomUUID().toString()
                                 .replace("-", "").substring(0, 10).toUpperCase();
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String         getNegotiationId()                       { return negotiationId; }
    public void           setNegotiationId(String v)               { this.negotiationId = v; }

    public String         getDealerAID()                           { return dealerAID; }
    public void           setDealerAID(String v)                   { this.dealerAID = v; }

    public String         getDealerName()                          { return dealerName; }
    public void           setDealerName(String v)                  { this.dealerName = v; }

    public CarListing     getListing()                             { return listing; }
    public void           setListing(CarListing v)                 { this.listing = v; }

    public String         getBuyerAID()                            { return buyerAID; }
    public void           setBuyerAID(String v)                    { this.buyerAID = v; }

    public String         getBuyerName()                           { return buyerName; }
    public void           setBuyerName(String v)                   { this.buyerName = v; }

    public CarRequirement getRequirement()                         { return requirement; }
    public void           setRequirement(CarRequirement v)         { this.requirement = v; }

    public String         getBrokerNote()                          { return brokerNote; }
    public void           setBrokerNote(String v)                  { this.brokerNote = v; }

    public int            getMaxRounds()                           { return maxRounds; }
    public void           setMaxRounds(int v)                       { this.maxRounds = v; }

    public double         getBuyerFirstOffer()                     { return buyerFirstOffer; }
    public void           setBuyerFirstOffer(double v)             { this.buyerFirstOffer = v; }

    public double         getBuyerReservationPrice()               { return buyerReservationPrice; }
    public void           setBuyerReservationPrice(double v)       { this.buyerReservationPrice = v; }

    public String         getBuyerStrategyKey()                    { return buyerStrategyKey; }
    public void           setBuyerStrategyKey(String v)            { this.buyerStrategyKey = v; }

    public boolean        isAutoNegotiate()                        { return autoNegotiate; }
    public void           setAutoNegotiate(boolean v)              { this.autoNegotiate = v; }

    public double         getWeightPrice()                         { return weightPrice; }
    public void           setWeightPrice(double v)                 { this.weightPrice = v; }

    public double         getWeightYear()                          { return weightYear; }
    public void           setWeightYear(double v)                  { this.weightYear = v; }

    public double         getWeightMileage()                       { return weightMileage; }
    public void           setWeightMileage(double v)               { this.weightMileage = v; }

    public double         getWeightCondition()                     { return weightCondition; }
    public void           setWeightCondition(double v)             { this.weightCondition = v; }

    @Override
    public String toString() {
        return String.format("Assignment{id=%s, dealer=%s, buyer=%s, listing=%s}",
                negotiationId, dealerName, buyerName, listing);
    }
}