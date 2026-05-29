package negotiation.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Buyer → Broker (PROPOSE, ontology = BUYER_SHORTLIST).
 *
 * Phase B reply: the cars the buyer is willing to negotiate (per the spec,
 * up to three dealers) together with her first offers.
 */
public class BuyerShortlistMessage {

    private String requirementId;
    private String buyerAID;
    private String buyerName;
    private List<BuyerShortlistEntry> entries = new ArrayList<>();

    public String getRequirementId()         { return requirementId; }
    public void   setRequirementId(String v) { this.requirementId = v; }

    public String getBuyerAID()         { return buyerAID; }
    public void   setBuyerAID(String v) { this.buyerAID = v; }

    public String getBuyerName()         { return buyerName; }
    public void   setBuyerName(String v) { this.buyerName = v; }

    public List<BuyerShortlistEntry> getEntries()                       { return entries; }
    public void                      setEntries(List<BuyerShortlistEntry> v) { this.entries = v; }
}