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

    @Override
    public String toString() {
        return String.format("Assignment{id=%s, dealer=%s, buyer=%s, listing=%s}",
                negotiationId, dealerName, buyerName, listing);
    }
}
