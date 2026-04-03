package spn.type.check;

import com.oracle.truffle.api.nodes.Node;
import spn.node.match.MatchPattern;
import spn.node.match.SpnMatchBranchNode;
import spn.node.match.SpnMatchNode;
import spn.type.SpnStructDescriptor;
import spn.type.SpnSymbol;
import spn.type.SpnSymbolSet;
import spn.type.SpnVariantSet;

import java.util.ArrayList;
import java.util.Arrays;
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
 *   3. If the branches' Literal patterns cover all symbols of a known SpnSymbolSet
 *      → exhaustive (all symbol values handled)
 *   4. Otherwise → non-exhaustive → ERROR diagnostic with the missing cases
 *
 * Usage:
 * <pre>
 *   var SHAPE = new SpnVariantSet("Shape", CIRCLE, RECTANGLE, TRIANGLE);
 *   var COLOR = new SpnSymbolSet("Color", red, green, blue);
 *
 *   var diagnostics = TotalityChecker.check(functionBody,
 *       List.of(SHAPE), List.of(COLOR));
 * </pre>
 */
public final class TotalityChecker {

    private TotalityChecker() {
    }

    /**
     * Checks with only variant sets (backwards compatible).
     */
    public static List<Diagnostic> check(Node body, SpnVariantSet... variantSets) {
        return check(body, Arrays.asList(variantSets), List.of());
    }

    /**
     * Checks with both variant sets and symbol sets.
     */
    public static List<Diagnostic> check(Node body,
                                          List<SpnVariantSet> variantSets,
                                          List<SpnSymbolSet> symbolSets) {
        var diagnostics = new ArrayList<Diagnostic>();
        walkAndCheck(body, variantSets, symbolSets, diagnostics);
        return diagnostics;
    }

    /** Returns true if any diagnostic is an ERROR. */
    public static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }

    // ── AST walking ─────────────────────────────────────────────────────────

    private static void walkAndCheck(Node node,
                                     List<SpnVariantSet> variantSets,
                                     List<SpnSymbolSet> symbolSets,
                                     List<Diagnostic> diagnostics) {
        if (node instanceof SpnMatchNode matchNode) {
            checkMatchExhaustiveness(matchNode, variantSets, symbolSets, diagnostics);
        }

        for (Node child : node.getChildren()) {
            if (child != null) {
                walkAndCheck(child, variantSets, symbolSets, diagnostics);
            }
        }
    }

    // ── Match exhaustiveness ────────────────────────────────────────────────

    private static void checkMatchExhaustiveness(SpnMatchNode matchNode,
                                                  List<SpnVariantSet> variantSets,
                                                  List<SpnSymbolSet> symbolSets,
                                                  List<Diagnostic> diagnostics) {
        SpnMatchBranchNode[] branches = matchNode.getBranches();
        MatchPattern[] patterns = extractPatterns(branches);

        // Quick check: wildcard covers everything
        if (hasWildcard(patterns)) {
            diagnostics.add(Diagnostic.info(Category.FEASIBILITY,
                    "Match expression has a wildcard default — trivially exhaustive."));
            return;
        }

        boolean checkedAgainstAny = false;

        // Check against struct variant sets
        for (SpnVariantSet vs : variantSets) {
            if (isRelevantVariant(patterns, vs)) {
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

        // Check against symbol sets
        for (SpnSymbolSet ss : symbolSets) {
            if (isRelevantSymbol(patterns, ss)) {
                checkedAgainstAny = true;
                if (ss.isCoveredBy(patterns)) {
                    diagnostics.add(Diagnostic.info(Category.FEASIBILITY,
                            "Match expression covers all symbols of " + ss.getName() + "."));
                } else {
                    SpnSymbol[] missing = ss.uncoveredSymbols(patterns);
                    var names = new StringBuilder();
                    for (int i = 0; i < missing.length; i++) {
                        if (i > 0) names.append(", ");
                        names.append(":").append(missing[i].name());
                    }
                    diagnostics.add(Diagnostic.error(Category.EMPTY_TYPE,
                            "Non-exhaustive match: missing patterns for " + ss.getName()
                                    + " symbol(s): " + names + "."));
                }
            }
        }

        if (!checkedAgainstAny) {
            diagnostics.add(Diagnostic.warning(Category.FEASIBILITY,
                    "Match expression has no wildcard default and could not be checked "
                            + "against any known variant or symbol set. It may fail at runtime."));
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
     * Returns true if the patterns reference at least one variant from this set.
     */
    private static boolean isRelevantVariant(MatchPattern[] patterns, SpnVariantSet vs) {
        for (MatchPattern p : patterns) {
            if (p instanceof MatchPattern.Struct sp) {
                for (SpnStructDescriptor variant : vs.getVariants()) {
                    if (sp.descriptor() == variant) return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the patterns contain a literal that matches a symbol from this set.
     */
    private static boolean isRelevantSymbol(MatchPattern[] patterns, SpnSymbolSet ss) {
        for (MatchPattern p : patterns) {
            if (p instanceof MatchPattern.Literal lit && lit.expected() instanceof SpnSymbol sym) {
                for (SpnSymbol s : ss.getSymbols()) {
                    if (s == sym) return true;
                }
            }
        }
        return false;
    }
}
