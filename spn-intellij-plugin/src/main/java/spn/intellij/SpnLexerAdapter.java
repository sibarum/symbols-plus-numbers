package spn.intellij;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import spn.lang.SpnLexer;
import spn.lang.Token;

import java.util.List;

/**
 * Adapts SpnLexer (line-local, batch) to IntelliJ's incremental Lexer interface.
 *
 * IntelliJ calls advance() repeatedly and expects getTokenType()/getTokenStart()/
 * getTokenEnd() to describe each token. We tokenize the full buffer up front and
 * then iterate through the flat list.
 */
public final class SpnLexerAdapter extends LexerBase {

    private final SpnLexer lexer = new SpnLexer();

    private CharSequence buffer;
    private int bufferEnd;

    // Flat list of (startOffset, endOffset, IElementType) triples
    private int[] startOffsets;
    private int[] endOffsets;
    private IElementType[] types;
    private int tokenCount;
    private int tokenIndex;

    @Override
    public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.bufferEnd = endOffset;

        String text = buffer.subSequence(startOffset, endOffset).toString();
        tokenize(text, startOffset);
        this.tokenIndex = 0;
    }

    private void tokenize(String text, int baseOffset) {
        String[] lines = text.split("\n", -1);

        // Estimate capacity
        int capacity = Math.max(16, text.length() / 3);
        startOffsets = new int[capacity];
        endOffsets = new int[capacity];
        types = new IElementType[capacity];
        tokenCount = 0;

        int lineStart = baseOffset;
        for (String line : lines) {
            List<Token> lineTokens = lexer.tokenizeLine(line);
            for (Token tok : lineTokens) {
                ensureCapacity();
                startOffsets[tokenCount] = lineStart + tok.startCol();
                endOffsets[tokenCount] = lineStart + tok.endCol();
                types[tokenCount] = SpnTokenTypes.get(tok.type());
                tokenCount++;
            }
            lineStart += line.length() + 1; // +1 for \n
        }
    }

    private void ensureCapacity() {
        if (tokenCount >= startOffsets.length) {
            int newLen = startOffsets.length * 2;
            int[] newStarts = new int[newLen];
            int[] newEnds = new int[newLen];
            IElementType[] newTypes = new IElementType[newLen];
            System.arraycopy(startOffsets, 0, newStarts, 0, tokenCount);
            System.arraycopy(endOffsets, 0, newEnds, 0, tokenCount);
            System.arraycopy(types, 0, newTypes, 0, tokenCount);
            startOffsets = newStarts;
            endOffsets = newEnds;
            types = newTypes;
        }
    }

    @Override
    public int getState() {
        return 0; // stateless — each line is independent
    }

    @Override
    public IElementType getTokenType() {
        if (tokenIndex >= tokenCount) return null;
        return types[tokenIndex];
    }

    @Override
    public int getTokenStart() {
        if (tokenIndex >= tokenCount) return bufferEnd;
        return startOffsets[tokenIndex];
    }

    @Override
    public int getTokenEnd() {
        if (tokenIndex >= tokenCount) return bufferEnd;
        return endOffsets[tokenIndex];
    }

    @Override
    public void advance() {
        tokenIndex++;
    }

    @Override
    public CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return bufferEnd;
    }
}
