package cott.pem.traction;

import java.util.ArrayList;
import java.util.List;

/**
 * Tree-based undo/redo history.
 *
 * Unlike a linear undo stack, editing after an undo does NOT discard the old
 * redo path. Instead it creates a branch. All branches are preserved and can
 * be revisited via {@link #switchBranch(int)}.
 *
 * Consecutive single-character inserts and backspaces are merged into one
 * node so that Ctrl+Z undoes a word at a time, not a character at a time.
 */
public class UndoManager {

    /** A single undoable edit. */
    static class Entry {
        int row, col;
        String removed;
        String inserted;
        int cursorRowBefore, cursorColBefore;
        int cursorRowAfter, cursorColAfter;

        Entry(int row, int col, String removed, String inserted,
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

    /** A node in the undo tree. The root node has a null edit. */
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

    /** Snapshot of the undo tree state for the HUD. */
    public record Info(int depth, int branches, int activeBranch, boolean canUndo, boolean canRedo) {}

    private Node root = new Node();
    private Node current = root;
    private int totalNodes;

    /** Record an edit. Creates a new branch if we're not at a leaf. */
    public void record(int row, int col, String removed, String inserted,
                       int crBefore, int ccBefore, int crAfter, int ccAfter) {

        // Try to merge with the current node (only if it's a leaf — no branches yet)
        if (current != root && current.children.isEmpty()) {
            Entry top = current.edit;

            // Merge consecutive single-char inserts (typing)
            if (removed.isEmpty() && top.removed.isEmpty()
                    && inserted.length() == 1 && !inserted.equals("\n")
                    && !top.inserted.contains("\n")
                    && row == top.row && col == top.col + top.inserted.length()) {
                top.inserted += inserted;
                top.cursorRowAfter = crAfter;
                top.cursorColAfter = ccAfter;
                return;
            }

            // Merge consecutive backspaces
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

            // Merge consecutive forward deletes
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

        // New node — becomes a child of current (possibly creating a branch)
        Node child = new Node(
                new Entry(row, col, removed, inserted, crBefore, ccBefore, crAfter, ccAfter),
                current);
        current.children.add(child);
        current.activeChild = current.children.size() - 1;
        current = child;
        totalNodes++;
    }

    /** Undo the current edit. Returns the entry to reverse, or null if at root. */
    public Entry undo() {
        if (current == root) return null;
        Entry e = current.edit;
        // Mark this node as the active child so redo returns here
        Node parent = current.parent;
        parent.activeChild = parent.children.indexOf(current);
        current = parent;
        return e;
    }

    /** Redo along the active branch. Returns the entry to reapply, or null if at a leaf. */
    public Entry redo() {
        if (current.children.isEmpty() || current.activeChild < 0) return null;
        current = current.children.get(current.activeChild);
        return current.edit;
    }

    /**
     * Switch which branch redo will follow at the current node.
     * @param direction -1 for previous branch, +1 for next
     * @return true if the branch changed
     */
    public boolean switchBranch(int direction) {
        if (current.children.size() <= 1) return false;
        int newIdx = current.activeChild + direction;
        if (newIdx < 0 || newIdx >= current.children.size()) return false;
        current.activeChild = newIdx;
        return true;
    }

    /** Get a snapshot of the current undo tree state for display. */
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
