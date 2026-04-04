package spn.language;

import java.util.List;

/**
 * Parsed representation of an import statement, before resolution.
 *
 * <pre>
 *   import Math                          → full unqualified
 *   import Math (abs, sqrt)              → selective
 *   import Math as M                     → qualified
 *   import String (join as glue)         → selective with alias
 *   import spn.mylib.utils               → FQ namespace
 *   import spn.mylib.utils as U          → FQ qualified
 * </pre>
 *
 * @param modulePath      the module path ("Math" or "spn.mylib.utils")
 * @param selectiveNames  null = import all; non-null = import only these names
 * @param qualifier       null = unqualified; non-null = qualified prefix (e.g., "M")
 */
public record ImportDirective(
        String modulePath,
        List<ImportedName> selectiveNames,
        String qualifier
) {
    /**
     * A single imported name, optionally aliased.
     *
     * @param name  the exported name in the source module
     * @param alias the local name (null = same as name)
     */
    public record ImportedName(String name, String alias) {
        /** Returns the local name this import binds to. */
        public String localName() {
            return alias != null ? alias : name;
        }
    }

    /** True if this import uses a qualifier (import X as Q). */
    public boolean isQualified() {
        return qualifier != null;
    }

    /** True if this import selects specific names (import X (a, b)). */
    public boolean isSelective() {
        return selectiveNames != null;
    }
}
