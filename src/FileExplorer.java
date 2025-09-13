import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileSystemView;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class FileExplorer extends JFrame {
    private final FileSystemView fileSystemView = FileSystemView.getFileSystemView();
    private JTree tree;
    private JTable table;
    private FileTableModel fileTableModel;
    private JTextField pathField;
    private JTextField searchField;
    private Deque<File> backStack = new ArrayDeque<>();
    private Deque<File> forwardStack = new ArrayDeque<>();
    private File currentDirectory;
    private JLabel statusLabel;

    public FileExplorer() {
        super("File System Explorer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 650);
        setLocationRelativeTo(null);

        initGUI();
    }

    private void initGUI() {
        // Top toolbar
        JToolBar toolBar = new JToolBar();
        JButton backBtn = new JButton("◀");
        JButton forwardBtn = new JButton("▶");
        JButton upBtn = new JButton("Up");
        JButton refreshBtn = new JButton("⟳");
        JButton newFolderBtn = new JButton("New Folder");
        JButton deleteBtn = new JButton("Delete");
        JButton renameBtn = new JButton("Rename");
        JButton openBtn = new JButton("Open");

        backBtn.setToolTipText("Back");
        forwardBtn.setToolTipText("Forward");
        upBtn.setToolTipText("Up");
        refreshBtn.setToolTipText("Refresh");
        newFolderBtn.setToolTipText("Create new folder");
        deleteBtn.setToolTipText("Delete selected");
        renameBtn.setToolTipText("Rename selected");
        openBtn.setToolTipText("Open selected");

        toolBar.add(backBtn);
        toolBar.add(forwardBtn);
        toolBar.add(upBtn);
        toolBar.add(refreshBtn);
        toolBar.addSeparator();
        toolBar.add(newFolderBtn);
        toolBar.add(deleteBtn);
        toolBar.add(renameBtn);
        toolBar.add(openBtn);
        toolBar.addSeparator(new Dimension(20, 0));

        pathField = new JTextField();
        pathField.setEditable(false);
        pathField.setColumns(40);
        toolBar.add(new JLabel(" Path: "));
        toolBar.add(pathField);

        toolBar.addSeparator(new Dimension(20, 0));
        searchField = new JTextField();
        searchField.setColumns(20);
        toolBar.add(new JLabel(" Search: "));
        toolBar.add(searchField);
        JButton searchBtn = new JButton("Go");
        toolBar.add(searchBtn);

        add(toolBar, BorderLayout.NORTH);

        // Split pane - tree on left, table on right
        DefaultMutableTreeNode rootNode = createFileTreeRoot();
        tree = new JTree(rootNode);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.addTreeSelectionListener(this::onTreeSelection);

        // Expand top-level drives quickly
        expandTreeRoot(tree);

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(300, 400));

        fileTableModel = new FileTableModel();
        table = new JTable(fileTableModel);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        File f = fileTableModel.getFileAt(row);
                        if (f.isDirectory()) {
                            navigateTo(f, true);
                        } else {
                            openFile(f);
                        }
                    }
                }
            }

            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showTablePopup(e);
            }

            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showTablePopup(e);
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, tableScroll);
        splitPane.setDividerLocation(300);

        add(splitPane, BorderLayout.CENTER);

        // Bottom status
        statusLabel = new JLabel("Ready");
        add(statusLabel, BorderLayout.SOUTH);

        // Button actions
        backBtn.addActionListener(e -> goBack());
        forwardBtn.addActionListener(e -> goForward());
        upBtn.addActionListener(e -> {
            if (currentDirectory != null && currentDirectory.getParentFile() != null) {
                navigateTo(currentDirectory.getParentFile(), true);
            }
        });
        refreshBtn.addActionListener(e -> refreshCurrentDirectory());
        newFolderBtn.addActionListener(e -> createNewFolder());
        deleteBtn.addActionListener(e -> deleteSelected());
        renameBtn.addActionListener(e -> renameSelected());
        openBtn.addActionListener(e -> openSelected());
        searchBtn.addActionListener(e -> doSearch());
        searchField.addActionListener(e -> doSearch());

        // Initialize to user's home
        File home = fileSystemView.getHomeDirectory();
        navigateTo(home, false);
    }

    private DefaultMutableTreeNode createFileTreeRoot() {
        File[] roots = File.listRoots();
        FileSystemView fsv = fileSystemView;
        DefaultMutableTreeNode rootWrapper = new DefaultMutableTreeNode("Computer");
        for (File root : roots) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new FileNode(root));
            rootWrapper.add(node);
            // lazily add dummy child so node is expandable
            node.add(new DefaultMutableTreeNode(Boolean.TRUE));
        }
        return rootWrapper;
    }

    private void expandTreeRoot(JTree tree) {
        TreeModel model = tree.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        Enumeration<?> e = root.children();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
            TreePath path = new TreePath(node.getPath());
            tree.expandPath(path);
        }

        // Add tree expansion listener to populate nodes lazily
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                loadChildren(node);
            }

            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {}
        });
    }

    private void loadChildren(DefaultMutableTreeNode node) {
        Object userObj = node.getUserObject();
        if (userObj instanceof FileNode) {
            FileNode fn = (FileNode) userObj;
            File file = fn.getFile();
            node.removeAllChildren();
            File[] files = fileSystemView.getFiles(file, false);
            if (files == null) return;
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : files) {
                if (f.isDirectory()) {
                    DefaultMutableTreeNode child = new DefaultMutableTreeNode(new FileNode(f));
                    // add dummy so it can be expanded later
                    child.add(new DefaultMutableTreeNode(Boolean.TRUE));
                    node.add(child);
                }
            }
            ((DefaultTreeModel) tree.getModel()).reload(node);
        }
    }

    private void onTreeSelection(TreeSelectionEvent e) {
        TreePath path = e.getPath();
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object u = node.getUserObject();
        if (u instanceof FileNode) {
            File f = ((FileNode) u).getFile();
            navigateTo(f, true);
        }
    }

    private void navigateTo(File dir, boolean pushHistory) {
        if (dir == null || !dir.exists()) return;
        try {
            dir = dir.getCanonicalFile();
        } catch (IOException ex) {
            // ignore canonicalization issues
        }
        if (currentDirectory != null && pushHistory) {
            backStack.push(currentDirectory);
            forwardStack.clear();
        }
        currentDirectory = dir;
        pathField.setText(dir.getAbsolutePath());
        File[] files = fileSystemView.getFiles(dir, true); // show hidden files too? false if you don't want
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        fileTableModel.setFiles(Arrays.asList(files));
        statusLabel.setText("Showing " + files.length + " items in " + dir.getAbsolutePath());
        // Expand tree selection to this node if present
        selectTreeNodeForFile(dir);
    }

    private void selectTreeNodeForFile(File dir) {
        TreeModel model = tree.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        DefaultMutableTreeNode match = findNodeForFile(root, dir);
        if (match != null) {
            TreePath path = new TreePath(match.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private DefaultMutableTreeNode findNodeForFile(DefaultMutableTreeNode node, File target) {
        Object obj = node.getUserObject();
        if (obj instanceof FileNode) {
            File nFile = ((FileNode) obj).getFile();
            try {
                if (nFile.getCanonicalPath().equals(target.getCanonicalPath())) {
                    return node;
                }
            } catch (IOException e) {
                // ignore
            }
        }
        Enumeration<?> en = node.children();
        while (en.hasMoreElements()) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) en.nextElement();
            DefaultMutableTreeNode found = findNodeForFile(child, target);
            if (found != null) return found;
        }
        return null;
    }

    private void goBack() {
        if (backStack.isEmpty()) return;
        forwardStack.push(currentDirectory);
        File prev = backStack.pop();
        navigateTo(prev, false);
    }

    private void goForward() {
        if (forwardStack.isEmpty()) return;
        backStack.push(currentDirectory);
        File next = forwardStack.pop();
        navigateTo(next, false);
    }

    private void refreshCurrentDirectory() {
        if (currentDirectory != null) navigateTo(currentDirectory, false);
    }

    private void createNewFolder() {
        if (currentDirectory == null) return;
        String name = JOptionPane.showInputDialog(this, "New folder name:", "Create Folder", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) return;
        File newDir = new File(currentDirectory, name.trim());
        if (newDir.exists()) {
            JOptionPane.showMessageDialog(this, "A file/folder with that name already exists.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean ok = newDir.mkdir();
        if (!ok) {
            JOptionPane.showMessageDialog(this, "Failed to create folder. Check permissions.", "Error", JOptionPane.ERROR_MESSAGE);
        }
        refreshCurrentDirectory();
    }

    private void deleteSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "No selection to delete.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "Delete selected item(s)? This cannot be undone.", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        for (int r : rows) {
            File f = fileTableModel.getFileAt(r);
            try {
                if (f.isDirectory()) {
                    deleteDirectoryRecursively(f.toPath());
                } else {
                    Files.deleteIfExists(f.toPath());
                }
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Failed to delete: " + f.getName() + " (" + ex.getMessage() + ")", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        refreshCurrentDirectory();
    }

    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ex) {
                        // ignore per-file errors here
                    }
                });
    }

    private void renameSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select an item to rename.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File f = fileTableModel.getFileAt(row);
        String newName = JOptionPane.showInputDialog(this, "Rename to:", f.getName());
        if (newName == null || newName.trim().isEmpty()) return;
        File dest = new File(f.getParentFile(), newName.trim());
        if (dest.exists()) {
            JOptionPane.showMessageDialog(this, "A file with that name already exists.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean ok = f.renameTo(dest);
        if (!ok) {
            JOptionPane.showMessageDialog(this, "Rename failed. Check permissions.", "Error", JOptionPane.ERROR_MESSAGE);
        }
        refreshCurrentDirectory();
    }

    private void openSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select an item to open.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File f = fileTableModel.getFileAt(row);
        if (f.isDirectory()) navigateTo(f, true);
        else openFile(f);
    }

    private void openFile(File f) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(f);
            } else {
                JOptionPane.showMessageDialog(this, "Open operation not supported on this platform.", "Info", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to open file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doSearch() {
        String q = searchField.getText();
        if (q == null || q.trim().isEmpty()) {
            refreshCurrentDirectory();
            return;
        }
        String ql = q.trim().toLowerCase();
        if (currentDirectory == null) return;
        File[] files = currentDirectory.listFiles();
        if (files == null) return;
        List<File> matches = Arrays.stream(files)
                .filter(f -> f.getName().toLowerCase().contains(ql))
                .sorted(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        fileTableModel.setFiles(matches);
        statusLabel.setText("Search: " + matches.size() + " matches for \"" + q + "\" in " + currentDirectory.getName());
    }

    private void showTablePopup(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        JPopupMenu popup = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("Open");
        JMenuItem renameItem = new JMenuItem("Rename");
        JMenuItem deleteItem = new JMenuItem("Delete");
        JMenuItem propertiesItem = new JMenuItem("Properties");

        openItem.addActionListener(a -> openSelected());
        renameItem.addActionListener(a -> renameSelected());
        deleteItem.addActionListener(a -> deleteSelected());
        propertiesItem.addActionListener(a -> showProperties());

        popup.add(openItem);
        popup.add(renameItem);
        popup.add(deleteItem);
        popup.addSeparator();
        popup.add(propertiesItem);
        popup.show(table, e.getX(), e.getY());
    }

    private void showProperties() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        File f = fileTableModel.getFileAt(row);
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(f.getName()).append("\n");
        sb.append("Path: ").append(f.getAbsolutePath()).append("\n");
        sb.append("Type: ").append(f.isDirectory() ? "Folder" : "File").append("\n");
        sb.append("Size: ").append(f.isFile() ? readableFileSize(f.length()) : "-").append("\n");
        sb.append("Readable: ").append(f.canRead()).append("\n");
        sb.append("Writable: ").append(f.canWrite()).append("\n");
        sb.append("Executable: ").append(f.canExecute()).append("\n");
        sb.append("Last Modified: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.lastModified()))).append("\n");
        JOptionPane.showMessageDialog(this, sb.toString(), "Properties", JOptionPane.INFORMATION_MESSAGE);
    }

    private String readableFileSize(long size) {
        if (size <= 0) return "0";
        final String[] units = new String[] {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    // FileNode used for tree user object
    private static class FileNode {
        private final File file;
        public FileNode(File file) { this.file = file; }
        public File getFile() { return file; }
        public String toString() {
            String name = file.getName();
            if (name.isEmpty()) return file.getAbsolutePath();
            return name;
        }
    }

    // Table model to show files
    private static class FileTableModel extends AbstractTableModel {
        private final String[] columns = {"Name", "Size", "Type", "Last Modified"};
        private List<File> files = new ArrayList<>();
        private final FileSystemView view = FileSystemView.getFileSystemView();
        private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        public void setFiles(List<File> files) {
            this.files = new ArrayList<>(files);
            fireTableDataChanged();
        }

        public File getFileAt(int row) {
            return files.get(row);
        }

        public int getRowCount() { return files.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int col) { return columns[col]; }

        public Object getValueAt(int row, int col) {
            File f = files.get(row);
            switch (col) {
                case 0:
                    return view.getSystemDisplayName(f);
                case 1:
                    if (f.isDirectory()) return "-";
                    long len = f.length();
                    return readableFileSize(len);
                case 2:
                    return f.isDirectory() ? "Folder" : view.getSystemTypeDescription(f);
                case 3:
                    return df.format(new Date(f.lastModified()));
            }
            return null;
        }

        private String readableFileSize(long size) {
            if (size <= 0) return "0";
            final String[] units = new String[] {"B", "KB", "MB", "GB", "TB"};
            int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
            return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Use system look & feel for better platform integration
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            FileExplorer explorer = new FileExplorer();
            explorer.setVisible(true);
        });
    }
}
