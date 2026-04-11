package spn.stdlib.file;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a string to a file (creates or overwrites). UTF-8 encoding.
 */
@SpnBuiltin(name = "writeFile", module = "File", params = {"path", "content"}, returns = "Boolean", pure = false)
@NodeChild("path")
@NodeChild("content")
@NodeInfo(shortName = "writeFile")
public abstract class SpnWriteFileNode extends SpnExpressionNode {

    @Specialization
    protected boolean writeFile(String path, String content) {
        try {
            Files.writeString(Path.of(path), content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new SpnException("writeFile failed: " + e.getMessage(), this);
        }
    }

    @Fallback
    protected Object typeError(Object path, Object content) {
        throw new SpnException("writeFile expects (string, string)", this);
    }
}
