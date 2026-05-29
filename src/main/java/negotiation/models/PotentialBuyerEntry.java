package negotiation.models;

/**
 * A buyer interested in one specific car of a dealer, with her first offer.
 * Used in both {@link PotentialBuyerList} (Broker → Dealer) and
 * {@link DealerSelection} (Dealer → Broker).
 */
public class PotentialBuyerEntry {

    private String requirementId;
    private String buyerAID;
    private String buyerName;
    private String listingId;
    private double firstOffer;

    public String getRequirementId()         { return requirementId; }
    public void   setRequirementId(String v) { this.requirementId = v; }

    public String getBuyerAID()         { return buyerAID; }
    public void   setBuyerAID(String v) { this.buyerAID = v; }

    public String getBuyerName()         { return buyerName; }
    public void   setBuyerName(String v) { this.buyerName = v; }

    public String getListingId()         { return listingId; }
    public void   setListingId(String v) { this.listingId = v; }

    public double getFirstOffer()         { return firstOffer; }
    public void   setFirstOffer(double v) { this.firstOffer = v; }
}