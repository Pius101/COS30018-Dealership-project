package negotiation.messages;

/**
 * Central registry of every ACL message type used in the Car Negotiation Platform.
 *
 * ─── How these constants are used ──────────────────────────────────────────────
 *   msg.setOntology(Ontology.TYPE_*)           identifies the message's purpose
 *   msg.setConversationId(Ontology.CONV_*)     groups related messages in a session
 *
 * ─── V1 message flow (manual broker assignment) ────────────────────────────────
 *
 *  REGISTRATION
 *   DA  ──LISTING_REGISTER──▶  KA           dealer submits car listing
 *   KA  ──LISTING_ACK────────▶ DA           broker confirms receipt
 *   BA  ──BUYER_REQUIREMENTS──▶ KA          buyer submits requirements
 *   KA  ──REQUIREMENTS_ACK───▶ BA           broker confirms receipt
 *
 *  BROKER MANUAL ASSIGNMENT  (triggered by KA operator clicking "Assign" in GUI)
 *   KA  ──ASSIGNMENT_NOTIFY──▶ DA           "you have been assigned to buyer X"
 *   KA  ──ASSIGNMENT_NOTIFY──▶ BA           "you have been assigned to dealer Y for listing Z"
 *
 *  MANUAL NEGOTIATION  (routed through KA so broker can see everything)
 *   DA  ──NEG_OFFER──────────▶ KA ──route──▶ BA    dealer makes/counter-offers
 *   BA  ──NEG_OFFER──────────▶ KA ──route──▶ DA    buyer counter-offers
 *   DA  ──NEG_ACCEPT─────────▶ KA ──route──▶ BA    dealer accepts buyer's last offer
 *   BA  ──NEG_ACCEPT─────────▶ KA ──route──▶ DA    buyer accepts dealer's last offer
 *   DA  ──NEG_REJECT─────────▶ KA ──route──▶ BA    dealer ends negotiation
 *   BA  ──NEG_REJECT─────────▶ KA ──route──▶ DA    buyer ends negotiation
 *   KA  ──DEAL_COMPLETE──────▶ DA & BA              broker confirms deal is done
 */
public final class Ontology {

    // ── DF Service identifiers ────────────────────────────────────────────────
    /** Type string the Broker Agent registers in the JADE yellow pages (DF). */
    public static final String BROKER_SERVICE_TYPE = "car-negotiation-broker";
    public static final String BROKER_SERVICE_NAME = "car-negotiation-platform";

    // ── Conversation IDs ──────────────────────────────────────────────────────
    public static final String CONV_REGISTRATION  = "registration";
    public static final String CONV_ASSIGNMENT    = "assignment";
    public static final String CONV_NEGOTIATION   = "negotiation";

    // ── Registration ──────────────────────────────────────────────────────────
    /** DA → KA: dealer registers a car listing on the platform. */
    public static final String TYPE_LISTING_REGISTER   = "LISTING_REGISTER";
    /** KA → DA: acknowledgement — listing stored successfully. */
    public static final String TYPE_LISTING_ACK        = "LISTING_ACK";
    /** BA → KA: buyer submits car search requirements. */
    public static final String TYPE_BUYER_REQUIREMENTS = "BUYER_REQUIREMENTS";
    /** KA → BA: acknowledgement — requirements stored successfully. */
    public static final String TYPE_REQUIREMENTS_ACK   = "REQUIREMENTS_ACK";

    // ── Assignment (broker-initiated) ─────────────────────────────────────────
    /**
     * KA → DA and KA → BA: broker has manually assigned this buyer–dealer pair.
     * Content is an {@code Assignment} object serialized to JSON.
     */
    public static final String TYPE_ASSIGNMENT_NOTIFY  = "ASSIGNMENT_NOTIFY";

    // ── Negotiation ───────────────────────────────────────────────────────────
    /** DA/BA → KA → other party: an offer or counter-offer with a price. */
    public static final String TYPE_NEG_OFFER    = "NEG_OFFER";
    /** DA/BA → KA → other party: accept the other party's last offer. */
    public static final String TYPE_NEG_ACCEPT   = "NEG_ACCEPT";
    /** DA/BA → KA → other party: reject and end the negotiation. */
    public static final String TYPE_NEG_REJECT   = "NEG_REJECT";
    /** KA → DA & BA: deal is complete; contains the final agreed price. */
    public static final String TYPE_DEAL_COMPLETE = "DEAL_COMPLETE";

    private Ontology() { /* utility class */ }
}
