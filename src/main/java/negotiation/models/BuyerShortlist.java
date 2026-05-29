package negotiation.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Buyer-to-broker payload containing the matched cars the buyer wants to pursue.
 */
public class BuyerShortlist {

    private String requirementId;
    private String buyerAID;
    private String buyerName;
    private List<Entry> entries = new ArrayList<>();
    private long timestamp;

    public BuyerShortlist() {
        this.timestamp = System.currentTimeMillis();
    }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public String getBuyerAID() { return buyerAID; }
    public void setBuyerAID(String buyerAID) { this.buyerAID = buyerAID; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public List<Entry> getEntries() { return entries; }
    public void setEntries(List<Entry> entries) { this.entries = entries; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public static class Entry {
        private String listingId;
        private double firstOffer;
        private String note;

        public Entry() { }

        public Entry(String listingId, double firstOffer, String note) {
            this.listingId = listingId;
            this.firstOffer = firstOffer;
            this.note = note;
        }

        public String getListingId() { return listingId; }
        public void setListingId(String listingId) { this.listingId = listingId; }

        public double getFirstOffer() { return firstOffer; }
        public void setFirstOffer(double firstOffer) { this.firstOffer = firstOffer; }

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}
