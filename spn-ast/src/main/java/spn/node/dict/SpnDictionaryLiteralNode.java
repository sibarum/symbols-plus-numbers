package spn.node.dict;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.FieldType;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSymbol;

import java.util.LinkedHashMap;

/**
 * Constructs an immutable SpnDictionaryValue from key-value expression pairs.
 *
 * Keys are SpnSymbol constants (known at AST construction time). Values are
 * arbitrary expressions evaluated at runtime. If the value type is concrete,
 * each value is validated.
 *
 * <pre>
 *   // {:name "Alice", :age 30}
 *   new SpnDictionaryLiteralNode(FieldType.UNTYPED,
 *       new SpnSymbol[]{nameSymbol, ageSymbol},
 *       new SpnExpressionNode[]{stringLiteral("Alice"), longLiteral(30)});
 * </pre>
 */
@NodeInfo(shortName = "dictLiteral")
public final class SpnDictionaryLiteralNode extends SpnExpressionNode {

    @CompilationFinal(dimensions = 1)
    private final SpnSymbol[] keys;

    @Children private final SpnExpressionNode[] valueNodes;

    @CompilationFinal
    private final FieldType valueType;

    @CompilationFinal
    private final boolean needsValidation;

    public SpnDictionaryLiteralNode(FieldType valueType, SpnSymbol[] keys,
                                     SpnExpressionNode[] valueNodes) {
        this.valueType = valueType;
        this.keys = keys;
        this.valueNodes = valueNodes;
        this.needsValidation = !(valueType instanceof FieldType.Untyped);
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        var map = new LinkedHashMap<SpnSymbol, Object>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            Object value = valueNodes[i].executeGeneric(frame);
            if (needsValidation && !valueType.accepts(value)) {
                throw new SpnException(
                        "Dictionary value for key :" + keys[i].name()
                                + " expects " + valueType.describe()
                                + ", got " + SpnTypeName.of(value),
                        this);
            }
            map.put(keys[i], value);
        }
        return SpnDictionaryValue.wrap(valueType, map);
    }
}
