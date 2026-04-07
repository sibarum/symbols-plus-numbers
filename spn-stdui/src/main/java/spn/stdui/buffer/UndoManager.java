package spn.stdui.buffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Tree-based undo/redo history.
 *
 * <p>Unlike a linear undo stack, editing after an undo does NOT discard the old
 * redo path. Instead it creates a branch. All branches are preserved and can
 * be revisited via {@link #switchBranch(int)}.
 *
 * <p>Consecutive single-character inserts and backspaces are merged into one
 * node so that undo operates on words, not characters.
 */
public class UndoManager {

    public static class Entry {
        public int row, col;
        public String removed;
        public String inserted;
        public int cursorRowBefore, cursorColBefore;
        public int cursorRowAfter, cursorColAfter;

        public Entry(int row, int col, String removed, String inserted,
                     int crBefore, int ccBefore, int crAfter, int ccAfter) {
            this.row = row;
            this.col = col;
            this.removed = removed;
            this.inserted = inserted;
            this.cursorRowBefore = crBefore;
            this.cursorColBefore = ccBefore;
            this.cursorRowAfter = crAfter;
            this.cursorColAfter = ccAfter;
        }
    }

    static class Node {
        final Entry edit;
        final Node parent;
        final List<Node> children = new ArrayList<>();
        int activeChild = -1;
        final int depth;

        Node() { this.edit = null; this.parent = null; this.depth = 0; }

        Node(Entry edit, Node parent) {
            this.edit = edit;
            this.parent = parent;
            this.depth = parent.depth + 1;
        }
    }

    public record Info(int depth, int branches, int activeBranch, boolean canUndo, boolean canRedo) {}

    private Node root = new Node();
    private Node current = root;
    private int totalNodes;

    public void record(int row, int col, String removed, String inserted,
                       int crBefore, int ccBefore, int crAfter, int ccAfter) {
        if (current != root && current.children.isEmpty()) {
            Entry top = current.edit;
            if (removed.isEmpty() && top.removed.isEmpty()
                    && inserted.length() == 1 && !inserted.equals("\n")
                    && !top.inserted.contains("\n")
                    && row == top.row && col == top.col + top.inserted.length()) {
                top.inserted += inserted;
                top.cursorRowAfter = crAfter;
                top.cursorColAfter = ccAfter;
                return;
            }
            if (inserted.isEmpty() && top.inserted.isEmpty()
                    && removed.length() == 1 && !removed.equals("\n")
                    && !top.removed.contains("\n")
                    && row == top.row && col == top.col - 1) {
                top.col = col;
                top.removed = removed + top.removed;
                top.cursorRowAfter = crAfter;
                top.cursorColAfter = ccAfter;
                return;
            }
            if (inserted.isEmpty() && top.inserted.isEmpty()
                    && removed.length() == 1 && !removed.equals("\n")
                    && !top.removed.contains("\n")
                    && row == top.row && col == top.col) {
                top.removed += removed;
                top.cursorRowAfter = crAfter;
                top.cursorColAfter = ccAfter;
                return;
            }
        }
        Node child = new Node(
                new Entry(row, col, removed, inserted, crBefore, ccBefore, crAfter, ccAfter),
                current);
        current.children.add(child);
        current.activeChild = current.children.size() - 1;
        current = child;
        totalNodes++;
    }

    public Entry undo() {
        if (current == root) return null;
        Entry e = current.edit;
        Node parent = current.parent;
        parent.activeChild = parent.children.indexOf(current);
        current = parent;
        return e;
    }

    public Entry redo() {
        if (current.children.isEmpty() || current.activeChild < 0) return null;
        current = current.children.get(current.activeChild);
        return current.edit;
    }

    public boolean switchBranch(int direction) {
        if (current.children.size() <= 1) return false;
        int newIdx = current.activeChild + direction;
        if (newIdx < 0 || newIdx >= current.children.size()) return false;
        current.activeChild = newIdx;
        return true;
    }

    public Info getInfo() {
        return new Info(
                current.depth,
                current.children.size(),
                current.children.isEmpty() ? 0 : current.activeChild + 1,
                current != root,
                !current.children.isEmpty()
        );
    }

    public void clear() {
        root = new Node();
        current = root;
        totalNodes = 0;
    }
}
