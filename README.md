# Car Negotiation Platform — COS30018 V1

Multi-agent automated negotiation system for trading automotive vehicles.
Built with JADE 4.6.0 + Java 21 + FlatLaf (Swing).

---

## Roles

| Role | Who runs it | What they do |
|------|-------------|--------------|
| **HOST / Broker (KA)** | One person (your PC) | Receives listings & requirements; manually pairs buyers with dealers; routes negotiation messages |
| **Dealer (DA)** | Each dealer groupmate | Submits car listings; receives broker-assigned buyers; negotiates manually |
| **Buyer (BA)** | Each buyer groupmate | Submits requirements; receives broker-assigned dealer; negotiates manually |

---

## Setup (Do Once Per Machine)

1. Install **JDK 21** and **IntelliJ IDEA Community**.
2. Clone/unzip the project.
3. Copy `jade.jar` into the `lib/` folder at the project root.
   - Your jade.jar is at: `JADE-bin-4.6.0\jade\lib\jade.jar`
4. Open the project in IntelliJ → click the **Maven elephant icon** to load dependencies.
   - FlatLaf and Gson download automatically.
5. Run `negotiation.Launcher` as the main class (no program arguments needed).

---

## Running the System

### Step 1 — Host starts the platform
One team member (the "broker operator") runs the Launcher and selects **HOST**.
- The Broker GUI opens showing the platform dashboard.
- Find your IP: `ipconfig` (Windows) or `ifconfig` (Mac/Linux) → look for your WiFi IPv4 address.
- Share this IP with your groupmates.

### Step 2 — Dealers and Buyers join
Each groupmate runs the Launcher on their own machine:
- Select **DEALER** or **BUYER**
- Enter the HOST's IP address
- Enter a unique agent name (e.g. `Alice`, `Dealer1`)
- Click LAUNCH — their GUI opens

> All machines must be on the **same WiFi/network**. Port 1099 must not be blocked.

### Step 3 — Run a negotiation
1. **Dealer** → goes to "My Listings" tab → fills in car details → clicks "Submit Listing to Broker"
2. **Buyer** → goes to "My Requirements" tab → fills in requirements → clicks "Submit Requirements to Broker"
3. **Broker (HOST)** → opens the "🔗 Assign" tab → sees all listings (top) and buyer requirements (bottom)
   → selects one listing + one buyer → optionally types a broker note → clicks "🔗 Assign Selected"
4. **Dealer** → a new "💬 [BuyerName]" tab opens automatically → dealer types an opening price and message → Send Offer
5. **Buyer** → a new "💬 [DealerName]" tab opens automatically → buyer sees the dealer's offer → can counter-offer or accept
6. Negotiation continues until one party clicks Accept or Reject.
7. **Broker** → the "💬 Negotiations" tab shows the full message history and logs completed deals.

---

## Building a Fat JAR (for sharing without IntelliJ)

```bash
mvn package
```

Produces: `target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar`

Run on any machine with Java 21:
```bash
java -jar car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
```

---

## Project Structure

```
src/main/java/negotiation/
├── Launcher.java                         ← entry point / startup dialog
├── messages/
│   └── Ontology.java                     ← all ACL message type constants
├── models/
│   ├── CarListing.java                   ← dealer's car listing
│   ├── CarRequirement.java               ← buyer's search requirements
│   ├── Assignment.java                   ← broker-created pair (listing ↔ buyer)
│   └── NegotiationMessage.java           ← offer / counter / accept / reject
├── agents/
│   ├── BrokerAgent.java                  ← KA: platform hub
│   ├── DealerAgent.java                  ← DA: car dealer
│   └── BuyerAgent.java                   ← BA: car buyer
├── behaviours/
│   ├── broker/BrokerMessageBehaviour.java
│   ├── dealer/DealerMessageBehaviour.java
│   └── buyer/BuyerMessageBehaviour.java
└── gui/
    ├── BrokerGui.java                    ← broker dashboard + assign panel
    ├── DealerGui.java                    ← dealer listing form + chat tabs
    └── BuyerGui.java                     ← buyer requirements form + chat tabs
```

---

## V1 Message Flow

```
DA  ──LISTING_REGISTER──────▶  KA   (dealer submits a listing)
KA  ──LISTING_ACK────────────▶ DA

BA  ──BUYER_REQUIREMENTS─────▶  KA  (buyer submits requirements)
KA  ──REQUIREMENTS_ACK───────▶ BA

  [ KA operator manually creates assignment in the GUI ]

KA  ──ASSIGNMENT_NOTIFY──────▶ DA   (broker tells dealer: you have a buyer)
KA  ──ASSIGNMENT_NOTIFY──────▶ BA   (broker tells buyer: you have a dealer)

DA  ──NEG_OFFER──────────────▶ KA ──route──▶ BA   (dealer makes first offer)
BA  ──NEG_OFFER──────────────▶ KA ──route──▶ DA   (buyer counter-offers)
BA  ──NEG_ACCEPT─────────────▶ KA              (buyer accepts)
KA  ──DEAL_COMPLETE──────────▶ DA & BA         (broker confirms deal)
```

---

## Common Issues

| Problem | Fix |
|---------|-----|
| `Broker not found in DF` | Make sure HOST launched first and is running before dealers/buyers join |
| `Launch failed: connection refused` | Check the host IP is correct and port 1099 is not blocked by firewall |
| `Address already in use: 1099` | Kill existing JADE: `netstat -ano \| findstr :1099` then `taskkill /PID <pid> /F` |
| Agent name rejected | Use only letters, digits, `-` and `_` |
| Dealer/Buyer GUI doesn't open | Check IntelliJ console for errors; jade.jar must be in `lib/` |
