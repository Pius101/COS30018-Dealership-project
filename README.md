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

## Quick Run Guide

### Option A — Run from IntelliJ

1. Open the project folder in IntelliJ:

   ```text
   COS30018-Dealership-project
   ```

2. Wait for Maven to load or click **Maven → Reload All Maven Projects**.
3. Make sure the Project SDK is **Java 21**.
4. Open and run:

   ```text
   src/main/java/negotiation/Launcher.java
   ```

5. Use this run configuration if needed:

   | Setting | Value |
      |---|---|
   | Main class | `negotiation.Launcher` |
   | Working directory | Project root folder |
   | Program arguments | Leave empty |
   | JDK | Java 21 |

Start the **HOST / Broker** first, then start **Dealer** and **Buyer** agents.

### Option B — Build and Run the JAR

From the project root:

```bash
mvn clean package
```

This creates:

```text
target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Run it with:

```bash
java -jar target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Option C — Run with the Python Agent Spawner

The spawner is located at:

```text
Scripts/spawn_agents_fixed.py
```

Run it from the project root:

```bash
python Scripts/spawn_agents_fixed.py
```

Or from inside the `Scripts` folder:

```bash
python spawn_agents_fixed.py
```

The spawner expects the application JAR here:

```text
target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
```

If needed, pass the application JAR manually:

```bash
python Scripts/spawn_agents_fixed.py --jar target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
```

> Important: `--jar` means the project application JAR, not `jade.jar`.

---


### Option D — Run the Python Spawner from IntelliJ

Use this if you want IntelliJ to run `Scripts/spawn_agents_fixed.py` directly from the green **Run** button.

#### 1. Add the Python interpreter to IntelliJ SDKs

1. Open **File → Project Structure...**.
2. Go to **Platform Settings → SDKs**.
3. Click **+** and choose **Add Python SDK** or **Python SDK**.
4. Select the project virtual environment interpreter:

   ```text
   COS30018-Dealership-project/.venv/Scripts/python.exe
   ```

   Example full path:

   ```text
   E:/javaIDE/javacode/COS30018-Dealership-project/.venv/Scripts/python.exe
   ```

5. Click **Apply**.

You should now see a Python SDK similar to:

```text
Python 3.14 (COS30018-Dealership-project)
```

#### 2. Create the Python Run/Debug Configuration

1. Open **Run → Edit Configurations...**.
2. Click **+** and choose **Python**.
3. Set the configuration like this:

   | Setting | Value |
      |---|---|
   | Name | `spawn_agents` |
   | Python interpreter | `Python 3.14 (COS30018-Dealership-project)` or your project `.venv` interpreter |
   | Script path | `COS30018-Dealership-project/Scripts/spawn_agents_fixed.py` |
   | Parameters | `--as-host` |
   | Working directory | `COS30018-Dealership-project/Scripts` |
   | Environment variables | `PYTHONUNBUFFERED=1` |

4. Tick **Store as project file** if you want the configuration saved with the project.
5. Click **Apply**, then **OK**.
6. Select `spawn_agents` from the run menu and click **Run**.

Use `--as-host` when your PC is starting the JADE host/broker and the agents.

If the host is already running on another machine, replace `--as-host` with:

```bash
--host HOST_IP_ADDRESS
```

Example:

```bash
--host 192.168.1.10
```

Useful spawner parameters(not a must to  include can just run fin without them):

| Parameter | Meaning |
|---|---|
| `--as-host` | Starts the JADE host, broker, dealers, and buyers on this PC |
| `--host 192.168.x.x` | Connects agents to an existing host PC |
| `--dealers-only` | Starts only dealer agents |
| `--buyers-only` | Starts only buyer agents |
| `--num-buyers 10` | Changes the number of generated buyers |
| `--dry-run` | Shows what would be launched without starting agents |
| `--jade-gui` | Opens the JADE GUI while running |

For example, to start only 10 buyers connected to a host:

```bash
--host 192.168.1.10 --buyers-only --num-buyers 10
```

> Note: The spawner still needs the built application JAR in `target/`. If it is missing, run `mvn package` first.

---

## JADE JAR Location

The best place for `jade.jar` is:

```text
COS30018-Dealership-project/lib/jade.jar
```

The Python spawner can also find it in:

```text
COS30018-Dealership-project/Scripts/lib/jade.jar
COS30018-Dealership-project/Scripts/jade.jar
COS30018-Dealership-project/jade.jar
```

If JADE is missing, copy it from:

```text
JADE-bin-4.6.0/jade/lib/jade.jar
```

---

## Reports, Logs, and Output Files

Generated output is usually saved in these folders:

| Folder | Purpose |
|---|---|
| `reports/` or `Scripts/reports/` | Negotiation reports |
| `logs/` or `Scripts/logs/` | Runtime logs and summary logs |
| `conversations/` or `Scripts/conversations/` | Saved negotiation messages |
| `experiment_results/` or `Scripts/experiment_results/` | Offline experiment results |
| `Scripts/agent_configs/` | Generated agent configuration files |

Check these folders after running negotiations or experiments.

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

```text
COS30018-Dealership-project/
├── README.md                              ← project setup and run guide
├── pom.xml                                ← Maven build file and dependencies
├── lib/
│   └── jade.jar                           ← JADE library used by IntelliJ/spawner
├── Scripts/
│   ├── spawn_agents_fixed.py              ← Python agent spawner
│   ├── car_market_data.json               ← sample/generated car market data
│   ├── lib/jade.jar                       ← backup JADE location for spawner
│   ├── agent_configs/                     ← generated dealer/buyer config JSON files
│   ├── conversations/                     ← saved negotiation histories: txt/csv/jsonl
│   ├── logs/                              ← host, agent, system, and error logs
│   ├── reports/                           ← generated HTML negotiation reports
│   └── experiment_results/                ← offline experiment output
├── agent_configs/                         ← root-level agent config output
├── auto_market_configs/                   ← auto market configuration files
├── conversations/                         ← root-level conversation output
├── experiment_results/                    ← root-level experiment sessions/reports
├── logs/                                  ← root-level runtime logs
├── reports/                               ← root-level generated reports
├── reearch paper/                         ← research/reference documents
├── src/main/java/negotiation/
│   ├── Launcher.java                      ← main entry point / startup dialog
│   ├── agents/
│   │   ├── BrokerAgent.java               ← broker / KA platform hub
│   │   ├── DealerAgent.java               ← dealer agent
│   │   └── BuyerAgent.java                ← buyer agent
│   ├── behaviours/
│   │   ├── BrokerMessageBehaviour.java    ← broker message handling
│   │   ├── DealerMessageBehaviour.java    ← dealer message handling
│   │   ├── BuyerMessageBehaviour.java     ← buyer message handling
│   │   ├── AutoNegotiationBehaviour.java  ← automatic negotiation behaviour
│   │   └── DealerNegotiationBehaviour.java← dealer-side negotiation flow
│   ├── experiments/
│   │   └── StrategyExperimentRunner.java  ← offline strategy experiment runner
│   ├── gui/
│   │   ├── BrokerGui.java                 ← broker dashboard and assign panel
│   │   ├── DealerGui.java                 ← dealer listing form and chat tabs
│   │   └── BuyerGui.java                  ← buyer requirements form and chat tabs
│   ├── messages/
│   │   └── Ontology.java                  ← ACL message type constants
│   ├── models/
│   │   ├── Assignment.java                ← broker-created buyer/dealer pairing
│   │   ├── CarListing.java                ← dealer car listing model
│   │   ├── CarRequirement.java            ← buyer requirement model
│   │   └── NegotiationMessage.java        ← offer/counter/accept/reject model
│   ├── network/
│   │   └── NetworkDiscovery.java          ← network/IP discovery helper
│   ├── report/
│   │   └── NegotiationReportGenerator.java← HTML/report generator
│   ├── strategy/
│   │   ├── NegotiationStrategy.java       ← strategy interface/base contract
│   │   ├── StrategyRegistry.java          ← available strategy registry
│   │   ├── NegotiationContext.java        ← negotiation state/context
│   │   ├── MultiAttributeUtility.java     ← multi-attribute scoring utility
│   │   ├── OpponentModel.java             ← opponent modelling support
│   │   ├── BayesianLearnerStrategy.java   ← Bayesian learner strategy
│   │   ├── FixedIncrementStrategy.java    ← fixed concession strategy
│   │   ├── TimeDependentStrategy.java     ← time-based concession strategy
│   │   └── TitForTatStrategy.java         ← tit-for-tat strategy
│   ├── testing/
│   │   └── NegotiationTestLogger.java     ← experiment/test logging
│   └── util/
│       ├── AppLogger.java                 ← application logging helper
│       ├── ConversationLogger.java        ← conversation file logging
│       ├── GuiMode.java                   ← GUI mode enum/helper
│       ├── HeadlessLogConfigurer.java     ← logging setup for headless runs
│       └── NegotiationAuditService.java   ← negotiation audit/history service
└── target/
    └── car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
                                            ← built runnable application JAR
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
