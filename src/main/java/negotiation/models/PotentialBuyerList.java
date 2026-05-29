package negotiation.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Broker → Dealer (CFP, ontology = POTENTIAL_BUYERS).
 *
 * Phase C: the broker forwards to a dealer the list of potential buyers
 * (and their first offers) interested in that dealer's cars. The dealer
 * then chooses whom to engage and replies with a {@link DealerSelection}.
 */
public class PotentialBuyerList {

    private String dealerAID;
    private List<PotentialBuyerEntry> buyers = new ArrayList<>();

    public String getDealerAID()         { return dealerAID; }
    public void   setDealerAID(String v) { this.dealerAID = v; }

    public List<PotentialBuyerEntry> getBuyers()                       { return buyers; }
    public void                      setBuyers(List<PotentialBuyerEntry> v) { this.buyers = v; }
}