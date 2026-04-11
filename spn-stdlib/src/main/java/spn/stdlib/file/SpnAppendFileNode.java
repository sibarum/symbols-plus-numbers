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
import java.nio.file.StandardOpenOption;

/**
 * Appends a string to a file (creates if it doesn't exist). UTF-8 encoding.
 */
@SpnBuiltin(name = "appendFile", module = "File", params = {"path", "content"}, returns = "Boolean", pure = false)
@NodeChild("path")
@NodeChild("content")
@NodeInfo(shortName = "appendFile")
public abstract class SpnAppendFileNode extends SpnExpressionNode {

    @Specialization
    protected boolean appendFile(String path, String content) {
        try {
            Files.writeString(Path.of(path), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            throw new SpnException("appendFile failed: " + e.getMessage(), this);
        }
    }

    @Fallback
    protected Object typeError(Object path, Object content) {
        throw new SpnException("appendFile expects (string, string)", this);
    }
}
