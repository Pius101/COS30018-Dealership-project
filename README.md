# How to run

1. Check root project folder to have these:
  - A lib folder containing "jade.jar"
  - A "NegotiationAppLauncher.bat" file
  - A "target" folder (not given) whichi contains teh jar file for the project, compile it locally
2. To get your target folder run "mvn clean package" in your terminal (alternatively run the project in your IDE launching the Launcher class)
3. Click o the .bat file to run the appication. For more instances, just click on the bat file again.

# Car Negotiation Platform - COS30018

Multi-agent automotive negotiation system built with JADE 4.6.0, Java 21, Maven, Gson, and FlatLaf Swing.

The project supports both the live JADE agent workflow and offline strategy experiments for the report critical analysis.

## Current Features

- Broker, dealer, and buyer JADE agents.
- Swing GUIs for broker assignment, dealer listings, buyer requirements, and negotiation chat tabs.
- Broker-routed ACL negotiation messages.
- Manual and automated buyer negotiation.
- Multiple concurrent buyer negotiations for one requirement.
- Automatic buyer shortlisting, including an option to choose listings from different dealers.
- JADE Sniffer launch button in the broker GUI for live ACL sequence diagrams.
- Offline strategy experiment runner for automatic critical-analysis data.
- Negotiation test logger that produces text, CSV, and HTML reports.

## Roles

| Role | Agent | Main responsibility |
|------|-------|---------------------|
| Broker | `BrokerAgent` | Receives listings and requirements, assigns buyers to dealers, routes negotiation messages, logs completed deals |
| Dealer | `DealerAgent` | Submits car listings and negotiates with assigned buyers |
| Buyer | `BuyerAgent` | Submits requirements, receives assignments, negotiates manually or automatically |

## Requirements

- JDK 21
- Maven
- JADE 4.6.0 local jar
- IntelliJ IDEA Community or another Java IDE

JADE is not downloaded from Maven Central. Copy `jade.jar` into:

```text
lib/jade.jar
```

The expected source is usually:

```text
JADE-bin-4.6.0/jade/lib/jade.jar
```

## Build

Compile and package the project:

```bash
mvn package
```

This creates:

```text
target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Quick compile check without tests:

```bash
mvn -q -DskipTests package
```

## Run The Live JADE System

### From IntelliJ

Run this main class:

```text
negotiation.Launcher
```

### From the packaged jar

```bash
java -jar target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Normal workflow

1. One person starts the launcher and selects `HOST`.
2. The broker GUI opens.
3. The host shares their local IPv4 address with the team.
4. Dealers and buyers start the launcher, choose their role, enter the host IP, and launch.
5. Dealers submit listings.
6. Buyers submit requirements.
7. Broker assigns a buyer requirement to a dealer listing.
8. Buyer and dealer negotiate until accept or reject.
9. Broker logs the full message history.

All machines must be on the same network. Port `1099` must be reachable.

## Broker Sniffer

The broker GUI includes a Sniffer button. It launches the JADE Sniffer tool using:

```text
jade.tools.sniffer.Sniffer
```

Use it to capture ACL message flow as a sequence diagram for the report.

## Automatic Buyers

Buyer config files live in:

```text
Scripts/agent_configs/
```

Important buyer automation settings:

| Setting | Meaning |
|---------|---------|
| `autoNegotiate` | Enables automated negotiation behaviour |
| `autoShortlist` | Lets the buyer automatically select matching listings |
| `shortlistLimit` | Maximum number of listings to shortlist |
| `shortlistDifferentDealers` | When true, automatic shortlisting takes at most one listing from each dealer |
| `shortlistDelayMs` | Delay before auto-shortlisting starts |
| `strategy` | Negotiation strategy key from `StrategyRegistry` |
| `maxRounds` | Maximum negotiation rounds |

Example:

```json
{
  "autoNegotiate": true,
  "autoShortlist": true,
  "shortlistLimit": 3,
  "shortlistDifferentDealers": true,
  "strategy": "TIME_DEPENDENT_CONCEDER",
  "maxRounds": 20
}
```

The helper script also writes this setting when generating buyer configs:

```text
Scripts/spawn_agents_fixed.py
```

## Negotiation Strategies

Strategies are registered in:

```text
src/main/java/negotiation/strategy/StrategyRegistry.java
```

Current built-in keys include:

- `BAYESIAN`
- `TIME_DEPENDENT_CONCEDER`
- `TIME_DEPENDENT_BOULWARE`
- `TIT_FOR_TAT`
- `FIXED_INCREMENT_2PCT`
- `FIXED_INCREMENT_5PCT`

To add another strategy, implement `NegotiationStrategy` and register it in `StrategyRegistry`.

## Offline Strategy Experiments

The offline experiment runner is:

```text
src/main/java/negotiation/experiments/StrategyExperimentRunner.java
```

It does not start JADE agents. It runs the negotiation strategies directly, which makes it faster and easier to produce repeatable critical-analysis evidence.

Run all strategy pairs with default scenarios:

```bash
java -cp target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar negotiation.experiments.StrategyExperimentRunner
```

Useful options:

```bash
java -cp target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar negotiation.experiments.StrategyExperimentRunner --threads 4 --repeats 5 --rounds 10
```

Filter strategies:

```bash
java -cp target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar negotiation.experiments.StrategyExperimentRunner --buyers BAYESIAN,TIT_FOR_TAT --dealers TIME_DEPENDENT_CONCEDER,TIT_FOR_TAT
```

Other options:

| Option | Meaning |
|--------|---------|
| `--threads N` | Number of worker threads |
| `--repeats N` | Repeats each scenario/strategy pair |
| `--rounds N` | Maximum rounds |
| `--seed N` | Reproducible random seed |
| `--variation 0.03` | Scenario variation for repeated runs |
| `--no-variation` | Disable repeat variation |
| `--out path` | Output directory |
| `--scenarios path` | Load custom scenario JSON |
| `--buyers key1,key2` | Buyer strategy filter |
| `--dealers key1,key2` | Dealer strategy filter |

## Experiment Outputs

Each experiment run creates a session folder:

```text
experiment_results/session_yyyy-MM-dd_HH-mm-ss/
```

Main files:

| File | Purpose |
|------|---------|
| `run_config.json` | Options, seed, strategies, scenarios, Java version, git commit if available |
| `summary.txt` | Human-readable experiment summary |
| `summary.csv` | Per-case data for analysis |
| `strategy_pair_summary.csv` | Aggregate results by buyer strategy and dealer strategy |
| `exp_*/transcript.txt` | Round-by-round transcript |
| `exp_*/result.csv` | Single-case CSV row |
| `exp_*/result.json` | Single-case JSON |
| `exp_*/report.html` | Per-case visual report |

The runner also calls `NegotiationTestLogger`, which refreshes:

```text
logs/negotiation-test-report.txt
logs/negotiation-test-report.csv
experiment_results/negotiation-test-report.html
```

## Critical Analysis Data

The experiment runner now includes baseline-aware data for critical analysis.

The baseline answers this question:

```text
What would happen if the buyer did not negotiate and simply bought at the asking price?
```

Important `summary.csv` columns:

| Column | Meaning |
|--------|---------|
| `outcome` | `DEAL` or `NO_DEAL` |
| `rounds` | Rounds taken |
| `asking_price` | Dealer asking price |
| `buyer_reservation_price` | Buyer maximum acceptable price |
| `dealer_reservation_price` | Dealer floor price |
| `final_price` | Accepted deal price |
| `saving_from_ask` | Asking price minus final price |
| `saving_pct` | Saving as a percentage |
| `baseline_outcome` | Whether buy-at-asking would have been possible |
| `baseline_price` | Baseline buy-at-asking price |
| `baseline_saving_from_ask` | Usually zero for buy-at-asking |
| `negotiation_gain_vs_baseline` | Baseline price minus negotiated final price |
| `buyer_utility` | Buyer-side utility score |
| `dealer_utility` | Dealer-side utility score |
| `joint_utility` | Buyer utility plus dealer utility |
| `benefit_side` | `BUYER`, `DEALER`, `BALANCED`, or `NO_DEAL` |

Important `strategy_pair_summary.csv` columns:

| Column | Meaning |
|--------|---------|
| `deals` | Number of successful deals |
| `deal_rate` | Successful deals as a percentage |
| `avg_rounds` | Average rounds |
| `avg_final_price` | Average final price for deals |
| `avg_buyer_utility` | Average buyer utility |
| `avg_dealer_utility` | Average dealer utility |
| `baseline_deals` | Cases where buying at asking would be possible |
| `baseline_deal_rate` | Baseline deal rate |
| `deal_rate_delta_vs_baseline` | Negotiated deal rate minus baseline deal rate |
| `avg_baseline_price` | Average baseline price |
| `avg_negotiation_gain_vs_baseline` | Average buyer gain compared with buy-at-asking |

Use these files to discuss:

- Which strategy pairs get the best deal rate.
- Which buyer strategies get lower prices.
- Which strategies trade better prices for more rounds.
- Whether negotiation improves on buying at asking price.
- Whether outcomes favour the buyer, dealer, or both.
- Limitations of simulated strategies and simplified scenarios.

## Negotiation Test Logger

The logger is:

```text
src/main/java/negotiation/testing/NegotiationTestLogger.java
```

Run it directly to analyse conversation logs or the latest experiment summary:

```bash
java -cp target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar negotiation.testing.NegotiationTestLogger
```

It reads:

```text
conversations/*.txt
```

If no conversation logs are found, it uses the latest experiment `summary.csv`.

## Project Structure

```text
src/main/java/negotiation/
|-- Launcher.java
|-- agents/
|   |-- BrokerAgent.java
|   |-- DealerAgent.java
|   `-- BuyerAgent.java
|-- behaviours/
|   |-- AutoNegotiationBehaviour.java
|   |-- broker/
|   |-- dealer/
|   `-- buyer/
|-- experiments/
|   `-- StrategyExperimentRunner.java
|-- gui/
|   |-- BrokerGui.java
|   |-- DealerGui.java
|   `-- BuyerGui.java
|-- messages/
|   `-- Ontology.java
|-- models/
|   |-- CarListing.java
|   |-- CarRequirement.java
|   |-- Assignment.java
|   `-- NegotiationMessage.java
|-- strategy/
|   |-- NegotiationStrategy.java
|   |-- StrategyRegistry.java
|   |-- BayesianLearnerStrategy.java
|   |-- TimeDependentStrategy.java
|   |-- TitForTatStrategy.java
|   `-- FixedIncrementStrategy.java
`-- testing/
    `-- NegotiationTestLogger.java
```

Other important folders:

```text
Scripts/agent_configs/      generated buyer/dealer config files
Scripts/spawn_agents_fixed.py
conversations/              live negotiation logs
logs/                       generated text/CSV reports
reports/                    individual live negotiation reports
experiment_results/         offline experiment sessions and HTML summary
target/                     Maven build output
```

## Message Flow

```text
Dealer -> Broker: LISTING_REGISTER
Broker -> Dealer: LISTING_ACK

Buyer -> Broker: BUYER_REQUIREMENTS
Broker -> Buyer: REQUIREMENTS_ACK

Broker operator or auto-shortlist creates assignment.

Broker -> Dealer: ASSIGNMENT_NOTIFY
Broker -> Buyer: ASSIGNMENT_NOTIFY

Dealer -> Broker -> Buyer: NEG_OFFER
Buyer -> Broker -> Dealer: NEG_OFFER
Buyer/Dealer -> Broker: NEG_ACCEPT or NEG_REJECT
Broker -> Buyer and Dealer: DEAL_COMPLETE or final status
```

## Common Issues

| Problem | Fix |
|---------|-----|
| `Broker not found in DF` | Start the host/broker first |
| `Launch failed: connection refused` | Check host IP, network, and firewall |
| `Address already in use: 1099` | Stop the old JADE process or kill the process using port 1099 |
| Agent name rejected | Use only letters, digits, `-`, and `_` |
| GUI does not open | Check IntelliJ console and confirm `lib/jade.jar` exists |
| Sniffer does not open | Confirm the project was rebuilt after the `jade.tools.sniffer.Sniffer` fix |
| No experiment report data | Run `StrategyExperimentRunner` or make sure conversation logs exist |

## Notes For The Report

For the critical analysis section, do not only paste tables. Use the generated data to explain what happened and why.

Recommended evidence:

- `experiment_results/session_*/summary.csv`
- `experiment_results/session_*/strategy_pair_summary.csv`
- `logs/negotiation-test-report.csv`
- Sniffer screenshots showing ACL message flow
- Per-case `report.html` files for examples of specific negotiations

