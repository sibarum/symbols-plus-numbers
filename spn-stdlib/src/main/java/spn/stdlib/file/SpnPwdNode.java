package spn.stdlib.file;

import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

import java.nio.file.Path;

/**
 * Returns the current working directory as a string with forward slashes.
 */
@SpnBuiltin(name = "pwd", module = "File", params = {}, returns = "String", pure = false)
@NodeInfo(shortName = "pwd")
public class SpnPwdNode extends SpnExpressionNode {

    @Override
    public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
        return Path.of("").toAbsolutePath().toString().replace('\\', '/');
    }
}
