package negotiation.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Broker-to-buyer payload containing the listings that match one requirement.
 */
public class MatchedListings {

    private String requirementId;
    private String buyerAID;
    private String buyerName;
    private List<CarListing> listings = new ArrayList<>();
    private long timestamp;

    public MatchedListings() {
        this.timestamp = System.currentTimeMillis();
    }

    public MatchedListings(CarRequirement requirement, List<CarListing> listings) {
        this();
        this.requirementId = requirement.getRequirementId();
        this.buyerAID = requirement.getBuyerAID();
        this.buyerName = requirement.getBuyerName();
        this.listings = new ArrayList<>(listings);
    }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public String getBuyerAID() { return buyerAID; }
    public void setBuyerAID(String buyerAID) { this.buyerAID = buyerAID; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public List<CarListing> getListings() { return listings; }
    public void setListings(List<CarListing> listings) { this.listings = listings; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
