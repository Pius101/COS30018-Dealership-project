package negotiation.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Broker-to-dealer payload containing buyers who shortlisted the dealer's cars.
 */
public class DealerBuyerOffers {

    private String dealerAID;
    private String dealerName;
    private List<Entry> entries = new ArrayList<>();
    private long timestamp;

    public DealerBuyerOffers() {
        this.timestamp = System.currentTimeMillis();
    }

    public String getDealerAID() { return dealerAID; }
    public void setDealerAID(String dealerAID) { this.dealerAID = dealerAID; }

    public String getDealerName() { return dealerName; }
    public void setDealerName(String dealerName) { this.dealerName = dealerName; }

    public List<Entry> getEntries() { return entries; }
    public void setEntries(List<Entry> entries) { this.entries = entries; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public static class Entry {
        private String requirementId;
        private String buyerAID;
        private String buyerName;
        private CarRequirement requirement;
        private String listingId;
        private CarListing listing;
        private double firstOffer;
        private String buyerNote;

        public Entry() { }

        public String getRequirementId() { return requirementId; }
        public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

        public String getBuyerAID() { return buyerAID; }
        public void setBuyerAID(String buyerAID) { this.buyerAID = buyerAID; }

        public String getBuyerName() { return buyerName; }
        public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

        public CarRequirement getRequirement() { return requirement; }
        public void setRequirement(CarRequirement requirement) { this.requirement = requirement; }

        public String getListingId() { return listingId; }
        public void setListingId(String listingId) { this.listingId = listingId; }

        public CarListing getListing() { return listing; }
        public void setListing(CarListing listing) { this.listing = listing; }

        public double getFirstOffer() { return firstOffer; }
        public void setFirstOffer(double firstOffer) { this.firstOffer = firstOffer; }

        public String getBuyerNote() { return buyerNote; }
        public void setBuyerNote(String buyerNote) { this.buyerNote = buyerNote; }
    }
}
