package spn.stdlib.general;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.*;

@SpnBuiltin(name = "typeof", module = "General", params = {"value"}, returns = "String")
@NodeChild("value")
@NodeInfo(shortName = "typeof")
public abstract class SpnTypeofNode extends SpnExpressionNode {

    @Specialization
    protected String typeofLong(long value) { return "Int"; }

    @Specialization
    protected String typeofDouble(double value) { return "Float"; }

    @Specialization
    protected String typeofBoolean(boolean value) { return "Bool"; }

    @Specialization
    protected String typeofString(String value) { return "String"; }

    @Specialization
    protected String typeofSymbol(SpnSymbol value) { return "Symbol"; }

    @Specialization
    protected String typeofArray(SpnArrayValue value) { return "Array"; }

    @Specialization
    protected String typeofSet(SpnSetValue value) { return "Set"; }

    @Specialization
    protected String typeofDict(SpnDictionaryValue value) { return "Dict"; }

    @Specialization
    protected String typeofStruct(SpnStructValue value) {
        return value.getDescriptor().getName();
    }

    @Fallback
    protected String typeofOther(Object value) {
        return value.getClass().getSimpleName();
    }
}
