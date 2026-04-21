package spn.language;

import spn.source.SourceRange;

/**
 * Source position of a type declaration, as exported from an SPN module.
 *
 * <p>Populated by module loaders that parse SPN source (e.g.,
 * {@code FilesystemModuleLoader}) and consumed by the parser's
 * go-to-definition plumbing: when a file imports a type from another
 * module, this record tells the IDE where the declaration's NAME token
 * lives in the defining file. Ranges are in parser convention (1-based
 * line, 0-based col). {@code file} may be null when the declaring module
 * has no source (native/builtin).
 */
public record TypeDeclPos(String file, SourceRange range) {}
