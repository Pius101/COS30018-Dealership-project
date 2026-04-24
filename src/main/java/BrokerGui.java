package negotiation.gui;

import negotiation.agents.BrokerAgent;
import negotiation.models.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Collection;
import java.util.List;

/**
 * The Broker Agent's GUI — the human operator's control panel.
 *
 * Layout (4 tabs):
 *   [Listings]     — all car listings from Dealer Agents
 *   [Buyers]       — all requirements from Buyer Agents
 *   [Assign]       — side-by-side view for manual pairing + Assign button
 *   [Negotiations] — live negotiation monitor with message history
 */
public class BrokerGui extends JFrame {

    private final BrokerAgent broker;

    // ── Listings tab ──────────────────────────────────────────────────────────
    private final DefaultTableModel listingsModel;
    private final JTable            listingsTable;

    // ── Buyers tab ────────────────────────────────────────────────────────────
    private final DefaultTableModel requirementsModel;
    private final JTable            requirementsTable;

    // ── Assign tab — the key V1 feature ──────────────────────────────────────
    private final DefaultTableModel assignListingsModel;
    private final JTable            assignListingsTable;
    private final DefaultTableModel assignBuyersModel;
    private final JTable            assignBuyersTable;
    private final JTextArea         brokerNoteField;
    private final JLabel            selectionStatus;
    // mirrors of the current selection for the Assign action
    private CarListing    selectedListing;
    private CarRequirement selectedRequirement;

    // ── Negotiations tab ──────────────────────────────────────────────────────
    private final DefaultListModel<String>  negListModel;
    private final JList<String>             negList;
    private final JTextArea                 negHistoryArea;
    private final DefaultTableModel         completedModel;
    // negotiationId → tab label for lookup
    private final Map<String, String>       negLabels = new LinkedHashMap<>();

    // ── Activity log (bottom of window) ──────────────────────────────────────
    private final JTextArea logArea = new JTextArea();

    /** Called by BrokerAgent.setup() to connect the AppLogger to this text area. */
    public JTextArea getLogArea() { return logArea; }

    private static final String[] LISTING_COLS    = {"ID", "Dealer", "Make", "Model", "Year", "Mileage (km)", "Price (RM)", "Condition"};
    private static final String[] REQUIREMENT_COLS = {"ID", "Buyer", "Make", "Model", "Year Min", "Year Max", "Max Price (RM)", "Condition"};
    private static final String[] COMPLETED_COLS  = {"Negotiation ID", "Dealer", "Buyer", "Car", "Final Price (RM)"};
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

    public BrokerGui(BrokerAgent broker) {
        super("Broker Agent (KA)  —  " + broker.getAID().getLocalName());
        this.broker = broker;

        // ── Table models ──────────────────────────────────────────────────────
        listingsModel      = makeReadOnlyModel(LISTING_COLS);
        listingsTable      = new JTable(listingsModel);
        requirementsModel  = makeReadOnlyModel(REQUIREMENT_COLS);
        requirementsTable  = new JTable(requirementsModel);
        assignListingsModel = makeReadOnlyModel(LISTING_COLS);
        assignListingsTable = new JTable(assignListingsModel);
        assignBuyersModel  = makeReadOnlyModel(REQUIREMENT_COLS);
        assignBuyersTable  = new JTable(assignBuyersModel);
        negListModel       = new DefaultListModel<>();
        negList            = new JList<>(negListModel);
        negHistoryArea     = new JTextArea();
        completedModel     = makeReadOnlyModel(COMPLETED_COLS);
        brokerNoteField    = new JTextArea(2, 30);
        selectionStatus    = new JLabel("No selection");

        applyLightTheme();
        buildUI();

        setSize(1000, 680);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // agent owns shutdown
        setLocationRelativeTo(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Light theme styling
    private void applyLightTheme() {
        // Define colorful light theme colors
        Color primaryColor = new Color(70, 130, 180);      // Steel blue
        Color accentColor = new Color(255, 140, 0);        // Dark orange
        Color successColor = new Color(34, 139, 34);      // Forest green
        Color warningColor = new Color(255, 165, 0);       // Orange
        Color headerColor = new Color(240, 248, 255);     // Alice blue
        Color borderColor = new Color(176, 196, 222);      // Light steel blue
        
        // Style tables
        styleTableWithTheme(listingsTable, primaryColor, headerColor);
        styleTableWithTheme(requirementsTable, primaryColor, headerColor);
        styleTableWithTheme(assignListingsTable, primaryColor, headerColor);
        styleTableWithTheme(assignBuyersTable, primaryColor, headerColor);
        
        // Style text areas
        brokerNoteField.setBackground(headerColor);
        brokerNoteField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        ));
        
        negHistoryArea.setBackground(headerColor);
        negHistoryArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        ));
        
        logArea.setBackground(new Color(248, 248, 248)); // Very light gray
        logArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        ));
        
        // Style labels
        selectionStatus.setForeground(accentColor);
    }
    
    private void styleTableWithTheme(JTable table, Color primaryColor, Color headerColor) {
        styleTable(table);
        table.getTableHeader().setBackground(primaryColor);
        table.getTableHeader().setForeground(Color.WHITE);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));
        table.setSelectionBackground(new Color(173, 216, 230)); // Light blue
        table.setSelectionForeground(Color.BLACK);
        table.setGridColor(new Color(176, 196, 222)); // Light steel blue
    }
    // ─────────────────────────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout());

        // ── JADE Tools toolbar ─────────────────────────────────────────────────
        // These launch built-in JADE agents for monitoring and testing.
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JLabel toolsLabel = new JLabel("JADE Tools: ");
        toolsLabel.setForeground(Color.GRAY);
        toolbar.add(toolsLabel);

        JButton rmaBtn = makeToolBtn("🖥  RMA Monitor",
            "Opens the JADE Remote Monitoring Agent — see all platform agents and containers",
            () -> broker.launchJadeRma());

        JButton sniffBtn = makeToolBtn("📡  Sniffer",
            "Opens the JADE Sniffer — live sequence diagram of all ACL messages between agents.\n"
            + "Drag agents into the sniff area to watch their messages.",
            () -> broker.launchSniffer());

        JButton dummyBtn = makeToolBtn("✉  DummyAgent",
            "Opens a JADE DummyAgent — manually compose and send ACL messages.\n"
            + "Useful for testing: set Ontology to e.g. LISTING_REGISTER and Content to JSON.",
            () -> broker.launchDummyAgent());

        toolbar.add(rmaBtn);
        toolbar.addSeparator(new Dimension(6, 0));
        toolbar.add(sniffBtn);
        toolbar.addSeparator(new Dimension(6, 0));
        toolbar.add(dummyBtn);

        toolbar.addSeparator(new Dimension(20, 0));

        // Show the JADE platform address so friends know what to connect to
        JLabel platformAddr = new JLabel("Platform: " + broker.getAID().getName()
                .replaceAll("broker@", "").replaceAll("/JADE", ""));
        platformAddr.setForeground(new Color(100, 200, 100));
        platformAddr.setFont(platformAddr.getFont().deriveFont(Font.BOLD, 11f));
        platformAddr.setToolTipText("Friends connect with:  java -cp car-negotiation.jar jade.Boot -container -host <ip> -gui");
        toolbar.add(platformAddr);

        add(toolbar, BorderLayout.NORTH);

        // ── Main content (tabs) ────────────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("📋  Listings",      buildListingsTab());
        tabs.addTab("👤  Buyers",         buildBuyersTab());
        tabs.addTab("🔗  Assign",         buildAssignTab());
        tabs.addTab("💬  Negotiations",  buildNegotiationsTab());

        // ── Activity log panel at the bottom ──────────────────────────────────
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setRows(5);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Activity Log"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logScroll);
        split.setResizeWeight(0.78);
        split.setDividerSize(4);
        add(split, BorderLayout.CENTER);
    }

    /** Creates a consistently-styled toolbar button. */
    private JButton makeToolBtn(String label, String tooltip, Runnable action) {
        JButton btn = new JButton(label);
        btn.setToolTipText("<html>" + tooltip.replace("\n", "<br>") + "</html>");
        btn.setFocusPainted(false);
        btn.addActionListener(e -> action.run());
        return btn;
    }

    // ── Tab: Listings ─────────────────────────────────────────────────────────

    private JPanel buildListingsTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        JLabel hdr = new JLabel("All car listings registered on the platform");
        hdr.setFont(hdr.getFont().deriveFont(Font.BOLD));
        p.add(hdr, BorderLayout.NORTH);
        styleTable(listingsTable);
        p.add(new JScrollPane(listingsTable), BorderLayout.CENTER);
        return p;
    }

    // ── Tab: Buyers ───────────────────────────────────────────────────────────

    private JPanel buildBuyersTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        JLabel hdr = new JLabel("All buyer requirements submitted to the platform");
        hdr.setFont(hdr.getFont().deriveFont(Font.BOLD));
        p.add(hdr, BorderLayout.NORTH);
        styleTable(requirementsTable);
        p.add(new JScrollPane(requirementsTable), BorderLayout.CENTER);
        return p;
    }

    // ── Tab: Assign ───────────────────────────────────────────────────────────

    private JPanel buildAssignTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Instructions
        JLabel instr = new JLabel(
            "<html><b>Manual Assignment</b> — Select one listing (top) and one buyer (bottom), " +
            "then click Assign.</html>");
        p.add(instr, BorderLayout.NORTH);

        // Split: listings on top, buyers on bottom
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.5);

        JPanel listingsPanel = new JPanel(new BorderLayout(4, 4));
        listingsPanel.setBorder(new TitledBorder("Available Listings"));
        styleTable(assignListingsTable);
        assignListingsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listingsPanel.add(new JScrollPane(assignListingsTable), BorderLayout.CENTER);
        split.setTopComponent(listingsPanel);

        JPanel buyersPanel = new JPanel(new BorderLayout(4, 4));
        buyersPanel.setBorder(new TitledBorder("Buyer Requirements"));
        styleTable(assignBuyersTable);
        assignBuyersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        buyersPanel.add(new JScrollPane(assignBuyersTable), BorderLayout.CENTER);
        split.setBottomComponent(buyersPanel);

        p.add(split, BorderLayout.CENTER);

        // Bottom action panel
        JPanel actionPanel = new JPanel(new BorderLayout(8, 4));
        actionPanel.setBorder(new TitledBorder("Broker Note (optional message to both parties)"));

        brokerNoteField.setLineWrap(true);
        brokerNoteField.setWrapStyleWord(true);
        actionPanel.add(new JScrollPane(brokerNoteField), BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        selectionStatus.setForeground(Color.GRAY);
        btnRow.add(selectionStatus);

        JButton assignBtn = new JButton("🔗  Assign Selected");
        assignBtn.setFont(assignBtn.getFont().deriveFont(Font.BOLD, 13f));
        assignBtn.addActionListener(e -> doAssign());
        btnRow.add(assignBtn);
        actionPanel.add(btnRow, BorderLayout.SOUTH);

        p.add(actionPanel, BorderLayout.SOUTH);

        // Track selection in both tables
        assignListingsTable.getSelectionModel().addListSelectionListener(e -> updateSelectionStatus());
        assignBuyersTable.getSelectionModel().addListSelectionListener(e -> updateSelectionStatus());

        return p;
    }

    // ── Tab: Negotiations ─────────────────────────────────────────────────────

    private JPanel buildNegotiationsTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Left: list of active negotiations
        JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
        leftPanel.setBorder(new TitledBorder("Active Negotiations"));
        leftPanel.setPreferredSize(new Dimension(220, 0));
        negList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        negList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { refreshHistoryForSelected(); }
        });
        leftPanel.add(new JScrollPane(negList), BorderLayout.CENTER);

        // Right: message history
        JPanel rightPanel = new JPanel(new BorderLayout(4, 4));
        rightPanel.setBorder(new TitledBorder("Message History"));
        negHistoryArea.setEditable(false);
        negHistoryArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        negHistoryArea.setLineWrap(true);
        negHistoryArea.setWrapStyleWord(true);
        rightPanel.add(new JScrollPane(negHistoryArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setDividerLocation(220);
        split.setResizeWeight(0.0);
        p.add(split, BorderLayout.CENTER);

        // Bottom: completed deals table
        JPanel donePanel = new JPanel(new BorderLayout(4, 4));
        donePanel.setBorder(new TitledBorder("Completed Deals"));
        donePanel.setPreferredSize(new Dimension(0, 130));
        JTable completedTable = new JTable(completedModel);
        styleTable(completedTable);
        donePanel.add(new JScrollPane(completedTable), BorderLayout.CENTER);
        p.add(donePanel, BorderLayout.SOUTH);

        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Action: Assign
    // ─────────────────────────────────────────────────────────────────────────

    private void doAssign() {
        int listingRow = assignListingsTable.getSelectedRow();
        int buyerRow   = assignBuyersTable.getSelectedRow();

        if (listingRow < 0 || buyerRow < 0) {
            JOptionPane.showMessageDialog(this,
                "Please select both a listing AND a buyer requirement before assigning.",
                "Nothing selected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Retrieve objects by the ID columns (col 0)
        String listingId  = (String) assignListingsModel.getValueAt(listingRow, 0);
        String reqId      = (String) assignBuyersModel.getValueAt(buyerRow, 0);

        CarListing     listing = findListingById(listingId);
        CarRequirement req     = findRequirementById(reqId);

        if (listing == null || req == null) {
            JOptionPane.showMessageDialog(this, "Selection data not found — please try again.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String note = brokerNoteField.getText().trim();

        int confirm = JOptionPane.showConfirmDialog(this,
            String.format("<html>Assign:<br><b>Listing:</b>  %s<br><b>Buyer:</b>  %s<br><br>Continue?</html>",
                    listing, req.getBuyerName()),
            "Confirm Assignment", JOptionPane.YES_NO_OPTION);

        if (confirm != JOptionPane.YES_OPTION) return;

        // Tell the broker agent to create the assignment and notify both parties
        broker.createAssignment(listing, req, note.isEmpty() ? null : note);
        brokerNoteField.setText("");
        selectionStatus.setText("Assignment sent ✓");
        selectionStatus.setForeground(new Color(80, 200, 80));
    }

    private void updateSelectionStatus() {
        int lr = assignListingsTable.getSelectedRow();
        int br = assignBuyersTable.getSelectedRow();
        if (lr >= 0 && br >= 0) {
            String listingId = (String) assignListingsModel.getValueAt(lr, 0);
            String buyerName = (String) assignBuyersModel.getValueAt(br, 1);
            selectionStatus.setText("Listing " + listingId + "  ↔  Buyer " + buyerName);
            selectionStatus.setForeground(new Color(120, 180, 255));
        } else {
            selectionStatus.setText("Select a listing and a buyer");
            selectionStatus.setForeground(Color.GRAY);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public refresh methods — called by BrokerAgent on the EDT
    // ─────────────────────────────────────────────────────────────────────────

    public void refreshListings(Collection<CarListing> listings) {
        listingsModel.setRowCount(0);
        assignListingsModel.setRowCount(0);
        for (CarListing l : listings) {
            Object[] row = {l.getListingId(), l.getDealerName(), l.getMake(), l.getModel(),
                            l.getYear(), l.getMileage(), String.format("%.0f", l.getRetailPrice()), l.getCondition()};
            listingsModel.addRow(row);
            assignListingsModel.addRow(row);
        }
    }

    public void refreshRequirements(Collection<CarRequirement> requirements) {
        requirementsModel.setRowCount(0);
        assignBuyersModel.setRowCount(0);
        for (CarRequirement r : requirements) {
            Object[] row = {r.getRequirementId(), r.getBuyerName(),
                    nvl(r.getMake()), nvl(r.getModel()),
                    r.getYearMin() > 0 ? r.getYearMin() : "—",
                    r.getYearMax() > 0 ? r.getYearMax() : "—",
                    r.getMaxPrice() > 0 ? String.format("%.0f", r.getMaxPrice()) : "—",
                    r.getCondition()};
            requirementsModel.addRow(row);
            assignBuyersModel.addRow(row);
        }
    }

    public void refreshAssignments(Collection<Assignment> assignments) {
        for (Assignment a : assignments) {
            String label = a.getNegotiationId() + "  [" + a.getDealerName()
                         + " ↔ " + a.getBuyerName() + "]";
            negLabels.put(a.getNegotiationId(), label);
            if (!negListModel.contains(label)) {
                negListModel.addElement(label);
            }
        }
    }

    public void onNegotiationMessage(NegotiationMessage msg) {
        // If this negotiation is currently selected, append the message
        String selected = negList.getSelectedValue();
        if (selected != null && selected.startsWith(msg.getNegotiationId())) {
            appendToHistory(msg);
        }
    }

    public void onDealComplete(NegotiationMessage finalMsg) {
        // Move from active list to completed table
        String label = negLabels.get(finalMsg.getNegotiationId());
        if (label != null) {
            negListModel.removeElement(label);
        }

        // Add to broker's assignment list to look up names
        Assignment a = broker.getAssignments().stream()
                .filter(x -> x.getNegotiationId().equals(finalMsg.getNegotiationId()))
                .findFirst().orElse(null);

        completedModel.addRow(new Object[]{
                finalMsg.getNegotiationId(),
                a != null ? a.getDealerName() : finalMsg.getFromName(),
                a != null ? a.getBuyerName()  : finalMsg.getToName(),
                a != null ? a.getListing().toString() : finalMsg.getListingDescription(),
                String.format("RM %.0f", finalMsg.getPrice())
        });
    }

    public void refreshNegotiations() {
        // Called when history changes — refresh if something is selected
        refreshHistoryForSelected();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshHistoryForSelected() {
        String selected = negList.getSelectedValue();
        if (selected == null) return;
        // Extract negotiation ID (first token before spaces)
        String negId = selected.split("\\s")[0];
        negHistoryArea.setText("");
        for (NegotiationMessage msg : broker.getHistory(negId)) {
            appendToHistory(msg);
        }
    }

    private void appendToHistory(NegotiationMessage msg) {
        String time    = SDF.format(new Date(msg.getTimestamp()));
        String role    = "[" + msg.getFromRole() + " " + msg.getFromName() + "]";
        String typeStr = msg.getType().name();
        String priceStr = msg.getPrice() > 0 ? "  RM " + String.format("%.0f", msg.getPrice()) : "";
        String note    = msg.getMessage() != null && !msg.getMessage().isBlank()
                         ? "\n  \"" + msg.getMessage() + "\"" : "";

        negHistoryArea.append(String.format("%s  %s  %s%s%s%n",
                time, role, typeStr, priceStr, note));
        // Auto-scroll to bottom
        negHistoryArea.setCaretPosition(negHistoryArea.getDocument().getLength());
    }

    private CarListing findListingById(String id) {
        return broker.getListings().stream()
                .filter(l -> l.getListingId().equals(id))
                .findFirst().orElse(null);
    }

    private CarRequirement findRequirementById(String id) {
        return broker.getRequirements().stream()
                .filter(r -> r.getRequirementId().equals(id))
                .findFirst().orElse(null);
    }

    private static DefaultTableModel makeReadOnlyModel(String[] cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private static void styleTable(JTable t) {
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        t.getTableHeader().setReorderingAllowed(false);
        t.setRowHeight(22);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 1));
    }

    private static String nvl(String s) {
        return (s == null || s.isBlank()) ? "Any" : s;
    }
}
