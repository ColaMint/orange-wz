package orange.wz.gui.component.dialog;

import orange.wz.gui.MainFrame;
import orange.wz.provider.tools.WzMergeService.Candidate;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class WzMergeDialog extends JDialog {
    private final CandidateTableModel model;
    private final JCheckBox selectAll;
    private boolean confirmed;
    private boolean updatingSelectAll;

    private WzMergeDialog(Frame owner, String oldName, String newName, List<Candidate> candidates) {
        super(owner, MainFrame.i18n.get("merge.dialog.title"), true);
        model = new CandidateTableModel(candidates);
        selectAll = new JCheckBox(MainFrame.i18n.get("merge.select_all"), true);

        setLayout(new BorderLayout(8, 8));
        JPanel files = new JPanel(new GridLayout(2, 1, 0, 4));
        files.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        files.add(new JLabel(MainFrame.i18n.get("merge.old_file", oldName)));
        files.add(new JLabel(MainFrame.i18n.get("merge.new_file", newName)));
        add(files, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(1).setMinWidth(72);
        table.getColumnModel().getColumn(1).setMaxWidth(90);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JButton confirm = new JButton(MainFrame.i18n.get("merge.confirm"));
        JButton cancel = new JButton(MainFrame.i18n.get("merge.cancel"));
        confirm.setEnabled(!candidates.isEmpty());
        confirm.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        selectAll.addActionListener(e -> {
            if (!updatingSelectAll) {
                model.setAllSelected(selectAll.isSelected());
            }
        });
        model.addTableModelListener(e -> updateSelectAll());

        JPanel actions = new JPanel(new BorderLayout());
        actions.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        actions.add(selectAll, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(confirm);
        buttons.add(cancel);
        actions.add(buttons, BorderLayout.EAST);
        add(actions, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(confirm);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(720, 460);
        setLocationRelativeTo(owner);
    }

    public static List<Candidate> show(Frame owner, String oldName, String newName,
                                       List<Candidate> candidates) {
        WzMergeDialog dialog = new WzMergeDialog(owner, oldName, newName, candidates);
        dialog.setVisible(true);
        return dialog.confirmed ? dialog.model.selectedCandidates() : null;
    }

    private void updateSelectAll() {
        updatingSelectAll = true;
        selectAll.setSelected(model.areAllSelected());
        updatingSelectAll = false;
    }

    private static final class CandidateTableModel extends AbstractTableModel {
        private final List<Candidate> candidates;
        private final List<Boolean> selected;

        private CandidateTableModel(List<Candidate> candidates) {
            this.candidates = List.copyOf(candidates);
            this.selected = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                selected.add(true);
            }
        }

        @Override
        public int getRowCount() {
            return candidates.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return MainFrame.i18n.get(column == 0 ? "merge.column.path" : "merge.column.selected");
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return columnIndex == 0 ? candidates.get(rowIndex).path() : selected.get(rowIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 1 && value instanceof Boolean checked) {
                selected.set(rowIndex, checked);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }

        private void setAllSelected(boolean checked) {
            for (int i = 0; i < selected.size(); i++) {
                selected.set(i, checked);
            }
            fireTableDataChanged();
        }

        private boolean areAllSelected() {
            return !selected.isEmpty() && selected.stream().allMatch(Boolean::booleanValue);
        }

        private List<Candidate> selectedCandidates() {
            List<Candidate> result = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                if (selected.get(i)) {
                    result.add(candidates.get(i));
                }
            }
            return result;
        }
    }
}
