package negotiation.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Dealer-to-broker payload listing buyers the dealer wants to negotiate with.
 */
public class DealerSelection {

    private String dealerAID;
    private String dealerName;
    private List<Entry> entries = new ArrayList<>();
    private long timestamp;

    public DealerSelection() {
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
        private String listingId;
        private String dealerNote;

        public Entry() { }

        public Entry(String requirementId, String listingId, String dealerNote) {
            this.requirementId = requirementId;
            this.listingId = listingId;
            this.dealerNote = dealerNote;
        }

        public String getRequirementId() { return requirementId; }
        public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

        public String getListingId() { return listingId; }
        public void setListingId(String listingId) { this.listingId = listingId; }

        public String getDealerNote() { return dealerNote; }
        public void setDealerNote(String dealerNote) { this.dealerNote = dealerNote; }
    }
}
