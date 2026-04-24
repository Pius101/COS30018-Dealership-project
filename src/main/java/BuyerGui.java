package negotiation.gui;

import negotiation.agents.BuyerAgent;
import negotiation.models.Assignment;
import negotiation.models.CarRequirement;
import negotiation.models.NegotiationMessage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * The Buyer Agent's GUI — mirror of DealerGui but from the buyer's perspective.
 *
 *   [My Requirements]   — form to submit car requirements + table of submitted ones
 *   [Assignments]       — table of broker-assigned negotiations
 *   [Negotiation: <id>] — one tab per active negotiation (chat interface)
 *                         opened automatically when an assignment arrives
 */
public class BuyerGui extends JFrame {

    private final BuyerAgent buyer;
    private final JTabbedPane tabs;

    private final DefaultTableModel requirementsModel;
    private final DefaultTableModel assignmentsModel;

    // ── Activity log ──────────────────────────────────────────────────────────
    private final JTextArea logArea = new JTextArea();
    public JTextArea getLogArea() { return logArea; }
    private final Map<String, NegotiationPanel> negPanels = new LinkedHashMap<>();

    private static final String[] REQ_COLS  = {"ID", "Make", "Model", "Year Min", "Year Max", "Max Price (RM)", "Condition", "Status"};
    private static final String[] ASGN_COLS = {"Negotiation ID", "Dealer", "Listing", "Asking Price (RM)", "Status"};

    public BuyerGui(BuyerAgent buyer) {
        super("Buyer  —  " + buyer.getLocalName());
        this.buyer = buyer;

        requirementsModel = makeReadOnlyModel(REQ_COLS);
        assignmentsModel  = makeReadOnlyModel(ASGN_COLS);
        tabs = new JTabbedPane();

        tabs.addTab("🔍  My Requirements", buildRequirementsTab());
        tabs.addTab("📌  Assignments",      buildAssignmentsTab());

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
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Light theme styling\n    private void applyLightTheme() {\n        // Define colorful light theme colors\n        Color primaryColor = new Color(70, 130, 180);      // Steel blue\n        Color accentColor = new Color(255, 140, 0);        // Dark orange\n        Color successColor = new Color(34, 139, 34);      // Forest green\n        Color headerColor = new Color(240, 248, 255);     // Alice blue\n        Color borderColor = new Color(176, 196, 222);      // Light steel blue\n        \n        // Style log area\n        logArea.setBackground(new Color(248, 248, 248)); // Very light gray\n        logArea.setBorder(BorderFactory.createCompoundBorder(\n            BorderFactory.createLineBorder(borderColor),\n            BorderFactory.createEmptyBorder(4, 4, 4, 4)\n        ));\n    }\n    \n    // Tab: My Requirements
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel buildRequirementsTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── Input form ────────────────────────────────────────────────────────
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new TitledBorder("Submit Car Requirements (leave blank = any)"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill   = GridBagConstraints.HORIZONTAL;

        JTextField makeField     = new JTextField(12);
        JTextField modelField    = new JTextField(12);
        JTextField yearMinField  = new JTextField(6);
        JTextField yearMaxField  = new JTextField(6);
        JTextField maxPriceField = new JTextField(10);
        JTextField maxMilesField = new JTextField(10);
        JComboBox<String> condBox = new JComboBox<>(new String[]{"Any", "New", "Used"});
        JTextArea notesArea      = new JTextArea(2, 30);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);

        Object[][] fields = {
                {"Preferred Make", makeField},     {"Preferred Model", modelField},
                {"Year (from)", yearMinField},      {"Year (to)", yearMaxField},
                {"Max Price (RM)", maxPriceField}, {"Max Mileage (km)", maxMilesField},
                {"Condition", condBox},            {"Notes to Broker", new JScrollPane(notesArea)}
        };

        int row = 0;
        for (int i = 0; i < fields.length; i += 2) {
            gbc.gridy = row;
            gbc.gridx = 0; gbc.weightx = 0; form.add(new JLabel(fields[i][0].toString()), gbc);
            gbc.gridx = 1; gbc.weightx = 1; form.add((Component) fields[i][1], gbc);
            gbc.gridx = 2; gbc.weightx = 0; form.add(new JLabel(fields[i + 1][0].toString()), gbc);
            gbc.gridx = 3; gbc.weightx = 1; form.add((Component) fields[i + 1][1], gbc);
            row++;
        }

        JButton submitBtn = new JButton("Submit Requirements to Broker");
        submitBtn.setFont(submitBtn.getFont().deriveFont(Font.BOLD));
        submitBtn.addActionListener(e -> {
            try {
                CarRequirement req = new CarRequirement();
                req.setMake(makeField.getText().trim());
                req.setModel(modelField.getText().trim());
                req.setCondition((String) condBox.getSelectedItem());
                req.setNotes(notesArea.getText().trim());

                if (!yearMinField.getText().isBlank())
                    req.setYearMin(Integer.parseInt(yearMinField.getText().trim()));
                if (!yearMaxField.getText().isBlank())
                    req.setYearMax(Integer.parseInt(yearMaxField.getText().trim()));
                if (!maxPriceField.getText().isBlank())
                    req.setMaxPrice(Double.parseDouble(maxPriceField.getText().trim()));
                if (!maxMilesField.getText().isBlank())
                    req.setMaxMileage(Integer.parseInt(maxMilesField.getText().trim()));

                buyer.submitRequirement(req);

                // Clear form
                makeField.setText(""); modelField.setText(""); yearMinField.setText("");
                yearMaxField.setText(""); maxPriceField.setText(""); maxMilesField.setText("");
                notesArea.setText(""); condBox.setSelectedIndex(0);

            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(p, "Year, price and mileage must be numbers.",
                    "Input Error", JOptionPane.WARNING_MESSAGE);
            }
        });

        gbc.gridy = row; gbc.gridx = 0; gbc.gridwidth = 4;
        gbc.anchor = GridBagConstraints.CENTER; gbc.fill = GridBagConstraints.NONE;
        form.add(submitBtn, gbc);

        // ── Requirements table ────────────────────────────────────────────────
        JTable table = new JTable(requirementsModel);
        styleTable(table);
        JPanel tablePanel = new JPanel(new BorderLayout(4, 4));
        tablePanel.setBorder(new TitledBorder("Submitted Requirements"));
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);

        p.add(form, BorderLayout.NORTH);
        p.add(tablePanel, BorderLayout.CENTER);
        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tab: Assignments
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel buildAssignmentsTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        JLabel info = new JLabel(
            "<html>These are negotiations the Broker has assigned to you. " +
            "Open each negotiation tab to respond.</html>");
        p.add(info, BorderLayout.NORTH);
        JTable table = new JTable(assignmentsModel);
        styleTable(table);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public methods — called on EDT by BuyerAgent
    // ─────────────────────────────────────────────────────────────────────────

    /** Called after buyer successfully submits requirements. Updates the table. */
    public void onRequirementSubmitted(CarRequirement req) {
        requirementsModel.addRow(new Object[]{
                req.getRequirementId(),
                nvl(req.getMake()), nvl(req.getModel()),
                req.getYearMin() > 0 ? req.getYearMin() : "—",
                req.getYearMax() > 0 ? req.getYearMax() : "—",
                req.getMaxPrice() > 0 ? String.format("%.0f", req.getMaxPrice()) : "—",
                req.getCondition(),
                "⏳ Waiting for broker"
        });
    }

    /** Opens a new negotiation tab when the broker assigns us to a dealer. */
    public void openNegotiationTab(Assignment a) {
        // Add row to Assignments tab
        assignmentsModel.addRow(new Object[]{
                a.getNegotiationId(), a.getDealerName(),
                a.getListing().toString(),
                String.format("%.0f", a.getListing().getRetailPrice()),
                "Active"
        });

        // Update the requirement's status
        for (int i = 0; i < requirementsModel.getRowCount(); i++) {
            if (a.getRequirement() != null
                    && a.getRequirement().getRequirementId() != null
                    && a.getRequirement().getRequirementId()
                        .equals(requirementsModel.getValueAt(i, 0))) {
                requirementsModel.setValueAt("✅ Matched", i, 7);
            }
        }

        // Build chat panel
        NegotiationPanel panel = new NegotiationPanel(a, buyer);
        negPanels.put(a.getNegotiationId(), panel);
        tabs.addTab("💬 " + a.getDealerName(), panel);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);

        if (a.getBrokerNote() != null && !a.getBrokerNote().isBlank()) {
            panel.appendSystem("[BROKER NOTE]  " + a.getBrokerNote());
        }
    }

    /** Appends a new message to the relevant negotiation tab. */
    public void appendNegotiationMessage(NegotiationMessage msg) {
        NegotiationPanel panel = negPanels.get(msg.getNegotiationId());
        if (panel != null) panel.appendMessage(msg);
        // Switch to that tab so buyer sees incoming offer
        int idx = indexOfTab("💬 " + msg.getFromName());
        if (idx >= 0) tabs.setSelectedIndex(idx);
    }

    /** Called when a deal is completed. */
    public void onDealComplete(NegotiationMessage finalMsg) {
        NegotiationPanel panel = negPanels.get(finalMsg.getNegotiationId());
        if (panel != null) {
            panel.appendSystem(String.format("✅  DEAL COMPLETE — Final price: RM %.0f", finalMsg.getPrice()));
            panel.disableInput();
        }
        updateAssignmentStatus(finalMsg.getNegotiationId(), "✅ Complete");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

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

    private static String nvl(String s) {
        return (s == null || s.isBlank()) ? "Any" : s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner class: NegotiationPanel
    // ─────────────────────────────────────────────────────────────────────────

    private static class NegotiationPanel extends JPanel {

        private final Assignment  assignment;
        private final BuyerAgent  buyer;
        private final JTextArea   historyArea;
        private final JTextField  priceField;
        private final JTextArea   messageField;
        private final JButton     offerBtn;
        private final JButton     acceptBtn;
        private final JButton     rejectBtn;
        private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

        NegotiationPanel(Assignment a, BuyerAgent buyer) {
            super(new BorderLayout(6, 6));
            this.assignment = a;
            this.buyer      = buyer;
            setBorder(new EmptyBorder(10, 10, 10, 10));

            // ── Info banner ───────────────────────────────────────────────────
            JLabel info = new JLabel(
                "<html><b>Car:</b>  " + a.getListing() + "<br>" +
                "<b>Dealer:</b>  " + a.getDealerName() +
                "   <b>Asking Price:</b>  RM " +
                String.format("%.0f", a.getListing().getRetailPrice()) +
                (a.getRequirement().getMaxPrice() > 0
                    ? "   <b>Your Budget:</b>  RM " + String.format("%.0f", a.getRequirement().getMaxPrice())
                    : "") +
                "</html>");
            info.setBorder(new EmptyBorder(0, 0, 6, 0));
            add(info, BorderLayout.NORTH);

            // ── Chat history ──────────────────────────────────────────────────
            historyArea = new JTextArea();
            historyArea.setEditable(false);
            historyArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            historyArea.setLineWrap(true);
            historyArea.setWrapStyleWord(true);
            add(new JScrollPane(historyArea), BorderLayout.CENTER);

            // ── Input panel ───────────────────────────────────────────────────
            JPanel input = new JPanel(new BorderLayout(6, 4));
            input.setBorder(new TitledBorder("Your Response"));

            JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            topRow.add(new JLabel("Your Offer Price (RM):"));
            // Default to buyer's max price or 80% of retail as starting counter
            double defaultOffer = a.getRequirement().getMaxPrice() > 0
                    ? a.getRequirement().getMaxPrice()
                    : a.getListing().getRetailPrice() * 0.85;
            priceField = new JTextField(String.format("%.0f", defaultOffer), 10);
            topRow.add(priceField);
            input.add(topRow, BorderLayout.NORTH);

            messageField = new JTextArea(2, 40);
            messageField.setLineWrap(true);
            messageField.setWrapStyleWord(true);
            messageField.setBorder(BorderFactory.createTitledBorder("Optional message"));
            input.add(new JScrollPane(messageField), BorderLayout.CENTER);

            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            offerBtn  = new JButton("📤 Counter Offer");
            acceptBtn = new JButton("✅ Accept Deal");
            rejectBtn = new JButton("❌ Reject");
            offerBtn .setForeground(new Color(100, 160, 255));
            acceptBtn.setForeground(new Color(80,  200, 80));
            rejectBtn.setForeground(new Color(220, 80,  80));
            btnRow.add(offerBtn);
            btnRow.add(acceptBtn);
            btnRow.add(rejectBtn);
            input.add(btnRow, BorderLayout.SOUTH);
            add(input, BorderLayout.SOUTH);

            // Note: buyer waits for the dealer's first offer before responding.
            // Input is enabled from the start so buyer can make a first move if desired.

            // ── Button actions ────────────────────────────────────────────────
            offerBtn.addActionListener(e -> {
                try {
                    double price = Double.parseDouble(priceField.getText().trim());
                    String note  = messageField.getText().trim();
                    buyer.sendOffer(assignment.getNegotiationId(), price, note);
                    appendSent("COUNTER OFFER", price, note);
                    messageField.setText("");
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Enter a valid price.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            });

            acceptBtn.addActionListener(e -> {
                try {
                    double price = Double.parseDouble(priceField.getText().trim());
                    int confirm  = JOptionPane.showConfirmDialog(this,
                        String.format("Accept deal at RM %.0f?", price),
                        "Confirm Accept", JOptionPane.YES_NO_OPTION);
                    if (confirm == JOptionPane.YES_OPTION) {
                        String note = messageField.getText().trim();
                        buyer.acceptOffer(assignment.getNegotiationId(), price, note);
                        appendSent("ACCEPT", price, note);
                        disableInput();
                    }
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Enter a valid price.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            });

            rejectBtn.addActionListener(e -> {
                int confirm = JOptionPane.showConfirmDialog(this,
                    "Reject this negotiation? This ends the session.",
                    "Confirm Reject", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    String note = messageField.getText().trim();
                    buyer.rejectOffer(assignment.getNegotiationId(), note);
                    appendSent("REJECT", 0, note);
                    disableInput();
                }
            });
        }

        void appendMessage(NegotiationMessage msg) {
            String time     = SDF.format(new Date(msg.getTimestamp()));
            String priceStr = msg.getPrice() > 0
                    ? "  RM " + String.format("%.0f", msg.getPrice()) : "";
            String noteStr  = msg.getMessage() != null && !msg.getMessage().isBlank()
                    ? "\n  \"" + msg.getMessage() + "\"" : "";
            historyArea.append(String.format("[%s] %s (%s)  %s%s%s%n",
                    time, msg.getFromName(), msg.getFromRole(), msg.getType().name(), priceStr, noteStr));
            historyArea.setCaretPosition(historyArea.getDocument().getLength());

            // Pre-fill price field with dealer's offer for easy counter
            if (msg.getType() == NegotiationMessage.Type.OFFER && msg.getPrice() > 0) {
                priceField.setText(String.format("%.0f", msg.getPrice()));
            }
            if (msg.getType() == NegotiationMessage.Type.REJECT) {
                appendSystem("Dealer ended the negotiation.");
                disableInput();
            }
        }

        void appendSent(String type, double price, String note) {
            String time     = SDF.format(new Date());
            String priceStr = price > 0 ? "  RM " + String.format("%.0f", price) : "";
            String noteStr  = note != null && !note.isBlank() ? "\n  \"" + note + "\"" : "";
            historyArea.append(String.format("[%s] YOU (BUYER)  %s%s%s%n",
                    time, type, priceStr, noteStr));
            historyArea.setCaretPosition(historyArea.getDocument().getLength());
        }

        void appendSystem(String text) {
            historyArea.append("─── " + text + " ───\n");
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
