package spn.node.struct;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.FieldDescriptor;
import spn.type.FieldType;
import spn.type.SpnStructDescriptor;
import spn.type.SpnStructValue;

/**
 * Constructs an immutable SpnStructValue from field expression nodes,
 * validating each field value against its declared FieldType.
 *
 * If the struct descriptor has typed fields, each value is checked at runtime
 * against its FieldType. Untyped fields (FieldType.Untyped) skip validation.
 * If all fields are untyped, no validation overhead is incurred.
 *
 * Type validation is compiled away for untyped structs: the hasTypedFields flag
 * is @CompilationFinal, so Graal eliminates the validation branch entirely.
 */
@NodeInfo(shortName = "structConstruct")
public final class SpnStructConstructNode extends SpnExpressionNode {

    @Children private final SpnExpressionNode[] fieldNodes;
    private final SpnStructDescriptor descriptor;

    @CompilationFinal(dimensions = 1)
    private final FieldType[] fieldTypes;

    @CompilationFinal
    private final boolean needsValidation;

    public SpnStructDescriptor getDescriptor() { return descriptor; }

    public SpnStructConstructNode(SpnStructDescriptor descriptor,
                                  SpnExpressionNode... fieldNodes) {
        this.descriptor = descriptor;
        this.fieldNodes = fieldNodes;

        FieldDescriptor[] fields = descriptor.getFields();
        this.fieldTypes = new FieldType[fields.length];
        boolean anyTyped = false;
        for (int i = 0; i < fields.length; i++) {
            this.fieldTypes[i] = fields[i].type();
            if (fields[i].isTyped()) anyTyped = true;
        }
        this.needsValidation = anyTyped;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] values = new Object[fieldNodes.length];
        for (int i = 0; i < fieldNodes.length; i++) {
            values[i] = fieldNodes[i].executeGeneric(frame);
        }

        if (needsValidation) {
            validateFields(values);
        }

        return new SpnStructValue(descriptor, values);
    }

    @ExplodeLoop
    private void validateFields(Object[] values) {
        for (int i = 0; i < fieldTypes.length; i++) {
            if (!fieldTypes[i].accepts(values[i])) {
                throw new SpnException(
                        "Field '" + descriptor.getFields()[i].name()
                                + "' of " + descriptor.getName()
                                + " expects " + fieldTypes[i].describe()
                                + ", got " + values[i].getClass().getSimpleName()
                                + " (" + values[i] + ")",
                        this);
            }
        }
    }
}
