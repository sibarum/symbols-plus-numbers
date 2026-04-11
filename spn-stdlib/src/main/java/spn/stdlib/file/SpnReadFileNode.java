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
 * Reads a file's entire contents as a UTF-8 string.
 */
@SpnBuiltin(name = "readFile", module = "File", params = {"path"}, returns = "String", pure = false)
@NodeChild("path")
@NodeInfo(shortName = "readFile")
public abstract class SpnReadFileNode extends SpnExpressionNode {

    @Specialization
    protected String readFile(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SpnException("readFile failed: " + e.getMessage(), this);
        }
    }

    @Fallback
    protected Object typeError(Object path) {
        throw new SpnException("readFile expects a string path", this);
    }
}
