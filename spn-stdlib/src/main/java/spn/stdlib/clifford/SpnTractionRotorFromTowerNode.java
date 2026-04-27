package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "tractionRotorFromTower", module = "Clifford",
        params = {"a", "b", "c", "d"}, returns = "TractionRotor")
@NodeChild("a")
@NodeChild("b")
@NodeChild("c")
@NodeChild("d")
@NodeInfo(shortName = "tractionRotorFromTower")
public abstract class SpnTractionRotorFromTowerNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor fromTower(double a, double b, double c, double d) {
        return TractionRotor.fromTower(a, b, c, d);
    }

    @Fallback
    protected Object typeError(Object a, Object b, Object c, Object d) {
        throw new SpnException("tractionRotorFromTower expects four numbers", this);
    }
}
