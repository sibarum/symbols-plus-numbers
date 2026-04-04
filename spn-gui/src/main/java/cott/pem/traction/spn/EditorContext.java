package cott.pem.traction.spn;

/** Semantic context at the cursor position, used to select available suggestions. */
public enum EditorContext {
    TOP_LEVEL,
    FUNCTION_BODY,
    TYPE_BODY,
    MATCH_BODY,
    WHILE_BODY,
    BLOCK
}
