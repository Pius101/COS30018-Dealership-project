#!/usr/bin/env python3
"""
spawn_agents.py — Car Negotiation Platform Agent Spawner
=========================================================
Automatically launches Dealer and Buyer agents from the car market data JSON.

Dealers: one per car brand, fixed listings from car_market_data.json.
Buyers:  randomly generated from the same market data — random car preferences,
         random budgets relative to market price, random strategies.

Usage examples
--------------
  # Full platform on localhost (host is already running)
  python spawn_agents.py --host 192.168.1.10

  # Start host + all agents on this machine
  python spawn_agents.py --as-host

  # Dealers only
  python spawn_agents.py --host 192.168.1.10 --dealers-only

  # 10 random buyers only
  python spawn_agents.py --host 192.168.1.10 --buyers-only --num-buyers 10

  # Specific strategies for buyers
  python spawn_agents.py --host 192.168.1.10 --buyer-strategy BAYESIAN

  # Dry run (see what would launch without launching)
  python spawn_agents.py --host 192.168.1.10 --dry-run

  # Use larger dataset
  python spawn_agents.py --host 192.168.1.10 --data bigger_dataset.json
"""

import argparse
import json
import os
import random
import subprocess
import sys
import time
from pathlib import Path


# ─── Configuration ────────────────────────────────────────────────────────────

BASE_DIR         = Path(__file__).resolve().parent
JAR_NAME         = "car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar"
DATA_FILE        = "car_market_data.json"
JADE_PORT        = 1099

# Must match NetworkDiscovery.java constants exactly
DISCOVERY_PORT   = 45678
DISCOVERY_QUERY  = "CAR_NEG_DISCOVER_V1"
DISCOVERY_PREFIX = "CAR_NEG_HOST:"
DISCOVERY_TIMEOUT = 3.0  # seconds to wait for host reply


def discover_host() -> str | None:
    """
    Broadcasts a UDP discovery query on the local network and waits for
    the JADE host to reply with its IP address.

    This mirrors the Auto-Discover button in the Java GUI launcher.
    The host replies because NetworkDiscovery.startHostListener() is running
    on port 45678 as soon as the HOST launches.

    Returns the host IP string, or None if no reply within DISCOVERY_TIMEOUT.
    """
    import socket
    query = DISCOVERY_QUERY.encode()
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(DISCOVERY_TIMEOUT)

        # Send to global broadcast + common subnet broadcasts
        for addr in ["255.255.255.255", "192.168.1.255", "192.168.0.255",
                     "10.0.0.255", "172.16.255.255"]:
            try:
                sock.sendto(query, (addr, DISCOVERY_PORT))
            except Exception:
                pass

        # Wait for reply
        data, _ = sock.recvfrom(256)
        reply = data.decode().strip()
        if reply.startswith(DISCOVERY_PREFIX):
            return reply[len(DISCOVERY_PREFIX):]
    except socket.timeout:
        pass
    except Exception:
        pass
    finally:
        sock.close()
    return None


def get_local_ip() -> str:
    """
    Return the local IP that child agents should use when this machine starts
    the JADE host. This must match the non-loopback address Java binds to.
    """
    import socket

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            ip = sock.getsockname()[0]
            if ip and not ip.startswith("127."):
                return ip
    except OSError:
        pass

    try:
        ip = socket.gethostbyname(socket.gethostname())
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        pass

    return "127.0.0.1"


def log_step(message: str) -> None:
    print(f"[Spawner] {message}", flush=True)


def format_cmd(cmd: list[str]) -> str:
    return " ".join(f'"{part}"' if " " in part else part for part in cmd)

# Dealer markup above market price
MARKUP_MIN = 0.12   # 12% — dealers start well above market, more room to negotiate
MARKUP_MAX = 0.22   # 22% — gives meaningful back-and-forth before meeting in the middle

# Strategy lists will be populated dynamically from Java
DEALER_STRATEGIES = []
BUYER_STRATEGIES = []

def fetch_strategies_from_java(host="localhost", port=8080, timeout=5.0):
    """
    Fetch available strategies from the Java StrategyRegistry REST service.
    Returns (dealer_strategies, buyer_strategies) tuples.
    """
    import urllib.request
    import urllib.error
    import json
    
    url = f"http://{host}:{port}/api/strategies"
    
    try:
        req = urllib.request.Request(url)
        req.add_header('User-Agent', 'spawn_agents.py/1.0')
        
        with urllib.request.urlopen(req, timeout=timeout) as response:
            if response.status == 200:
                data = json.loads(response.read().decode('utf-8'))
                
                # Extract strategy keys and names
                all_strategies = [(s['key'], s['name']) for s in data.get('strategies', [])]
                
                # Filter dealer strategies (typically more conservative)
                dealer_keys = [key for key, name in all_strategies 
                              if any(keyword in key.upper() for keyword in ['BOULWARE', 'TIT_FOR_TAT', 'FIXED_INCREMENT_5PCT'])]
                
                # Filter buyer strategies (more varied)
                buyer_keys = [key for key, name in all_strategies 
                             if any(keyword in key.upper() for keyword in ['BAYESIAN', 'CONCEDER', 'TIT_FOR_TAT', 'FIXED_INCREMENT_2PCT'])]
                
                return dealer_keys, buyer_keys
                
            else:
                return None, None
                
    except urllib.error.URLError:
        return None, None
    except json.JSONDecodeError:
        return None, None
    except Exception:
        return None, None

def initialize_strategies(host="localhost", port=8080):
    """
    Initialize strategy arrays either from Java service or fallback to defaults.
    """
    global DEALER_STRATEGIES, BUYER_STRATEGIES
    
    # Try to fetch from Java service
    log_step(f"Checking strategy service at http://{host}:{port}/api/strategies")
    dealer_strats, buyer_strats = fetch_strategies_from_java(host, port)
    
    if dealer_strats and buyer_strats:
        # Use dynamically fetched strategies
        DEALER_STRATEGIES = dealer_strats
        BUYER_STRATEGIES = buyer_strats
        log_step("Loaded strategy lists from the Java strategy service")
    else:
        # Fallback to hardcoded defaults
        DEALER_STRATEGIES = [
            "TIME_DEPENDENT_BOULWARE",
            "TIT_FOR_TAT", 
            "FIXED_INCREMENT_5PCT",
        ]
        BUYER_STRATEGIES = [
            "BAYESIAN",
            "TIME_DEPENDENT_CONCEDER",
            "TIT_FOR_TAT",
            "FIXED_INCREMENT_2PCT",
        ]
        log_step("Strategy service unavailable; using built-in strategy defaults")
    log_step("Dealer strategy pool: " + ", ".join(DEALER_STRATEGIES))
    log_step("Buyer strategy pool: " + ", ".join(BUYER_STRATEGIES))

# Estimated mileage per model year
MILEAGE_BY_YEAR = {
    2017: (60_000, 120_000),
    2018: (50_000, 100_000),
    2019: (40_000,  85_000),
    2020: (30_000,  70_000),
    2021: (20_000,  55_000),
    2022: (10_000,  40_000),
    2023: (     0,  20_000),
}

# Available strategies (must match StrategyRegistry keys in Java)
AVAILABLE_STRATEGIES = [
    "BAYESIAN",
    "TIME_DEPENDENT_CONCEDER",
    "TIME_DEPENDENT_BOULWARE",
    "TIT_FOR_TAT",
    "FIXED_INCREMENT_2PCT",
    "FIXED_INCREMENT_5PCT",
]

# Buyer personalities — affect how budget/offers are set
PERSONALITIES = {
    "aggressive": {
        "budget_factor":    (0.80, 0.92),   # budget = 80–92% of market price
        "first_offer_pct":  (0.70, 0.78),   # first offer = 70–78% of budget
        "description":      "Aggressive bargainer, low opening offer",
    },
    "moderate": {
        "budget_factor":    (0.90, 1.00),
        "first_offer_pct":  (0.80, 0.88),
        "description":      "Balanced approach",
    },
    "easy": {
        "budget_factor":    (0.95, 1.08),
        "first_offer_pct":  (0.88, 0.95),
        "description":      "Flexible buyer, willing to pay near market price",
    },
}

CAR_COLORS     = ["Pearl White", "Metallic Silver", "Midnight Black",
                  "Ruby Red", "Navy Blue", "Graphite Grey"]
BUYER_NAMES_A  = ["Ahmad", "Siti", "Raju", "Wei", "Priya", "Lim",
                  "Farah", "Kumar", "Aisha", "Tan", "Zara", "Muthu"]
BUYER_NAMES_B  = ["Buyer", "Shopper", "Customer", "Client"]


# ─── Market data helpers ──────────────────────────────────────────────────────

def load_market_data(filepath: str) -> dict:
    try:
        with open(filepath) as f:
            raw = json.load(f)
        data = raw.get("car_market_data", raw)
        return data
    except FileNotFoundError:
        raise SystemExit(f"Market data not found: {filepath}")
    except json.JSONDecodeError as e:
        raise SystemExit(f"Invalid JSON: {e}")


def get_all_cars(data: dict) -> list[tuple]:
    """Return flat list of (brand, model, year_int, market_price)."""
    cars = []
    for brand, models in data.items():
        for model, years in models.items():
            for year_str, price in years.items():
                cars.append((brand, model, int(year_str), price))
    return cars


def make_dealer_config(brand: str, models: dict, rng: random.Random,
                        auto_negotiate: bool = True,
                        strategy: str | None = None,
                        margin_pct: float | None = None) -> dict:
    """
    Build a dealer config dict for a given brand.

    strategy:   None = randomise per dealer from DEALER_STRATEGIES
    margin_pct: None = randomise per dealer (8-15% — how far they will drop)
    """
    # Randomise per dealer if not specified — each brand behaves differently
    chosen_strategy = strategy or rng.choice(DEALER_STRATEGIES)
    chosen_margin   = margin_pct if margin_pct is not None else rng.uniform(0.08, 0.15)
    # Rounds: Boulware needs more rounds to converge, conceder fewer
    max_rounds =  20 #15 if "BOULWARE" in chosen_strategy else 10
    listings = []
    for model, years in models.items():
        for year_str, market_price in years.items():
            year   = int(year_str)
            markup = rng.uniform(MARKUP_MIN, MARKUP_MAX)
            asking = round(market_price * (1 + markup) / 1000) * 1000  # round to nearest 1000

            mlo, mhi = MILEAGE_BY_YEAR.get(year, (30_000, 80_000))
            mileage  = round(rng.randint(mlo, mhi) / 500) * 500

            listings.append({
                "make":        brand,
                "model":       model,
                "year":        year,
                "mileage":     mileage,
                "color":       rng.choice(CAR_COLORS),
                "retailPrice": float(asking),
                "marketPrice": float(market_price),
                "condition":   "New" if year >= 2023 else "Used",
                "description": (f"{year} {brand} {model}, {mileage:,} km. "
                                f"Market ref: RM {market_price:,}."),
            })
    return {
        "dealerName":    f"{brand}Dealer",
        "brand":         brand,
        "autoSubmit":    True,
        "autoNegotiate": auto_negotiate,
        "autoSelectBuyers": auto_negotiate,
        "strategy":      chosen_strategy,
        "marginPct":     chosen_margin,
        "maxRounds":     max_rounds,
        "listings":      listings,
    }


def make_random_buyer(idx: int, all_cars: list[tuple], rng: random.Random,
                      strategy_override: str | None = None) -> dict:
    """
    Generate a random buyer profile from the market data.

    Each buyer:
      - Picks a random car (brand/model) — 30% chance of being flexible on model
      - Gets a random personality (aggressive / moderate / easy)
      - Gets a budget derived from the market price
      - Gets a first offer derived from their budget
      - Gets a random strategy (or the override if specified)
    """
    brand, model, year, market_price = rng.choice(all_cars)

    personality_name = rng.choice(list(PERSONALITIES.keys()))
    p                = PERSONALITIES[personality_name]

    budget_factor = rng.uniform(*p["budget_factor"])
    max_price     = round(market_price * budget_factor / 500) * 500

    first_offer_pct = rng.uniform(*p["first_offer_pct"])
    first_offer     = round(max_price * first_offer_pct / 500) * 500

    # 30% of buyers are flexible on model (any model from that brand)
    flexible_model = rng.random() < 0.30

    # Random max mileage — some buyers don't care (0 = no limit)
    mileage_options = [0, 30_000, 50_000, 80_000, 100_000]
    max_mileage     = rng.choice(mileage_options)

    # Random year minimum — prefer newer cars
    year_min = rng.choice([0, 2018, 2019, 2020, 2021])

    # Strategy
    # Use the curated buyer strategy pool (weighted toward BAYESIAN)
    strategy = strategy_override or rng.choice(BUYER_STRATEGIES)

    # Max negotiation rounds — varied so we can see different dynamics
    max_rounds = rng.choice([10, 12, 15, 15, 20])  # longer = more observable

    # Generate a human-sounding name
    first = rng.choice(BUYER_NAMES_A)
    name  = f"{first}{rng.randint(1, 99):02d}"

    notes_templates = [
        f"Looking for a reliable {brand} for daily commuting.",
        f"Interested in a {brand} {model if not flexible_model else 'model'}, flexible on year.",
        f"Need a car under RM {max_price:,}. {p['description']}.",
        f"Comparing options — open to {brand} vehicles.",
        f"First car purchase, budget is strict at RM {max_price:,}.",
    ]

    return {
        "name":             name,
        "make":             brand,
        "model":            "" if flexible_model else model,
        "yearMin":          year_min,
        "yearMax":          0,
        "maxPrice":         float(max_price),
        "maxMileage":       max_mileage,
        "condition":        rng.choice(["Any", "Any", "Used", "New"]),
        "notes":            rng.choice(notes_templates),
        "firstOffer":       float(first_offer),
        "reservationPrice": float(max_price),
        "autoNegotiate":    True,
        "autoShortlist":    True,
        "shortlistLimit":   1,
        "shortlistDifferentDealers": True,
        "shortlistDelayMs": 1000,
        "strategy":         strategy,
        "maxRounds":        max_rounds,
        "personality":      personality_name,
        # Metadata is not sent to the Java agent.
        "_marketPrice":     float(market_price),
        "_flexibleModel":   flexible_model,
    }


# ─── Agent launching ──────────────────────────────────────────────────────────

def resolve_existing_path(path: str | None) -> str | None:
    """Resolve a path relative to the current folder OR this script's folder."""
    if not path:
        return None
    candidate = Path(path)
    candidates = [candidate]
    if not candidate.is_absolute():
        candidates.append(BASE_DIR / candidate)
    for c in candidates:
        if c.exists():
            return str(c.resolve())
    return None


def find_jar(hint: str | None) -> str:
    candidates = [
        hint,
        JAR_NAME,
        f"target/{JAR_NAME}",
        f"../target/{JAR_NAME}",
        str(BASE_DIR / JAR_NAME),
        str(BASE_DIR / "target" / JAR_NAME),
        str(BASE_DIR.parent / "target" / JAR_NAME),
    ]
    for candidate in candidates:
        resolved = resolve_existing_path(candidate)
        if resolved:
            return resolved

    raise SystemExit(
        f"JAR not found: expected {JAR_NAME}. "
        f"Script folder: {BASE_DIR}. "
        "Run from the Scripts folder or pass --jar C:/full/path/to/the.jar."
    )


def find_jade_jar() -> str | None:
    """Look for jade.jar relative to current folder or this script's folder."""
    candidates = [
        "lib/jade.jar",
        "../lib/jade.jar",
        "jade.jar",
        str(BASE_DIR / "lib" / "jade.jar"),
        str(BASE_DIR.parent / "lib" / "jade.jar"),
        str(BASE_DIR / "jade.jar"),
    ]
    for c in candidates:
        resolved = resolve_existing_path(c)
        if resolved:
            return resolved
    return None



def write_config(config: dict, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    name = config.get("dealerName") or config.get("name") or "agent"
    path = out_dir / f"{name}_config.json"
    with open(path, "w") as f:
        json.dump(config, f, indent=2)
    return path

def build_java_cmd(jar: str, extra_args: list[str]) -> list[str]:
    """
    Build the java command. Uses -cp instead of -jar when jade.jar needs
    to be added separately (i.e. when JADE wasn't bundled in the fat JAR).
    """
    jade_jar = find_jade_jar()
    if jade_jar:
        # jade.jar found separately — use -cp to combine both JARs
        sep = ";" if sys.platform == "win32" else ":"
        classpath = jar + sep + jade_jar
        return ["java", "-cp", classpath, "negotiation.Launcher"] + extra_args
    else:
        # Assume JADE is bundled inside the fat JAR
        return ["java", "-jar", jar] + extra_args


def launch_agent(jar: str, role: str, host: str, name: str,
                 config_path: str | None = None,
                 log_dir: str | None = None,
                 dry_run: bool = False) -> subprocess.Popen | None:
    extra = ["--headless", "--role", role.upper(), "--host", host, "--name", name]
    if config_path:
        extra += ["--config", config_path]
    if log_dir:
        extra += ["--log-dir", log_dir, "--log-name", name]

    cmd = build_java_cmd(jar, extra)
    log_path = str(Path(log_dir) / f"{name}.log") if log_dir else None

    log_step(f"Launching {role.upper()} agent '{name}'")
    log_step(f"  host: {host}:{JADE_PORT}")
    if config_path:
        log_step(f"  config: {config_path}")
    if log_path:
        log_step(f"  log: {log_path}")

    if dry_run:
        log_step(f"  dry command: {format_cmd(cmd)}")
        return None

    proc = subprocess.Popen(cmd)
    log_step(f"  started pid={proc.pid}")
    return proc


def launch_host(jar: str, show_jade_gui: bool, log_dir: str | None, dry_run: bool):
    extra = ["--headless", "--role", "HOST"]
    if show_jade_gui: extra += ["--gui"]
    if log_dir:
        extra += ["--log-dir", log_dir, "--log-name", "host"]
    cmd = build_java_cmd(jar, extra)
    log_path = str(Path(log_dir) / "host.log") if log_dir else None

    log_step("Launching JADE HOST")
    log_step("  JADE RMA GUI: " + ("enabled" if show_jade_gui else "disabled"))
    if log_path:
        log_step(f"  log: {log_path}")
    if dry_run:
        log_step(f"  dry command: {format_cmd(cmd)}")
        return None
    proc = subprocess.Popen(cmd)
    log_step(f"  started pid={proc.pid}")
    return proc


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Spawn Car Negotiation Platform agents from market data.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--host",             default=None,
                        help="JADE host IP. If omitted, auto-discovers on the local network.")
    parser.add_argument("--as-host",          action="store_true",
                        help="This machine is the host — start Main Container + Broker too")
    parser.add_argument("--data",             default=DATA_FILE)
    parser.add_argument("--jar",              default=None)
    parser.add_argument("--dealers-only",     action="store_true")
    parser.add_argument("--buyers-only",      action="store_true")
    parser.add_argument("--num-buyers",       type=int, default=5,
                        help="Number of random buyers to spawn (default: 5)")
    parser.add_argument("--buyer-strategy",   default=None,
                        choices=AVAILABLE_STRATEGIES + ["RANDOM"],
                        help="Force all buyers to use this strategy (default: random)")
    parser.add_argument("--dealer-strategy",  default="TIME_DEPENDENT_BOULWARE",
                        choices=AVAILABLE_STRATEGIES,
                        help="Strategy for all auto-dealers (default: TIME_DEPENDENT_BOULWARE)")
    parser.add_argument("--dealer-margin",    type=float, default=0.10,
                        help="Dealer minimum margin %% — won't go below (1-margin) x retail (default: 0.10)")
    parser.add_argument("--manual-dealers",   action="store_true",
                        help="Spawn dealers without auto-negotiate (manual mode)")
    parser.add_argument("--seed",             type=int, default=42,
                        help="Random seed for reproducible runs (default: 42)")
    parser.add_argument("--delay",            type=float, default=2.0,
                        help="Seconds between agent launches (default: 2.0)")
    parser.add_argument("--jade-gui",         action="store_true")
    parser.add_argument("--dry-run",          action="store_true")
    parser.add_argument("--config-dir",       default="agent_configs")
    parser.add_argument("--log-dir",          default="logs")
    args = parser.parse_args()

    log_step("Starting Car Negotiation Platform agent spawner")
    log_step(f"Script directory: {BASE_DIR}")
    log_step("Mode: " + ("dry run" if args.dry_run else "launch processes"))
    log_step("Agent Swing GUIs: disabled by headless launcher")

    jar       = find_jar(args.jar)
    data_path = resolve_existing_path(args.data) or str(BASE_DIR / args.data)
    data      = load_market_data(data_path)
    all_cars  = get_all_cars(data)
    out_dir   = Path(args.config_dir)
    if not out_dir.is_absolute():
        out_dir = BASE_DIR / out_dir
    log_dir   = Path(args.log_dir)
    if not log_dir.is_absolute():
        log_dir = BASE_DIR / log_dir
    rng      = random.Random(args.seed)
    procs    = []

    log_step(f"Application JAR: {jar}")
    jade_jar = find_jade_jar()
    log_step("JADE library: " + (jade_jar if jade_jar else "bundled in application JAR"))
    log_step(f"Market data: {data_path}")
    log_step(f"Loaded {len(data)} brands and {len(all_cars)} car price entries")
    log_step(f"Config output directory: {out_dir}")
    log_step(f"Agent log directory: {log_dir}")
    log_step(f"Random seed: {args.seed}")

    # ── Resolve host IP ───────────────────────────────────────────────────────
    host = args.host
    if host is None:
        log_step("Resolving JADE host address")
        if args.as_host:
            host = get_local_ip()
            log_step("Using this machine's local IP because --as-host is enabled")
        else:
            discovered_host = discover_host()
            if discovered_host:
                log_step(f"Discovered JADE host at {discovered_host}")
                host = discovered_host
            else:
                log_step("No JADE host discovered; falling back to 127.0.0.1")
                host = "127.0.0.1"
    else:
        log_step("Using host from --host")
    log_step(f"Agents will connect to JADE host {host}:{JADE_PORT}")

    strategy_override  = None if (args.buyer_strategy in (None, "RANDOM")) else args.buyer_strategy
    dealer_strategy    = args.dealer_strategy
    dealer_margin      = args.dealer_margin
    dealer_auto        = not args.manual_dealers

    # Initialize strategies from Java service (with fallback)
    initialize_strategies()

    # 1. Optional: start HOST
    if args.as_host:
        log_step("Starting HOST before launching agents")
        p = launch_host(jar, args.jade_gui, str(log_dir), args.dry_run)
        if p: procs.append(("HOST", p))
        if not args.dry_run:
            wait_seconds = args.delay * 3
            log_step(f"Waiting {wait_seconds:.1f}s for HOST startup")
            time.sleep(wait_seconds)
    else:
        log_step("Not starting HOST; expecting an existing JADE platform")

    # 2. Dealers — one per brand, fixed from JSON
    if not args.buyers_only:
        log_step(f"Preparing {len(data)} dealer agent(s)")
        for brand, models in data.items():
            config = make_dealer_config(
                brand, models, rng,
                auto_negotiate=dealer_auto,
                strategy=dealer_strategy,
                margin_pct=dealer_margin,
            )
            config_path = write_config(config, out_dir)
            log_step(f"Wrote dealer config for {brand}Dealer: {config_path}")
            p = launch_agent(jar, "DEALER", host,
                             name=f"{brand}Dealer",
                             config_path=str(config_path),
                             log_dir=str(log_dir),
                             dry_run=args.dry_run)
            if p: procs.append((f"{brand}Dealer", p))
            if not args.dry_run:
                log_step(f"Waiting {args.delay:.1f}s before launching the next agent")
                time.sleep(args.delay)
    else:
        log_step("Skipping dealer agents because --buyers-only was set")

    # 3. Buyers — randomly generated
    if not args.dealers_only:
        log_step(f"Preparing {args.num_buyers} buyer agent(s)")
        seen_names = set()

        for i in range(args.num_buyers):
            profile = make_random_buyer(i, all_cars, rng, strategy_override)

            # Ensure unique name
            base_name = profile["name"]
            while profile["name"] in seen_names:
                profile["name"] = f"{base_name}_{rng.randint(1, 999)}"
            seen_names.add(profile["name"])

            config_path = write_config(profile, out_dir)
            log_step(f"Wrote buyer config for {profile['name']}: {config_path}")

            p = launch_agent(jar, "BUYER", host,
                             name=profile["name"],
                             config_path=str(config_path),
                             log_dir=str(log_dir),
                             dry_run=args.dry_run)
            if p: procs.append((profile["name"], p))
            if not args.dry_run:
                log_step(f"Waiting {args.delay:.1f}s before launching the next agent")
                time.sleep(args.delay)
    else:
        log_step("Skipping buyer agents because --dealers-only was set")

    # Keep child processes attached so Ctrl+C can stop them together.
    if not args.dry_run:
        try:
            log_step(f"Launch sequence complete; monitoring {len(procs)} child process(es)")
            last_running = None
            while True:
                time.sleep(5)
                exited = [(n, p.returncode) for n, p in procs if p.poll() is not None]
                for name, code in exited:
                    log_step(f"Process exited: {name} returncode={code}")
                procs = [(n, p) for n, p in procs if p.poll() is None]
                running = tuple(n for n, _ in procs)
                if running != last_running:
                    log_step("Still running: " + (", ".join(running) if running else "none"))
                    last_running = running
                if not procs:
                    log_step("No child processes remain; spawner exiting")
                    break
        except KeyboardInterrupt:
            log_step("Ctrl+C received; terminating child processes")
            for name, proc in procs:
                log_step(f"Terminating {name} pid={proc.pid}")
                proc.terminate()
    else:
        log_step("Dry run complete; no Java processes were started")


if __name__ == "__main__":
    main()
