package spn.stdlib.file;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lists the entries in a directory, returning an array of path strings.
 * Each entry is the full path (directory + filename).
 */
@SpnBuiltin(name = "listDir", module = "File", params = {"path"}, returns = "Array", pure = false)
@NodeChild("path")
@NodeInfo(shortName = "listDir")
public abstract class SpnListDirNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue listDir(String path) {
        try (var stream = Files.list(Path.of(path))) {
            Object[] entries = stream
                    .map(p -> (Object) p.toString().replace('\\', '/'))
                    .toArray();
            return new SpnArrayValue(FieldType.STRING, entries);
        } catch (IOException e) {
            throw new SpnException("listDir failed: " + e.getMessage(), this);
        }
    }

    @Fallback
    protected Object typeError(Object path) {
        throw new SpnException("listDir expects a string path", this);
    }
}
