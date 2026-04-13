package spn.type;

import spn.node.match.MatchPattern;

/**
 * Groups SpnStructDescriptors as the variants of a sum type (algebraic data type).
 *
 * A variant set declares: "these are ALL the possible constructors for this type."
 * The totality checker uses this to verify that pattern matching is exhaustive --
 * a match over a Shape value must cover Circle, Rectangle, AND Triangle.
 *
 * <pre>
 *   var SHAPE = new SpnVariantSet("Shape", CIRCLE, RECTANGLE, TRIANGLE);
 * </pre>
 *
 * Note: the variant set is a separate concept from the struct descriptors themselves.
 * A struct descriptor like CIRCLE doesn't "know" it belongs to Shape. The grouping
 * is external, declared wherever the sum type is defined. This allows a struct to
 * participate in multiple sum types if needed.
 */
public final class SpnVariantSet {

    private final String name;
    private final SpnStructDescriptor[] variants;

    public SpnVariantSet(String name, SpnStructDescriptor... variants) {
        this.name = name;
        this.variants = variants;
    }

    public String getName() {
        return name;
    }

    public SpnStructDescriptor[] getVariants() {
        return variants;
    }

    public int size() {
        return variants.length;
    }

    /**
     * Checks whether the given set of match patterns covers every variant.
     * Returns true if for each variant, at least one pattern matches it.
     * Wildcard and OfType patterns are considered to cover all variants.
     */
    public boolean isCoveredBy(MatchPattern[] patterns) {
        // A wildcard or universal type pattern covers everything
        for (MatchPattern p : patterns) {
            if (p instanceof MatchPattern.Wildcard) return true;
            if (p instanceof MatchPattern.OfType ot
                    && ot.fieldType() instanceof FieldType.Untyped) return true;
        }

        // Check that every variant has at least one matching pattern
        outer:
        for (SpnStructDescriptor variant : variants) {
            for (MatchPattern p : patterns) {
                if (coversVariant(p, variant)) continue outer;
            }
            return false; // this variant is not covered
        }
        return true;
    }

    /** Returns true if the pattern covers the given variant. */
    private static boolean coversVariant(MatchPattern p, SpnStructDescriptor variant) {
        if (p instanceof MatchPattern.Struct sp) return sp.descriptor() == variant;
        if (p instanceof MatchPattern.StructDestructure sd) return sd.descriptor() == variant;
        if (p instanceof MatchPattern.OfType ot
                && ot.fieldType() instanceof FieldType.OfStruct os) return os.descriptor() == variant;
        return false;
    }

    /**
     * Returns the list of variants not covered by the given patterns.
     * Useful for error messages: "missing patterns for: Triangle".
     */
    public SpnStructDescriptor[] uncoveredVariants(MatchPattern[] patterns) {
        // Quick check: wildcard covers all
        for (MatchPattern p : patterns) {
            if (p instanceof MatchPattern.Wildcard) return new SpnStructDescriptor[0];
        }

        var missing = new java.util.ArrayList<SpnStructDescriptor>();
        outer:
        for (SpnStructDescriptor variant : variants) {
            for (MatchPattern p : patterns) {
                if (coversVariant(p, variant)) continue outer;
            }
            missing.add(variant);
        }
        return missing.toArray(new SpnStructDescriptor[0]);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(name).append(" = ");
        for (int i = 0; i < variants.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(variants[i].getName());
        }
        return sb.toString();
    }
}
