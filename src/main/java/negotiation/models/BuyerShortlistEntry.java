package negotiation.models;

/**
 * One car a buyer is willing to negotiate, with her opening offer.
 * Part of {@link BuyerShortlistMessage} (Buyer → Broker).
 */
public class BuyerShortlistEntry {

    private String listingId;
    private String dealerAID;
    private double firstOffer;

    public String getListingId()         { return listingId; }
    public void   setListingId(String v) { this.listingId = v; }

    public String getDealerAID()         { return dealerAID; }
    public void   setDealerAID(String v) { this.dealerAID = v; }

    public double getFirstOffer()         { return firstOffer; }
    public void   setFirstOffer(double v) { this.firstOffer = v; }
}