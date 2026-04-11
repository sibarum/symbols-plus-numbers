package spn.stdlib.file;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates a directory and all necessary parent directories (like mkdir -p).
 * Returns true on success. No error if the directory already exists.
 */
@SpnBuiltin(name = "mkdirs", module = "File", params = {"path"}, returns = "Boolean", pure = false)
@NodeChild("path")
@NodeInfo(shortName = "mkdirs")
public abstract class SpnMkdirsNode extends SpnExpressionNode {

    @Specialization
    protected boolean mkdirs(String path) {
        try {
            Files.createDirectories(Path.of(path));
            return true;
        } catch (IOException e) {
            throw new SpnException("mkdirs failed: " + e.getMessage(), this);
        }
    }

    @Fallback
    protected Object typeError(Object path) {
        throw new SpnException("mkdirs expects a string path", this);
    }
}
