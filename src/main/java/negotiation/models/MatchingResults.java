package negotiation.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Broker → Buyer (INFORM, ontology = MATCH_RESULTS).
 *
 * Phase B of the spec protocol: after a buyer submits requirements, the broker
 * matches them against all registered listings and returns the dealers/cars
 * whose specifications match.
 */
public class MatchingResults {

    private String requirementId;
    private String buyerAID;
    private List<CarListing> matches = new ArrayList<>();

    public String getRequirementId()        { return requirementId; }
    public void   setRequirementId(String v) { this.requirementId = v; }

    public String getBuyerAID()         { return buyerAID; }
    public void   setBuyerAID(String v) { this.buyerAID = v; }

    public List<CarListing> getMatches()              { return matches; }
    public void             setMatches(List<CarListing> v) { this.matches = v; }
}