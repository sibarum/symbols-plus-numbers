package spn.stdlib.file;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Returns true if a file or directory exists at the given path.
 */
@SpnBuiltin(name = "fileExists", module = "File", params = {"path"}, returns = "Boolean", pure = false)
@NodeChild("path")
@NodeInfo(shortName = "fileExists")
public abstract class SpnFileExistsNode extends SpnExpressionNode {

    @Specialization
    protected boolean fileExists(String path) {
        return Files.exists(Path.of(path));
    }

    @Fallback
    protected Object typeError(Object path) {
        throw new SpnException("fileExists expects a string path", this);
    }
}
