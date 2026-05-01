package negotiation.gui;

import negotiation.agents.DealerAgent;
import negotiation.models.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * The Dealer Agent's GUI - two tabs plus dynamic negotiation tabs.
 *
 *   [My Listings]       - form to add listings + table of submitted listings
 *   [Assignments]       - table of broker-assigned negotiations
 *   [Negotiation: <id>] - one tab per active negotiation (chat interface)
 *                         opened automatically when an assignment arrives
 */
public class DealerGui extends JFrame {
    private final DealerAgent dealer;
    private final JTabbedPane tabs;

    // My Listings tab
    private final DefaultTableModel listingsModel;

    // Activity log
    private final JTextArea logArea = new JTextArea();
    public JTextArea getLogArea() { return logArea; }

    // Assignments tab
    private final DefaultTableModel assignmentsModel;

    // Per-negotiation panels: negotiationId -> NegotiationPanel
    private final Map<String, NegotiationPanel> negPanels = new LinkedHashMap<>();

    private static final String[] LISTING_COLS    = {"ID", "Make", "Model", "Year", "Mileage (km)", "Price (RM)", "Condition"};
    private static final String[] ASSIGNMENT_COLS = {"Negotiation ID", "Buyer", "Listing", "Buyer's Max Price (RM)", "Status"};

    public DealerGui(DealerAgent dealer) {
        super("Dealer  -  " + dealer.getLocalName());
        this.dealer = dealer;

        listingsModel    = makeReadOnlyModel(LISTING_COLS);
        assignmentsModel = makeReadOnlyModel(ASSIGNMENT_COLS);
        tabs             = new JTabbedPane();

        tabs.addTab("  My Listings",  buildListingsTab());
        tabs.addTab("  Assignments",  buildAssignmentsTab());

        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setRows(4);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Activity Log"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logScroll);
        split.setResizeWeight(0.80);
        split.setDividerSize(4);
        add(split);

        setSize(820, 600);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);

        applyLightTheme();
    }

    // Light theme styling
    private void applyLightTheme() {
        // Define colorful light theme colors
        Color primaryColor = new Color(70, 130, 180);      // Steel blue
        Color accentColor = new Color(255, 140, 0);        // Dark orange
        Color successColor = new Color(34, 139, 34);      // Forest green
        Color headerColor = new Color(240, 248, 255);     // Alice blue
        Color borderColor = new Color(176, 196, 222);      // Light steel blue

        // Style log area
        logArea.setBackground(new Color(248, 248, 248)); // Very light gray
        logArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
        ));
    }

    // Tab: My Listings
    private JPanel buildListingsTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Input form
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new TitledBorder("Add New Listing"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill   = GridBagConstraints.HORIZONTAL;

        JTextField makeField        = new JTextField(12);
        JTextField modelField       = new JTextField(12);
        JTextField yearField        = new JTextField(6);
        JTextField mileageField     = new JTextField(8);
        JTextField colorField       = new JTextField(10);
        JTextField priceField       = new JTextField(10);
        JComboBox<String> condBox   = new JComboBox<>(new String[]{"New", "Used"});
        JTextArea  descArea         = new JTextArea(2, 30);
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);

        Object[][] fields = {
                {"Make *", makeField},   {"Model *", modelField},
                {"Year *", yearField},   {"Mileage (km)", mileageField},
                {"Color", colorField},   {"Retail Price (RM) *", priceField},
                {"Condition", condBox},  {"Description", new JScrollPane(descArea)}
        };

        int row = 0;
        for (int i = 0; i < fields.length; i += 2) {
            gbc.gridy = row;
            gbc.gridx = 0; gbc.weightx = 0;
            form.add(new JLabel(fields[i][0].toString()), gbc);
            gbc.gridx = 1; gbc.weightx = 1;
            form.add((Component) fields[i][1], gbc);
            gbc.gridx = 2; gbc.weightx = 0;
            form.add(new JLabel(fields[i + 1][0].toString()), gbc);
            gbc.gridx = 3; gbc.weightx = 1;
            form.add((Component) fields[i + 1][1], gbc);
            row++;
        }

        JButton submitBtn = new JButton("Submit Listing to Broker");
        submitBtn.setFont(submitBtn.getFont().deriveFont(Font.BOLD));
        submitBtn.addActionListener(e -> {
            try {
                String make  = makeField.getText().trim();
                String model = modelField.getText().trim();
                int    year  = Integer.parseInt(yearField.getText().trim());
                int    miles = mileageField.getText().isBlank() ? 0
                        : Integer.parseInt(mileageField.getText().trim());
                String color = colorField.getText().trim();
                double price = Double.parseDouble(priceField.getText().trim());
                String cond  = (String) condBox.getSelectedItem();
                String desc  = descArea.getText().trim();

                if (make.isEmpty() || model.isEmpty()) {
                    showError("Make and Model are required.");
                    return;
                }

                CarListing listing = new CarListing(make, model, year, miles, color, price, cond, desc);
                dealer.submitListing(listing);

                // Clear form
                makeField.setText(""); modelField.setText(""); yearField.setText("");
                mileageField.setText(""); colorField.setText(""); priceField.setText("");
                descArea.setText("");
                condBox.setSelectedIndex(0);
            } catch (NumberFormatException ex) {
                showError("Year and Price must be valid numbers.");
            }
        });

        gbc.gridy = row; gbc.gridx = 0; gbc.gridwidth = 4;
        gbc.anchor = GridBagConstraints.CENTER; gbc.fill = GridBagConstraints.NONE;
        form.add(submitBtn, gbc);

        // Listings table
        JTable table = new JTable(listingsModel);
        styleTable(table);
        JPanel tablePanel = new JPanel(new BorderLayout(4, 4));
        tablePanel.setBorder(new TitledBorder("Submitted Listings"));
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);

        p.add(form, BorderLayout.NORTH);
        p.add(tablePanel, BorderLayout.CENTER);
        return p;
    }

    // Tab: Assignments
    private JPanel buildAssignmentsTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── Auto-negotiate toggle ─────────────────────────────────────────────
        JPanel autoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        autoPanel.setBorder(BorderFactory.createTitledBorder("Auto-Negotiate Mode"));

        JToggleButton autoToggle = new JToggleButton("🤖  Auto-Negotiate: OFF");
        autoToggle.setToolTipText("When ON, all new assignments are handled automatically by the strategy");

        String[] strategyNames = negotiation.strategy.StrategyRegistry.getDisplayNames();
        String[] strategyKeys  = negotiation.strategy.StrategyRegistry.getKeys().toArray(new String[0]);
        JComboBox<String> strategyBox = new JComboBox<>(strategyNames);
        strategyBox.setSelectedIndex(0);

        JLabel marginLabel = new JLabel("Min margin (%):");
        JSpinner marginSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 30, 1));
        marginSpinner.setPreferredSize(new Dimension(60, 26));
        marginSpinner.setToolTipText("Dealer won't go below (100 - margin)% of retail price");

        autoPanel.add(autoToggle);
        autoPanel.add(new JLabel("Strategy:"));
        autoPanel.add(strategyBox);
        autoPanel.add(marginLabel);
        autoPanel.add(marginSpinner);

        autoToggle.addActionListener(e -> {
            boolean on = autoToggle.isSelected();
            autoToggle.setText(on ? "🤖  Auto-Negotiate: ON" : "🤖  Auto-Negotiate: OFF");
            String key       = strategyKeys[strategyBox.getSelectedIndex()];
            double marginPct = ((Integer) marginSpinner.getValue()) / 100.0;
            dealer.setAutoMode(on, key, marginPct);
        });

        JLabel infoLabel = new JLabel(
                "<html>Negotiations the Broker has assigned to you.</html>");

        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.add(autoPanel, BorderLayout.NORTH);
        north.add(infoLabel, BorderLayout.SOUTH);
        p.add(north, BorderLayout.NORTH);

        JTable table = new JTable(assignmentsModel);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    // Dynamic Negotiation Tab (one per assignment)
    /**
     * Called on the EDT by DealerAgent.onAssignment().
     * Opens a new negotiation tab for the given assignment.
     */
    public void openNegotiationTab(Assignment a) {
        // Add row to Assignments tab
        assignmentsModel.addRow(new Object[]{
                a.getNegotiationId(), a.getBuyerName(),
                a.getListing().toString(),
                a.getRequirement().getMaxPrice() > 0
                        ? String.format("%.0f", a.getRequirement().getMaxPrice()) : "",
                "Active"
        });

        // Build and add the negotiation chat panel
        NegotiationPanel panel = new NegotiationPanel(a, dealer);
        negPanels.put(a.getNegotiationId(), panel);
        String tabTitle = "  " + a.getBuyerName();
        tabs.addTab(tabTitle, panel);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);

        // Broker note banner
        if (a.getBrokerNote() != null && !a.getBrokerNote().isBlank()) {
            panel.appendSystem("[BROKER NOTE]  " + a.getBrokerNote());
        }
    }

    /** Called on the EDT when a negotiation message arrives from the buyer via KA. */
    public void appendNegotiationMessage(NegotiationMessage msg) {
        NegotiationPanel panel = negPanels.get(msg.getNegotiationId());
        if (panel != null) panel.appendMessage(msg);
        // Switch to that tab so dealer sees it
        int idx = indexOfTab("  " + msg.getFromName());
        if (idx >= 0) tabs.setSelectedIndex(idx);
    }

    /** Called on the EDT when a deal is completed. */
    public void onDealComplete(NegotiationMessage finalMsg) {
        NegotiationPanel panel = negPanels.get(finalMsg.getNegotiationId());
        if (panel != null) {
            panel.appendSystem(String.format("  DEAL COMPLETE - Final price: RM %.0f", finalMsg.getPrice()));
            panel.disableInput();
        }
        // Update Assignments table status
        updateAssignmentStatus(finalMsg.getNegotiationId(), "  Complete");
    }

    /**
     * Shows auto-negotiate banner and locks manual input.
     * Called by DealerNegotiationBehaviour on start.
     */
    public void showAutoModeBanner(String negotiationId, String strategyName,
                                   double startingPrice, double minPrice, int maxRounds) {
        NegotiationPanel panel = negPanels.get(negotiationId);
        if (panel == null) return;
        panel.appendSystem(String.format(
                "🤖 AUTO-NEGOTIATE ON  |  Strategy: %s  |  Starting: RM %.0f"
                        + "  |  Minimum: RM %.0f  |  Max rounds: %d",
                strategyName, startingPrice, minPrice, maxRounds));
        panel.disableInput();
    }

    /**
     * Appends a strategy reasoning line (🧠) to the negotiation chat tab.
     * Called by DealerNegotiationBehaviour after each decision.
     */
    public void appendAutoReasoning(String negotiationId, String reasoning) {
        NegotiationPanel panel = negPanels.get(negotiationId);
        if (panel != null) panel.appendReasoning(reasoning);
    }

    // Refresh methods
    public void refreshMyListings(List<CarListing> listings) {
        listingsModel.setRowCount(0);
        for (CarListing l : listings) {
            listingsModel.addRow(new Object[]{
                    l.getListingId(), l.getMake(), l.getModel(), l.getYear(),
                    l.getMileage(), String.format("%.0f", l.getRetailPrice()), l.getCondition()
            });
        }
    }

    // Helpers
    private void updateAssignmentStatus(String negotiationId, String status) {
        for (int i = 0; i < assignmentsModel.getRowCount(); i++) {
            if (negotiationId.equals(assignmentsModel.getValueAt(i, 0))) {
                assignmentsModel.setValueAt(status, i, 4);
                return;
            }
        }
    }

    private int indexOfTab(String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (title.equals(tabs.getTitleAt(i))) return i;
        }
        return -1;
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Input Error", JOptionPane.WARNING_MESSAGE);
    }

    private static DefaultTableModel makeReadOnlyModel(String[] cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private static void styleTable(JTable t) {
        t.setRowHeight(22);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.getTableHeader().setReorderingAllowed(false);
    }

    // Inner class: NegotiationPanel - the chat interface for one negotiation
    private static class NegotiationPanel extends JPanel {
        private final Assignment   assignment;
        private final DealerAgent  dealer;
        private final JTextArea    historyArea;
        private final JTextField   priceField;
        private final JTextArea    messageField;
        private final JButton      offerBtn;
        private final JButton      acceptBtn;
        private final JButton      rejectBtn;
        private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

        NegotiationPanel(Assignment a, DealerAgent dealer) {
            super(new BorderLayout(6, 6));
            this.assignment = a;
            this.dealer     = dealer;
            setBorder(new EmptyBorder(10, 10, 10, 10));

            // Info banner
            JLabel info = new JLabel(
                    "<html><b>Car:</b>  " + a.getListing() + "<br>" +
                            "<b>Buyer:</b>  " + a.getBuyerName() +
                            "   <b>Budget:</b>  " +
                            (a.getRequirement().getMaxPrice() > 0
                                    ? "RM " + String.format("%.0f", a.getRequirement().getMaxPrice())
                                    : "not disclosed") +
                            "   <b>Asking Price:</b>  RM " +
                            String.format("%.0f", a.getListing().getRetailPrice()) +
                            "</html>");
            info.setBorder(new EmptyBorder(0, 0, 6, 0));
            add(info, BorderLayout.NORTH);

            // Chat history
            historyArea = new JTextArea();
            historyArea.setEditable(false);
            historyArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            historyArea.setLineWrap(true);
            historyArea.setWrapStyleWord(true);
            add(new JScrollPane(historyArea), BorderLayout.CENTER);

            // Input panel
            JPanel input = new JPanel(new BorderLayout(6, 4));
            input.setBorder(new TitledBorder("Your Response"));

            JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            topRow.add(new JLabel("Offer Price (RM):"));
            priceField = new JTextField(
                    String.format("%.0f", a.getListing().getRetailPrice()), 10);
            topRow.add(priceField);
            input.add(topRow, BorderLayout.NORTH);

            messageField = new JTextArea(2, 40);
            messageField.setLineWrap(true);
            messageField.setWrapStyleWord(true);
            messageField.setBorder(BorderFactory.createTitledBorder("Optional message"));
            input.add(new JScrollPane(messageField), BorderLayout.CENTER);

            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            offerBtn  = new JButton(" Send Offer");
            acceptBtn = new JButton(" Accept");
            rejectBtn = new JButton(" Reject");
            offerBtn .setForeground(new Color(100, 160, 255));
            acceptBtn.setForeground(new Color(80,  200, 80));
            rejectBtn.setForeground(new Color(220, 80,  80));
            btnRow.add(offerBtn);
            btnRow.add(acceptBtn);
            btnRow.add(rejectBtn);
            input.add(btnRow, BorderLayout.SOUTH);
            add(input, BorderLayout.SOUTH);

            // Button actions
            offerBtn.addActionListener(e -> {
                try {
                    double price = Double.parseDouble(priceField.getText().trim());
                    String note  = messageField.getText().trim();
                    dealer.sendOffer(assignment.getNegotiationId(), price, note);
                    appendSent("OFFER", price, note);
                    messageField.setText("");
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Enter a valid price.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            });

            acceptBtn.addActionListener(e -> {
                double price = Double.parseDouble(priceField.getText().trim());
                String note  = messageField.getText().trim();
                int confirm  = JOptionPane.showConfirmDialog(this,
                        String.format("Accept deal at RM %.0f?", price),
                        "Confirm Accept", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    dealer.acceptOffer(assignment.getNegotiationId(), price, note);
                    appendSent("ACCEPT", price, note);
                    disableInput();
                }
            });

            rejectBtn.addActionListener(e -> {
                int confirm = JOptionPane.showConfirmDialog(this,
                        "Reject this negotiation? This ends the session.",
                        "Confirm Reject", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    String note = messageField.getText().trim();
                    dealer.rejectOffer(assignment.getNegotiationId(), note);
                    appendSent("REJECT", 0, note);
                    disableInput();
                }
            });
        }

        void appendMessage(NegotiationMessage msg) {
            String time = SDF.format(new Date(msg.getTimestamp()));
            String price = msg.getPrice() > 0
                    ? "  RM " + String.format("%.0f", msg.getPrice()) : "";
            String note = msg.getMessage() != null && !msg.getMessage().isBlank()
                    ? "\n  \"" + msg.getMessage() + "\"" : "";
            historyArea.append(String.format(
                    "[%s] %s (%s)  %s%s%s%n",
                    time, msg.getFromName(), msg.getFromRole(), msg.getType().name(), price, note));
            historyArea.setCaretPosition(historyArea.getDocument().getLength());

            if (msg.getType() == NegotiationMessage.Type.REJECT) {
                appendSystem("Buyer ended the negotiation.");
                disableInput();
            }
        }

        void appendSent(String type, double price, String note) {
            String time = SDF.format(new Date());
            String priceStr = price > 0 ? "  RM " + String.format("%.0f", price) : "";
            String noteStr  = note != null && !note.isBlank() ? "\n  \"" + note + "\"" : "";
            historyArea.append(String.format(
                    "[%s] YOU (DEALER)  %s%s%s%n", time, type, priceStr, noteStr));
        }

        void appendSystem(String text) {
            historyArea.append("--- " + text + " ---\n");
        }

        /** Strategy reasoning line — shown with 🧠 prefix in auto mode. */
        void appendReasoning(String text) {
            historyArea.append("🧠 " + text + "\n");
            historyArea.setCaretPosition(historyArea.getDocument().getLength());
        }

        void disableInput() {
            priceField.setEnabled(false);
            messageField.setEnabled(false);
            offerBtn.setEnabled(false);
            acceptBtn.setEnabled(false);
            rejectBtn.setEnabled(false);
        }
    }
}