package spn.type.check;

import com.oracle.truffle.api.nodes.Node;
import spn.node.match.MatchPattern;
import spn.node.match.SpnMatchBranchNode;
import spn.node.match.SpnMatchNode;
import spn.type.SpnStructDescriptor;
import spn.type.SpnVariantSet;

import java.util.ArrayList;
import java.util.List;

import static spn.type.check.Diagnostic.Category;

/**
 * Validates that a function body is total -- every match expression is exhaustive.
 *
 * A pure function must be total: it must produce a value for every possible input.
 * Since SPN functions use pattern matching for control flow, totality reduces to
 * exhaustiveness of match expressions.
 *
 * The checker walks the AST, finds all SpnMatchNode instances, and verifies each:
 *
 *   1. If any branch has a Wildcard pattern → exhaustive (trivially total)
 *   2. If the branches' Struct patterns cover all variants of a known SpnVariantSet
 *      → exhaustive (all constructors handled)
 *   3. Otherwise → non-exhaustive → ERROR diagnostic with the missing variants
 *
 * Usage:
 * <pre>
 *   var SHAPE = new SpnVariantSet("Shape", CIRCLE, RECTANGLE, TRIANGLE);
 *
 *   var diagnostics = TotalityChecker.check(functionBody, SHAPE);
 *   if (TotalityChecker.hasErrors(diagnostics)) {
 *       // function is not total -- report missing patterns
 *   }
 * </pre>
 *
 * Limitations:
 *   - Does not check nested patterns (future work)
 *   - Does not reason about guard exhaustiveness (guards make totality undecidable)
 *   - Requires explicit SpnVariantSet declarations to check struct coverage
 *   - Match nodes not associated with any variant set get a warning (not an error)
 */
public final class TotalityChecker {

    private TotalityChecker() {
    }

    /**
     * Checks all match expressions in the given AST for exhaustiveness.
     *
     * @param body          the function body (root of the AST subtree to check)
     * @param variantSets   the known sum types whose variants must be fully covered
     * @return list of diagnostics (errors for non-exhaustive matches, info for exhaustive ones)
     */
    public static List<Diagnostic> check(Node body, SpnVariantSet... variantSets) {
        var diagnostics = new ArrayList<Diagnostic>();
        walkAndCheck(body, variantSets, diagnostics);
        return diagnostics;
    }

    /** Returns true if any diagnostic is an ERROR. */
    public static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }

    // ── AST walking ─────────────────────────────────────────────────────────

    private static void walkAndCheck(Node node, SpnVariantSet[] variantSets,
                                     List<Diagnostic> diagnostics) {
        if (node instanceof SpnMatchNode matchNode) {
            checkMatchExhaustiveness(matchNode, variantSets, diagnostics);
        }

        for (Node child : node.getChildren()) {
            if (child != null) {
                walkAndCheck(child, variantSets, diagnostics);
            }
        }
    }

    // ── Match exhaustiveness ────────────────────────────────────────────────

    private static void checkMatchExhaustiveness(SpnMatchNode matchNode,
                                                  SpnVariantSet[] variantSets,
                                                  List<Diagnostic> diagnostics) {
        SpnMatchBranchNode[] branches = matchNode.getBranches();
        MatchPattern[] patterns = extractPatterns(branches);

        // Quick check: wildcard covers everything
        if (hasWildcard(patterns)) {
            diagnostics.add(Diagnostic.info(Category.FEASIBILITY,
                    "Match expression has a wildcard default — trivially exhaustive."));
            return;
        }

        // Check against each known variant set
        boolean checkedAgainstAny = false;
        for (SpnVariantSet vs : variantSets) {
            if (isRelevant(patterns, vs)) {
                checkedAgainstAny = true;
                if (vs.isCoveredBy(patterns)) {
                    diagnostics.add(Diagnostic.info(Category.FEASIBILITY,
                            "Match expression covers all variants of " + vs.getName() + "."));
                } else {
                    SpnStructDescriptor[] missing = vs.uncoveredVariants(patterns);
                    var names = new StringBuilder();
                    for (int i = 0; i < missing.length; i++) {
                        if (i > 0) names.append(", ");
                        names.append(missing[i].getName());
                    }
                    diagnostics.add(Diagnostic.error(Category.EMPTY_TYPE,
                            "Non-exhaustive match: missing patterns for " + vs.getName()
                                    + " variant(s): " + names + "."));
                }
            }
        }

        if (!checkedAgainstAny) {
            diagnostics.add(Diagnostic.warning(Category.FEASIBILITY,
                    "Match expression has no wildcard default and could not be checked "
                            + "against any known variant set. It may fail at runtime."));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static MatchPattern[] extractPatterns(SpnMatchBranchNode[] branches) {
        MatchPattern[] patterns = new MatchPattern[branches.length];
        for (int i = 0; i < branches.length; i++) {
            patterns[i] = branches[i].getPattern();
        }
        return patterns;
    }

    private static boolean hasWildcard(MatchPattern[] patterns) {
        for (MatchPattern p : patterns) {
            if (p instanceof MatchPattern.Wildcard) return true;
        }
        return false;
    }

    /**
     * Returns true if the patterns reference at least one variant from this set,
     * suggesting the match is intended to dispatch over this sum type.
     */
    private static boolean isRelevant(MatchPattern[] patterns, SpnVariantSet vs) {
        for (MatchPattern p : patterns) {
            if (p instanceof MatchPattern.Struct sp) {
                for (SpnStructDescriptor variant : vs.getVariants()) {
                    if (sp.descriptor() == variant) return true;
                }
            }
        }
        return false;
    }
}
