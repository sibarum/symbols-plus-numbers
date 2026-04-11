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
 * Deletes a file or empty directory. Returns true if deleted, false if it didn't exist.
 */
@SpnBuiltin(name = "deleteFile", module = "File", params = {"path"}, returns = "Boolean", pure = false)
@NodeChild("path")
@NodeInfo(shortName = "deleteFile")
public abstract class SpnDeleteFileNode extends SpnExpressionNode {

    @Specialization
    protected boolean deleteFile(String path) {
        try {
            return Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            throw new SpnException("deleteFile failed: " + e.getMessage(), this);
        }
    }

    @Fallback
    protected Object typeError(Object path) {
        throw new SpnException("deleteFile expects a string path", this);
    }
}
