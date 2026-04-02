package spn.language;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/**
 * Base exception for SPN runtime errors.
 *
 * Extends AbstractTruffleException so the Truffle framework can properly handle it:
 * - It participates in guest-language stack traces
 * - It can be caught by polyglot embedders
 * - It carries a source location via the Node parameter
 */
public final class SpnException extends AbstractTruffleException {

    public SpnException(String message) {
        super(message);
    }

    public SpnException(String message, Node location) {
        super(message, location);
    }
}
