package negotiation.messages;

/**
 * Message Type Dictionary for Car Negotiation Platform
 * 
 * In JADE, agents send ACL messages to communicate. The "ontology" field
 * tells the receiver what type of message this is. Think of these constants
 * as message type identifiers.
 * 
 * USAGE EXAMPLE:
 *   ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
 *   msg.setOntology(Ontology.MESSAGE_TYPE.LISTING_REGISTER);
 *   msg.setContent(jsonString);
 *   send(msg);
 * 
 * MESSAGE FLOW:
 *   1. Registration: Dealers submit listings, Buyers submit requirements
 *   2. Assignment: Broker manually matches buyers to listings
 *   3. Negotiation: Back-and-forth offers through broker
 *   4. Completion: Deal finalized and logged
 */
public final class Ontology {

    // ── Service Registration (for JADE Directory Facilitator) ────────────────
    /** How agents find the broker in JADE's yellow pages */
    public static final String BROKER_SERVICE_TYPE = "car-negotiation-broker";
    public static final String BROKER_SERVICE_NAME = "car-negotiation-platform";

    // ── Conversation Categories ───────────────────────────────────────────────
    /** Groups related messages - helps with message filtering */
    public static final String CONV_REGISTRATION  = "registration";
    public static final String CONV_MATCHING      = "matching";    // automated match protocol
    public static final String CONV_ASSIGNMENT    = "assignment";
    public static final String CONV_NEGOTIATION   = "negotiation";

    // ── Message Types ─────────────────────────────────────────────────────────
    /** 
     * REGISTRATION MESSAGES
     * Used when agents first join the platform
     */
    public static final class MESSAGE_TYPE {
        /** Dealer → Broker: "Here's my car for sale" */
        public static final String LISTING_REGISTER   = "LISTING_REGISTER";
        
        /** Broker → Dealer: "Got your listing, stored it" */
        public static final String LISTING_ACK        = "LISTING_ACK";
        
        /** Buyer → Broker: "Here's what I want to buy" */
        public static final String BUYER_REQUIREMENTS = "BUYER_REQUIREMENTS";
        
        /** Broker → Buyer: "Got your requirements, stored them" */
        public static final String REQUIREMENTS_ACK   = "REQUIREMENTS_ACK";
        
        /**
         * AUTOMATED MATCHING PROTOCOL (Task 1 core flow)
         *
         * Flow:
         *   1. BA submits requirements (BUYER_REQUIREMENTS above)
         *   2. KA auto-matches and sends results → BA
         *   3. BA sends back shortlist → KA
         *   4. KA sends interested buyers per listing → DA
         *   5. DA sends selection back → KA
         *   6. KA creates assignment → notifies DA + BA (ASSIGNMENT_NOTIFY above)
         */

        /**
         * KA → BA: "Here are the listings that match your requirements."
         * Content: { requirementId, listings: [CarListing, ...] }
         */
        public static final String MATCHING_RESULTS = "MATCHING_RESULTS";

        /**
         * BA → KA: "I want to negotiate on these listings (my shortlist)."
         * Content: { requirementId, buyerAID, buyerName, entries: [BuyerShortlistEntry, ...] }
         * (Up to 3 entries — Extension 1 allows concurrent negotiations)
         */
        public static final String BUYER_SHORTLIST  = "BUYER_SHORTLIST";

        /**
         * KA → DA: "These buyers are interested in your listing [ID]."
         * Content: { listingId, buyers: [PotentialBuyerEntry, ...] }
         */
        public static final String POTENTIAL_BUYERS = "POTENTIAL_BUYERS";

        /**
         * DA → KA: "I want to negotiate with these buyers."
         * Content: { listingId, selectedBuyerAIDs: ["aid1", "aid2", ...] }
         */
        public static final String DEALER_SELECTION = "DEALER_SELECTION";

        /**
         * ASSIGNMENT MESSAGES
         * Broker tells buyers and dealers they've been matched
         */
        /** Broker → Dealer/Buyer: "You've been matched, start negotiating" */
        public static final String ASSIGNMENT_NOTIFY  = "ASSIGNMENT_NOTIFY";
        
        /**
         * NEGOTIATION MESSAGES
         * All offers/accepts/rejects flow through broker
         */
        /** Dealer/Buyer → Broker → Other: "Here's my price offer" */
        public static final String NEG_OFFER    = "NEG_OFFER";
        
        /** Dealer/Buyer → Broker: "I accept the last offer" */
        public static final String NEG_ACCEPT   = "NEG_ACCEPT";
        
        /** Dealer/Buyer → Broker: "I reject this negotiation" */
        public static final String NEG_REJECT   = "NEG_REJECT";
        
        /**
         * COMPLETION MESSAGES
         */
        /** Broker → Both: "Deal done, final price is X" */
        public static final String DEAL_COMPLETE = "DEAL_COMPLETE";
    }
    
    // ── Backward Compatibility (keep old constants working) ───────────────────
    // These let existing code keep working while we transition to MESSAGE_TYPE.*
    public static final String TYPE_LISTING_REGISTER   = MESSAGE_TYPE.LISTING_REGISTER;
    public static final String TYPE_LISTING_ACK        = MESSAGE_TYPE.LISTING_ACK;
    public static final String TYPE_BUYER_REQUIREMENTS = MESSAGE_TYPE.BUYER_REQUIREMENTS;
    public static final String TYPE_REQUIREMENTS_ACK   = MESSAGE_TYPE.REQUIREMENTS_ACK;
    public static final String TYPE_ASSIGNMENT_NOTIFY  = MESSAGE_TYPE.ASSIGNMENT_NOTIFY;
    public static final String TYPE_NEG_OFFER          = MESSAGE_TYPE.NEG_OFFER;
    public static final String TYPE_NEG_ACCEPT         = MESSAGE_TYPE.NEG_ACCEPT;
    public static final String TYPE_NEG_REJECT         = MESSAGE_TYPE.NEG_REJECT;
    public static final String TYPE_DEAL_COMPLETE      = MESSAGE_TYPE.DEAL_COMPLETE;

    // ── Spec protocol: matching → shortlist → selection ──
    public static final String TYPE_MATCH_RESULTS    = "MATCH_RESULTS";     // Broker → Buyer
    public static final String TYPE_BUYER_SHORTLIST  = "BUYER_SHORTLIST";   // Buyer  → Broker
    public static final String TYPE_POTENTIAL_BUYERS = "POTENTIAL_BUYERS";  // Broker → Dealer
    public static final String TYPE_DEALER_SELECTION = "DEALER_SELECTION";  // Dealer → Broker

    // ── Special Messages ───────────────────────────────────────────────────────
    /** Agent → Broker: "I'm shutting down, save my active negotiations" */
    public static final String EMERGENCY_SAVE = "EMERGENCY_SAVE";

    // Prevent instantiation - this is just a constants dictionary
    private Ontology() { }
}