package negotiation.models;

/**
 * A single message exchanged during a negotiation session.
 *
 * Every offer, counter-offer, acceptance, and rejection is wrapped in this
 * object and serialized to JSON for transport through the Broker Agent (KA).
 *
 * Routing:
 *   Sender  ──msg──▶  KA  ──route to toAID──▶  Recipient
 *
 * KA logs every message so the broker GUI can show the full history.
 */
public class NegotiationMessage {

    /** What kind of message this is. */
    public enum Type {
        OFFER,   // initial offer or counter-offer
        ACCEPT,  // accept the counterpart's last offer
        REJECT   // end the negotiation without a deal
    }

    private String negotiationId;  // links back to Assignment.negotiationId
    private String listingId;
    private String listingDescription; // short human-readable car summary
    private String fromAID;
    private String fromName;
    private String fromRole;       // "DEALER" or "BUYER"
    private String toAID;
    private String toName;
    private double price;          // offered/accepted price (0 for REJECT)
    private String message;        // optional typed text from the user
    private Type   type;
    private long   timestamp;

    /** No-arg constructor for Gson. */
    public NegotiationMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getNegotiationId()               { return negotiationId; }
    public void   setNegotiationId(String v)       { this.negotiationId = v; }

    public String getListingId()                   { return listingId; }
    public void   setListingId(String v)           { this.listingId = v; }

    public String getListingDescription()          { return listingDescription; }
    public void   setListingDescription(String v)  { this.listingDescription = v; }

    public String getFromAID()                     { return fromAID; }
    public void   setFromAID(String v)             { this.fromAID = v; }

    public String getFromName()                    { return fromName; }
    public void   setFromName(String v)            { this.fromName = v; }

    public String getFromRole()                    { return fromRole; }
    public void   setFromRole(String v)            { this.fromRole = v; }

    public String getToAID()                       { return toAID; }
    public void   setToAID(String v)               { this.toAID = v; }

    public String getToName()                      { return toName; }
    public void   setToName(String v)              { this.toName = v; }

    public double getPrice()                       { return price; }
    public void   setPrice(double v)               { this.price = v; }

    public String getMessage()                     { return message; }
    public void   setMessage(String v)             { this.message = v; }

    public Type   getType()                        { return type; }
    public void   setType(Type v)                  { this.type = v; }

    public long   getTimestamp()                   { return timestamp; }
    public void   setTimestamp(long v)             { this.timestamp = v; }
}
