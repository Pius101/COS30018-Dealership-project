package negotiation.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Dealer → Broker (PROPOSE, ontology = DEALER_SELECTION).
 *
 * Phase C reply: the subset of potential buyers the dealer has chosen to
 * engage. The broker turns each selected entry into an Assignment and starts
 * the negotiation (reusing the existing createAssignment flow).
 */
public class DealerSelection {

    private String dealerAID;
    private List<PotentialBuyerEntry> selected = new ArrayList<>();

    public String getDealerAID()         { return dealerAID; }
    public void   setDealerAID(String v) { this.dealerAID = v; }

    public List<PotentialBuyerEntry> getSelected()                       { return selected; }
    public void                      setSelected(List<PotentialBuyerEntry> v) { this.selected = v; }
}