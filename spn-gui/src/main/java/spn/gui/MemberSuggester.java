package spn.gui;

import spn.lang.TypeGraph;
import spn.stdui.buffer.TextBuffer;
import spn.type.FieldDescriptor;
import spn.type.SpnFunctionDescriptor;
import spn.type.SpnStructDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cycles through contextual suggestions at the cursor.
 *
 * <p>Two modes, both triggered by Ctrl+, (previous) and Ctrl+. (next):
 *
 * <ul>
 *   <li>{@code IDENT}: cursor is on a (possibly partial) identifier, optionally
 *       preceded by a {@code receiver.}. Cycles through visible names from the
 *       TypeGraph.</li>
 *   <li>{@code ARGS}: cursor is right after a {@code (} with an empty/non-word
 *       right side (e.g. {@code foo(|)} or {@code foo(|}). Cycles through
 *       overloads of the callable named to the left of the paren and inserts
 *       the parameter names as a comma-separated argument list, completing
 *       the closing paren when necessary.</li>
 * </ul>
 *
 * <p>Both modes share the same lifecycle (cycle / accept / cancel / HUD),
 * tracked by a single session.
 */
final class MemberSuggester {

    enum Mode { IDENT, ARGS }

    /** A single suggestion. {@code insertText} is what goes into the buffer.
     *  {@code displayText} is what's shown in the HUD (may include a function
     *  name prefix or other context). {@code selectionEnd} is the offset
     *  within {@code insertText} where the autofilled selection ends — for
     *  ARGS this excludes a trailing {@code )} so it isn't selected.
     *  {@code firstArgEnd} is the offset where the first argument ends,
     *  used by accept in ARGS mode. */
    private record Candidate(String insertText, String displayText,
                             int selectionEnd, int firstArgEnd) {}

    private boolean active;
    private Mode mode = Mode.IDENT;
    private int sessionRow;
    private int replaceStart;
    private int originalEnd;             // end of replaced region in original buffer
    private String originalText;         // text we replaced (restored on cancel)
    private int selectionAnchorOffset;   // offset within candidate where selection begins
    private int cursorBeforeCol;
    private int lastSuggestionLen;
    private int expectedCursorCol;
    private List<Candidate> candidates = List.of();
    private int idx;

    /** True when a session is open AND the cursor is still at the expected
     *  position. If the user typed or moved the cursor, the session is no
     *  longer "live" — callers see false and the next cycle attempt starts
     *  a fresh session. */
    boolean isActive(TextArea ta) {
        if (!active) return false;
        if (ta.getCursorRow() != sessionRow) {
            active = false;
            return false;
        }
        if (ta.getCursorCol() != expectedCursorCol) {
            active = false;
            return false;
        }
        return true;
    }

    int matchCount() { return candidates.size(); }

    int currentIndex() { return idx; }

    String currentSuggestion() {
        return active && idx < candidates.size() ? candidates.get(idx).displayText() : "";
    }

    /** Up to {@code n} upcoming display labels (after the current one),
     *  wrapping around the candidate list. Useful for HUD previews. */
    List<String> upcoming(int n) {
        if (!active || candidates.size() <= 1) return List.of();
        List<String> out = new ArrayList<>(Math.min(n, candidates.size() - 1));
        int total = candidates.size();
        for (int i = 1; i <= n && i < total; i++) {
            out.add(candidates.get((idx + i) % total).displayText());
        }
        return out;
    }

    /** Cycle one step. {@code dir} is +1 (next) or -1 (prev). No-op when
     *  no candidates can be produced at the current cursor. */
    void cycle(TextArea ta, TypeGraph tg, String currentFile, int dir) {
        if (!isStillInSession(ta)) {
            startSession(ta, tg, currentFile);
        } else {
            idx = (idx + dir + candidates.size()) % candidates.size();
        }
        if (!active || candidates.isEmpty()) return;
        applyCurrent(ta);
    }

    /** Commit the displayed suggestion. Cursor goes to the end of the word
     *  in IDENT mode; in ARGS mode the first argument is selected so the user
     *  can type-replace it. A single undo entry covers the whole session. */
    void accept(TextArea ta) {
        accept(ta, false);
    }

    /** {@link #accept} variant that places the cursor at the start of the
     *  inserted region instead of the end (only meaningful in IDENT mode —
     *  ARGS mode always selects the first argument). Used so LEFT-arrow
     *  accept stops at the beginning of the completed word. */
    void accept(TextArea ta, boolean atStart) {
        if (!active) return;
        Candidate c = candidates.get(idx);
        int row = sessionRow;
        int finalEnd = replaceStart + c.insertText().length();

        if (!c.insertText().equals(originalText)) {
            ta.getUndoManager().record(
                    row, replaceStart, originalText, c.insertText(),
                    row, cursorBeforeCol,
                    row, finalEnd);
        }
        if (mode == Mode.ARGS && c.firstArgEnd() > 0) {
            // Select the first argument — anchor at args start, cursor at
            // first comma (or end of args if single arg).
            ta.selectRange(row, replaceStart,
                    row, replaceStart + c.firstArgEnd());
        } else if (atStart) {
            ta.setCursorPosition(row, replaceStart);
        } else {
            ta.setCursorPosition(row, finalEnd);
        }
        active = false;
    }

    /** Discard the cycled suggestion and restore the original buffer state.
     *  Cursor returns to where the user was when the session began. No undo
     *  entry is recorded because the buffer never visibly changed. */
    void cancel(TextArea ta) {
        if (!active) return;
        TextBuffer buf = ta.getBuffer();
        int currentEnd = replaceStart + lastSuggestionLen;
        if (lastSuggestionLen > 0) {
            buf.deleteRange(sessionRow, replaceStart, sessionRow, currentEnd);
            if (!originalText.isEmpty()) {
                buf.insertText(sessionRow, replaceStart, originalText);
            }
        }
        ta.setCursorPosition(sessionRow, cursorBeforeCol);
        active = false;
    }

    private boolean isStillInSession(TextArea ta) {
        return active
                && ta.getCursorRow() == sessionRow
                && ta.getCursorCol() == expectedCursorCol;
    }

    private void startSession(TextArea ta, TypeGraph tg, String currentFile) {
        active = false;
        candidates = List.of();
        lastSuggestionLen = 0;

        TextBuffer buf = ta.getBuffer();
        int row = ta.getCursorRow();
        int col = ta.getCursorCol();
        if (row >= buf.lineCount()) return;

        String line = buf.getLine(row);

        // Try ARGS context first; fall back to IDENT.
        if (tryStartArgsSession(tg, line, row, col)) {
            this.cursorBeforeCol = col;
            this.active = true;
            return;
        }
        tryStartIdentSession(tg, currentFile, line, row, col);
    }

    // ── ARGS mode ──────────────────────────────────────────────────────────

    /**
     * Fire ARGS mode when the cursor sits at the start of an empty argument
     * slot of an unclosed (or close-paren-terminated) call. Slot 0 is
     * "right after the {@code (}"; slot N is "right after the Nth comma".
     *
     * <p>Right side of the cursor must be whitespace followed by {@code )}
     * or end-of-line. A comma on the right means the user is mid-arg-list
     * and wants value suggestions, not param-name autofill — that case
     * falls through to IDENT.
     */
    private boolean tryStartArgsSession(TypeGraph tg, String line, int row, int col) {
        if (tg == null) return false;

        // Find the surrounding `(` by scanning left while balancing parens.
        int parenPos = findEnclosingOpenParen(line, col);
        if (parenPos < 0) return false;

        // The cursor must be at the start of an empty slot — the only
        // characters between the previous `(` or `,` and the cursor should
        // be whitespace. Anything else means content has been typed in
        // this slot, in which case IDENT handles completion.
        int p = col - 1;
        while (p > parenPos && Character.isWhitespace(line.charAt(p))) p--;
        if (p > parenPos && line.charAt(p) != ',') return false;

        // Right side: skip whitespace, then must be `)` or EOL. A comma on
        // the right means more args follow — the user wants identifier
        // suggestions for the current slot, not param-name fill.
        int q = col;
        while (q < line.length() && Character.isWhitespace(line.charAt(q))) q++;
        if (q < line.length()) {
            char nextCh = line.charAt(q);
            if (nextCh != ')') return false;
        }
        boolean closeParenExists = q < line.length() && line.charAt(q) == ')';

        // Identifier immediately left of the enclosing `(`.
        int idEnd = parenPos;
        int idStart = idEnd;
        while (idStart > 0 && isIdentChar(line.charAt(idStart - 1))) idStart--;
        if (idStart == idEnd) return false;
        String name = line.substring(idStart, idEnd);

        // Slot index: count commas between `(` and the cursor.
        int slot = 0;
        for (int i = parenPos + 1; i < col; i++) {
            if (line.charAt(i) == ',') slot++;
        }

        List<Candidate> built = buildArgCandidates(tg, name, slot, !closeParenExists);
        if (built.isEmpty()) return false;

        this.mode = Mode.ARGS;
        this.candidates = built;
        this.sessionRow = row;
        this.replaceStart = col;
        this.originalEnd = col;
        this.originalText = "";
        this.selectionAnchorOffset = 0;
        this.idx = 0;
        return true;
    }

    /** Build ARGS candidates that fill the function's parameter list from
     *  {@code slot} onward. When {@code slot > 0}, only the remaining params
     *  are emitted (e.g., slot 1 of {@code foo(x, y, z)} fills {@code y, z}).
     *  Overloads with fewer than {@code slot+1} params are skipped. */
    private List<Candidate> buildArgCandidates(TypeGraph tg, String name,
                                               int slot, boolean appendCloseParen) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TypeGraph.Node n : tg.lookup(name)) {
            List<String> paramNames = paramNamesOf(n);
            if (paramNames == null || slot >= paramNames.size()) continue;

            List<String> remaining = paramNames.subList(slot, paramNames.size());
            String args = String.join(", ", remaining);
            // Dedupe overloads that produce identical arg-name lists.
            if (!seen.add(args)) continue;

            String insertText = appendCloseParen ? args + ")" : args;
            String prefix = slot > 0 ? "..., " : "";
            String display = name + "(" + prefix + args + ")";
            int selectionEnd = args.length();
            int firstArgEnd = remaining.get(0).length();
            out.add(new Candidate(insertText, display, selectionEnd, firstArgEnd));
        }
        return out;
    }

    /**
     * If the cursor sits inside the parens of a known function with at least
     * one non-whitespace character already typed in the call, return a
     * formatted signature like {@code "foo(x, y, z)"} for HUD display. The
     * current slot (counted from the surrounding {@code (}) is wrapped in
     * brackets so the user can see which arg they're filling — e.g.,
     * {@code "circle(x, [y], r)"}.
     *
     * <p>Returns {@code null} when not in such a position, so the caller can
     * fall through to default HUD content.
     */
    static String signatureHintAt(TextArea ta, TypeGraph tg) {
        if (tg == null) return null;
        TextBuffer buf = ta.getBuffer();
        int row = ta.getCursorRow();
        int col = ta.getCursorCol();
        if (row >= buf.lineCount()) return null;
        String line = buf.getLine(row);

        int parenPos = findEnclosingOpenParen(line, col);
        if (parenPos < 0) return null;

        // Function name immediately left of the `(`.
        int idEnd = parenPos;
        int idStart = idEnd;
        while (idStart > 0 && isIdentChar(line.charAt(idStart - 1))) idStart--;
        if (idStart == idEnd) return null;
        String name = line.substring(idStart, idEnd);

        // The user must have typed something inside this paren group.
        boolean hasContent = false;
        for (int i = parenPos + 1; i < col; i++) {
            if (!Character.isWhitespace(line.charAt(i))) { hasContent = true; break; }
        }
        if (!hasContent) {
            // Also check forward from cursor in case content sits to the right.
            for (int i = col; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ')') break;
                if (!Character.isWhitespace(c)) { hasContent = true; break; }
            }
        }
        if (!hasContent) return null;

        // Look up param names. Prefer the unique overload — multiple overloads
        // would each need their own line, which doesn't fit the HUD format.
        List<String> paramNames = null;
        for (TypeGraph.Node n : tg.lookup(name)) {
            List<String> p = paramNamesOf(n);
            if (p == null) continue;
            if (paramNames != null && !paramNames.equals(p)) {
                // Conflicting overloads — give up rather than show the wrong one.
                return null;
            }
            paramNames = p;
        }
        if (paramNames == null || paramNames.isEmpty()) return null;

        // Slot index: count commas between `(` and cursor.
        int slot = 0;
        for (int i = parenPos + 1; i < col; i++) {
            if (line.charAt(i) == ',') slot++;
        }

        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) sb.append(", ");
            if (i == slot && slot < paramNames.size()) {
                sb.append('[').append(paramNames.get(i)).append(']');
            } else {
                sb.append(paramNames.get(i));
            }
        }
        sb.append(')');
        return sb.toString();
    }

    /** Scan left from {@code col} to find the position of an unmatched open
     *  paren on this line. Returns -1 if none. Balances {@code ()} pairs so
     *  nested calls resolve to the innermost surrounding paren. */
    private static int findEnclosingOpenParen(String line, int col) {
        int depth = 0;
        for (int i = col - 1; i >= 0; i--) {
            char c = line.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    /** Parameter names for a callable node, or null if the node isn't callable
     *  in a way that exposes named parameters. Falls back to positional names
     *  ({@code _0, _1, ...}) when the function descriptor has unnamed slots. */
    private static List<String> paramNamesOf(TypeGraph.Node n) {
        SpnFunctionDescriptor fd = n.functionDescriptor();
        if (fd != null) {
            List<String> names = new ArrayList<>(fd.arity());
            for (int i = 0; i < fd.arity(); i++) {
                FieldDescriptor p = fd.getParams()[i];
                String pname = p.name();
                if (pname == null || pname.isEmpty()) pname = "_" + i;
                names.add(pname);
            }
            return names;
        }
        SpnStructDescriptor sd = n.structDescriptor();
        if (sd != null) {
            FieldDescriptor[] fields = sd.getFields();
            List<String> names = new ArrayList<>(fields.length);
            for (int i = 0; i < fields.length; i++) {
                String fname = fields[i].name();
                if (fname == null || fname.isEmpty()) fname = "_" + i;
                names.add(fname);
            }
            return names;
        }
        return null;
    }

    // ── IDENT mode ─────────────────────────────────────────────────────────

    private void tryStartIdentSession(TypeGraph tg, String currentFile,
                                      String line, int row, int col) {
        // Word boundaries around the cursor — extend in both directions so a
        // mid-word cursor still completes the surrounding identifier.
        int ws = col, we = col;
        while (ws > 0 && isIdentChar(line.charAt(ws - 1))) ws--;
        while (we < line.length() && isIdentChar(line.charAt(we))) we++;
        String word = line.substring(ws, we);
        int pLen = Math.max(0, col - ws);
        String partial = word.substring(0, pLen);

        // Receiver: a `.` immediately before the word, with a plain identifier
        // before that. Chained accesses (`a.b.c`) only see the last segment.
        String receiver = null;
        if (ws > 0 && line.charAt(ws - 1) == '.') {
            int re = ws - 1;
            int rs = re;
            while (rs > 0 && isIdentChar(line.charAt(rs - 1))) rs--;
            if (rs < re) receiver = line.substring(rs, re);
        }

        List<String> names = buildIdentCandidates(tg, currentFile, row, receiver, partial);
        if (names.isEmpty()) return;

        List<Candidate> built = new ArrayList<>(names.size());
        for (String s : names) {
            built.add(new Candidate(s, s, s.length(), 0));
        }

        this.mode = Mode.IDENT;
        this.candidates = built;
        this.sessionRow = row;
        this.replaceStart = ws;
        this.originalEnd = we;
        this.originalText = word;
        this.selectionAnchorOffset = pLen;
        this.cursorBeforeCol = col;
        this.idx = 0;

        // If the original word is itself a candidate, start cycling from one
        // past it so the first press feels like "give me something different".
        for (int i = 0; i < built.size(); i++) {
            if (built.get(i).insertText().equals(word)) {
                this.idx = i;
                break;
            }
        }
        this.active = true;
    }

    private void applyCurrent(TextArea ta) {
        Candidate c = candidates.get(idx);
        int row = sessionRow;
        int currentEnd = lastSuggestionLen > 0
                ? replaceStart + lastSuggestionLen
                : originalEnd;

        // Raw buffer mutation — no per-cycle undo entry. The session as a
        // whole is captured in one undo entry on accept (or simply reverted
        // on cancel without leaving any trail).
        TextBuffer buf = ta.getBuffer();
        if (currentEnd > replaceStart) {
            buf.deleteRange(row, replaceStart, row, currentEnd);
        }
        buf.insertText(row, replaceStart, c.insertText());

        // Leave the autofilled portion selected so the next keystroke replaces
        // it and a follow-up Ctrl+. keeps us anchored at the same origin.
        int anchor = replaceStart + selectionAnchorOffset;
        int end = replaceStart + c.selectionEnd();
        ta.selectRange(row, anchor, row, end);

        lastSuggestionLen = c.insertText().length();
        expectedCursorCol = end;
    }

    private List<String> buildIdentCandidates(TypeGraph tg, String currentFile,
                                              int line, String receiver, String partial) {
        if (tg == null) return List.of();

        Set<String> raw = new LinkedHashSet<>();
        if (receiver != null) {
            // Restrict to the receiver's members when the receiver name is
            // itself a known type. Otherwise fall back to the union of all
            // member names — useful enough until we plumb local-type info.
            boolean receiverIsType = false;
            for (TypeGraph.Node n : tg.lookup(receiver)) {
                TypeGraph.Kind k = n.kind();
                if (k == TypeGraph.Kind.TYPE || k == TypeGraph.Kind.STRUCT
                        || k == TypeGraph.Kind.VARIANT) {
                    receiverIsType = true;
                    break;
                }
            }
            for (TypeGraph.Kind k : new TypeGraph.Kind[] {
                    TypeGraph.Kind.METHOD, TypeGraph.Kind.FIELD,
                    TypeGraph.Kind.CONSTANT, TypeGraph.Kind.FACTORY }) {
                for (TypeGraph.Node n : tg.byKind(k)) {
                    String name = n.name();
                    int dot = name.indexOf('.');
                    if (dot <= 0 || dot == name.length() - 1) continue;
                    String prefix = name.substring(0, dot);
                    String member = name.substring(dot + 1);
                    if (receiverIsType && !prefix.equals(receiver)) continue;
                    if (!isPlainIdent(member)) continue;
                    raw.add(member);
                }
            }
        } else {
            for (TypeGraph.Kind k : new TypeGraph.Kind[] {
                    TypeGraph.Kind.FUNCTION, TypeGraph.Kind.TYPE,
                    TypeGraph.Kind.STRUCT, TypeGraph.Kind.VARIANT,
                    TypeGraph.Kind.MACRO, TypeGraph.Kind.BUILTIN,
                    TypeGraph.Kind.FACTORY }) {
                for (TypeGraph.Node n : tg.byKind(k)) {
                    String name = n.name();
                    if (isPlainIdent(name)) raw.add(name);
                }
            }
            // Locals declared at or before the cursor in this file. Parser
            // ranges are 1-based; editor `line` is 0-based.
            if (currentFile != null) {
                int parserLine = line + 1;
                for (TypeGraph.Kind k : new TypeGraph.Kind[] {
                        TypeGraph.Kind.LOCAL_BINDING, TypeGraph.Kind.PARAMETER }) {
                    for (TypeGraph.Node n : tg.byKind(k)) {
                        if (!currentFile.equals(n.file())) continue;
                        if (!isPlainIdent(n.name())) continue;
                        if (n.nameRange().isKnown()
                                && n.nameRange().startLine() <= parserLine) {
                            raw.add(n.name());
                        }
                    }
                }
            }
        }

        String partialLower = partial.toLowerCase();
        List<String> filtered = new ArrayList<>();
        for (String name : raw) {
            if (partialLower.isEmpty() || name.toLowerCase().startsWith(partialLower)) {
                filtered.add(name);
            }
        }
        filtered.sort((a, b) -> {
            boolean aCase = !partial.isEmpty() && a.startsWith(partial);
            boolean bCase = !partial.isEmpty() && b.startsWith(partial);
            if (aCase != bCase) return aCase ? -1 : 1;
            return a.compareToIgnoreCase(b);
        });
        return filtered;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isPlainIdent(String s) {
        if (s == null || s.isEmpty()) return false;
        if (Character.isDigit(s.charAt(0))) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isIdentChar(s.charAt(i))) return false;
        }
        return true;
    }
}
