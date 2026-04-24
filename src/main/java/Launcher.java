package negotiation;

import com.formdev.flatlaf.FlatLightLaf;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import negotiation.network.NetworkDiscovery;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Application entry point — shows a startup dialog.
 *
 * ─── Roles ───────────────────────────────────────────────────────────────────
 *   HOST   → Main Container + BrokerAgent (KA).  Run ONCE on one machine.
 *   DEALER → Peripheral container + DealerAgent (DA).  Each dealer runs this.
 *   BUYER  → Peripheral container + BuyerAgent  (BA).  Each buyer runs this.
 *
 * ─── JADE agents created ─────────────────────────────────────────────────────
 *   This launcher uses the JADE programmatic API (jade.core.Runtime) to create
 *   real JADE containers and agents — identical to running jade.Boot from the
 *   command line, just without needing a terminal.
 *
 *   HOST creates:
 *     - A JADE Main Container (hosts the AMS, DF, and optionally the RMA GUI)
 *     - BrokerAgent named "broker" → registers with DF as "car-negotiation-broker"
 *
 *   DEALER / BUYER creates:
 *     - A JADE peripheral container (connects to the host's Main Container)
 *     - DealerAgent / BuyerAgent with the name you type in the dialog
 *     - These agents search the DF for "car-negotiation-broker" to find KA
 *
 * ─── Network (Searchy) ────────────────────────────────────────────────
 *   When HOST starts, it also starts a UDP listener on port 45678.
 *   When DEALER/BUYER clicks "Search", a UDP broadcast goes out and the
 *   host replies with its IP — no manual IP typing needed on the same WiFi.
 *   If Search fails (firewall / different subnet), type the IP manually.
 *
 * ─── Build and distribute ────────────────────────────────────────────────────
 *   mvn package  →  target/car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
 *   Share the JAR with groupmates.  They run:
 *     java -jar car-negotiation-1.0-SNAPSHOT-jar-with-dependencies.jar
 */
public class Launcher {

    // Shared containers for grouping agents
    private static AgentContainer buyersContainer = null;
    private static AgentContainer sellersContainer = null;
    private static final String BUYERS_CONTAINER_NAME = "buyers-market";
    private static final String SELLERS_CONTAINER_NAME = "sellers-market";

    public static void main(String[] args) {
        FlatLightLaf.setup();
        UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 13));
        SwingUtilities.invokeLater(Launcher::showDialog);
    }

    private static void showDialog() {
        JDialog dlg = new JDialog((Frame) null, "Car Negotiation Platform", true);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dlg.setSize(490, 490);
        dlg.setLocationRelativeTo(null);
        dlg.setResizable(false);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(22, 28, 22, 28));

        // ── Header ────────────────────────────────────────────────────────────
        JLabel title = new JLabel("🚗  Car Negotiation Platform", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 19));
        JLabel sub = new JLabel("COS30018 — Intelligent Systems  |  V1", SwingConstants.CENTER);
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sub.setForeground(Color.GRAY);
        JPanel hdr = new JPanel(new GridLayout(2, 1, 0, 4));
        hdr.add(title); hdr.add(sub);
        root.add(hdr, BorderLayout.NORTH);

        // ── Form ──────────────────────────────────────────────────────────────
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(7, 4, 7, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill   = GridBagConstraints.HORIZONTAL;

        ButtonGroup rg = new ButtonGroup();
        JRadioButton hostBtn   = new JRadioButton("HOST — start the platform (run once, on your PC)");
        JRadioButton dealerBtn = new JRadioButton("DEALER — join as a car dealer");
        JRadioButton buyerBtn  = new JRadioButton("BUYER  — join as a car buyer");
        rg.add(hostBtn); rg.add(dealerBtn); rg.add(buyerBtn);
        hostBtn.setSelected(true);

        g.gridx = 0; g.gridy = 0; g.gridwidth = 3; form.add(hostBtn,   g);
        g.gridy = 1;                                 form.add(dealerBtn, g);
        g.gridy = 2;                                 form.add(buyerBtn,  g);
        g.gridy = 3;                                 form.add(new JSeparator(), g);

        // Host IP row
        g.gridwidth = 1;
        JLabel    ipLbl      = new JLabel("Host IP:");
        JTextField ipFld     = new JTextField("", 14);
        JButton   discoverBtn = new JButton("🔍 Search");
        ipFld.setToolTipText("The HOST machine's IPv4 address — or use Search");
        discoverBtn.setToolTipText("Broadcast on WiFi to find the host automatically");
        g.gridx = 0; g.gridy = 4; g.fill = GridBagConstraints.NONE;       form.add(ipLbl,       g);
        g.gridx = 1;              g.fill = GridBagConstraints.HORIZONTAL;  form.add(ipFld,       g);
        g.gridx = 2;              g.fill = GridBagConstraints.NONE;        form.add(discoverBtn, g);

        // Agent name row
        JLabel    nameLbl = new JLabel("Agent Name:");
        JTextField nameFld = new JTextField("", 14);
        nameFld.setToolTipText("Unique name for your agent, e.g. Alice or Dealer1 (letters/digits/_ only)");
        g.gridx = 0; g.gridy = 5; g.fill = GridBagConstraints.NONE;       form.add(nameLbl, g);
        g.gridx = 1; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL; form.add(nameFld, g);
        g.gridwidth = 1;

        g.gridx = 0; g.gridy = 6; g.gridwidth = 3; form.add(new JSeparator(), g);

        // HOST-only options
        JCheckBox showJadeGui = new JCheckBox("Show JADE RMA GUI (lets you inspect all agents)", false);
        showJadeGui.setToolTipText("Opens the JADE Remote Monitoring window — useful for debugging");
        g.gridy = 7; form.add(showJadeGui, g);

        // Show this machine's IP so HOST can share it
        String myIp = NetworkDiscovery.getLocalIP();
        JLabel yourIpLbl = new JLabel("Your IP (share with groupmates):  " + myIp);
        yourIpLbl.setForeground(new Color(100, 200, 100));
        yourIpLbl.setFont(yourIpLbl.getFont().deriveFont(Font.BOLD, 12f));
        g.gridy = 8; form.add(yourIpLbl, g);

        root.add(form, BorderLayout.CENTER);

        // ── Field toggle ──────────────────────────────────────────────────────
        Runnable toggle = () -> {
            boolean isHost = hostBtn.isSelected();
            ipLbl.setEnabled(!isHost);      ipFld.setEnabled(!isHost);
            discoverBtn.setEnabled(!isHost); nameLbl.setEnabled(!isHost);
            nameFld.setEnabled(!isHost);    showJadeGui.setEnabled(isHost);
        };
        toggle.run();
        hostBtn.addActionListener(e -> toggle.run());
        dealerBtn.addActionListener(e -> toggle.run());
        buyerBtn.addActionListener(e -> toggle.run());

        // ── Search ─────────────────────────────────────────────────────
        discoverBtn.addActionListener(e -> {
            discoverBtn.setText("Searching…");
            discoverBtn.setEnabled(false);
            new Thread(() -> {
                String found = NetworkDiscovery.discoverHost();
                SwingUtilities.invokeLater(() -> {
                    discoverBtn.setText("🔍 Search");
                    discoverBtn.setEnabled(true);
                    if (found != null) {
                        ipFld.setText(found);
                        ipFld.setForeground(new Color(80, 200, 80));
                    } else {
                        JOptionPane.showMessageDialog(dlg,
                            "<html>No host found on the network.<br><br>"
                            + "Check that:<br>"
                            + "• The HOST has already launched the platform<br>"
                            + "• You are on the same WiFi network as the host<br>"
                            + "• Windows firewall allows UDP on port 45678<br><br>"
                            + "You can also type the host IP manually.<br>"
                            + "Host IP hint: run <b>ipconfig</b> on the host machine.</html>",
                            "Host Not Found", JOptionPane.WARNING_MESSAGE);
                        ipFld.setForeground(UIManager.getColor("TextField.foreground"));
                    }
                });
            }, "DiscoverThread").start();
        });

        // ── Launch button ─────────────────────────────────────────────────────
        JButton launchBtn = new JButton("  LAUNCH  ");
        launchBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        launchBtn.setPreferredSize(new Dimension(160, 40));

        launchBtn.addActionListener(e -> {
            try {
                if (hostBtn.isSelected()) {
                    launchHost(showJadeGui.isSelected());
                    dlg.dispose();
                } else {
                    String ip   = ipFld.getText().trim();
                    String name = nameFld.getText().trim();
                    if (ip.isEmpty()) {
                        warn(dlg, "Enter the Host IP or click Search."); return;
                    }
                    if (name.isEmpty()) {
                        warn(dlg, "Enter your Agent Name."); return;
                    }
                    if (!name.matches("[A-Za-z0-9_-]+")) {
                        warn(dlg, "Agent name may only contain letters, digits, - and _"); return;
                    }
                    if (dealerBtn.isSelected()) launchDealer(ip, name);
                    else                        launchBuyer(ip, name);
                    dlg.dispose();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg,
                    "Launch failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnRow.add(launchBtn);
        root.add(btnRow, BorderLayout.SOUTH);

        dlg.add(root);
        dlg.setVisible(true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch implementations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * HOST: creates the JADE Main Container and the Broker Agent.
     *
     * JADE system agents created automatically by the framework:
     *   "ams"  — Agent Management System (AID registry, lifecycle)
     *   "df"   — Directory Facilitator (yellow pages / service lookup)
     *   "rma"  — Remote Monitoring Agent (GUI, only if showJadeGui=true)
     *
     * Our agent:
     *   "broker" — BrokerAgent (KA): registers "car-negotiation-broker" in DF
     *
     * Also starts UDP discovery listener on port 45678.
     */
    private static void launchHost(boolean showJadeGui) throws Exception {
        Runtime rt = Runtime.instance();
        Profile p  = new ProfileImpl();
        p.setParameter(Profile.MAIN, "true");
        p.setParameter(Profile.GUI, showJadeGui ? "true" : "false");
        // Bind to WiFi/hotspot IP instead of VirtualBox
        String localIp = NetworkDiscovery.getLocalIP();
        p.setParameter(Profile.LOCAL_HOST, localIp);

        AgentContainer mc = rt.createMainContainer(p);
        AgentController broker = mc.createNewAgent(
                "broker", "negotiation.agents.BrokerAgent", new Object[0]);
        broker.start();

        // UDP listener so groupmates can Search this machine
        NetworkDiscovery.startHostListener(clientIp ->
            System.out.println("[Discovery] Peer connected from " + clientIp));

        System.out.println();
        System.out.println("════════════════════════════════════════════════");
        System.out.println("  HOST is running — Broker Agent (KA) started");
        System.out.println("  JADE platform : " + localIp + ":1099/JADE");
        System.out.println("  Your IP       : " + localIp);
        System.out.println("  Share the IP above OR let groupmates Search");
        System.out.println("════════════════════════════════════════════════");
        System.out.println();
    }

    /**
     * DEALER: joins or creates the shared sellers container + DealerAgent at hostIp:1099.
     * DealerAgent.setup() searches the DF for "car-negotiation-broker" to find KA.
     */
    private static void launchDealer(String hostIp, String name) throws Exception {
        if (sellersContainer == null) {
            System.out.println("[Launcher] Creating sellers container...");
            sellersContainer = joinPlatform(hostIp, SELLERS_CONTAINER_NAME);
            if (sellersContainer == null) {
                throw new Exception("Failed to create sellers container - check host IP and network connection");
            }
            System.out.println("[Launcher] Sellers container '" + SELLERS_CONTAINER_NAME + "' created at " + hostIp);
        }
        sellersContainer.createNewAgent(name, "negotiation.agents.DealerAgent", new Object[0]).start();
        System.out.println("[Launcher] Dealer agent '" + name + "' joined sellers container at " + hostIp);
    }

    /**
     * BUYER: joins or creates the shared buyers container + BuyerAgent at hostIp:1099.
     */
    private static void launchBuyer(String hostIp, String name) throws Exception {
        if (buyersContainer == null) {
            System.out.println("[Launcher] Creating buyers container...");
            buyersContainer = joinPlatform(hostIp, BUYERS_CONTAINER_NAME);
            if (buyersContainer == null) {
                throw new Exception("Failed to create buyers container - check host IP and network connection");
            }
            System.out.println("[Launcher] Buyers container '" + BUYERS_CONTAINER_NAME + "' created at " + hostIp);
        }
        buyersContainer.createNewAgent(name, "negotiation.agents.BuyerAgent", new Object[0]).start();
        System.out.println("[Launcher] Buyer agent '" + name + "' joined buyers container at " + hostIp);
    }

    /**
     * Creates a peripheral JADE container connected to hostIp:1099 with a specific name.
     * Once connected, agents in this container are fully registered in the AMS
     * and can message any other agent on the platform.
     */
    private static AgentContainer joinPlatform(String hostIp, String containerName) throws Exception {
        try {
            Runtime rt = Runtime.instance();
            Profile p  = new ProfileImpl();
            p.setParameter(Profile.MAIN, "false");
            p.setParameter(Profile.MAIN_HOST, hostIp);
            p.setParameter(Profile.MAIN_PORT, "1099");
            p.setParameter(Profile.CONTAINER_NAME, containerName);
            // Use local machine's IP for binding (prefers WiFi/hotspot over VirtualBox)
            String localIp = NetworkDiscovery.getLocalIP();
            p.setParameter(Profile.LOCAL_HOST, localIp);
            System.out.println("[Launcher] Connecting to JADE platform at " + hostIp + ":1099...");
            System.out.println("[Launcher] Binding to local IP: " + localIp);
            AgentContainer container = rt.createAgentContainer(p);
            if (container == null) {
                throw new Exception("JADE createAgentContainer returned null - connection failed");
            }
            System.out.println("[Launcher] Successfully connected to JADE platform");
            return container;
        } catch (Exception e) {
            System.err.println("[Launcher] ERROR connecting to JADE platform: " + e.getMessage());
            System.err.println("[Launcher] Make sure:");
            System.err.println("  1. HOST is running (someone selected HOST in the launcher)");
            System.err.println("  2. Host IP is correct: " + hostIp);
            System.err.println("  3. Port 1099 is not blocked by firewall");
            System.err.println("  4. Both machines are on the same network");
            e.printStackTrace();
            throw e;
        }
    }

    private static void warn(JDialog parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Missing Info", JOptionPane.WARNING_MESSAGE);
    }
}
