package spn.language;

import com.oracle.truffle.api.CallTarget;
import spn.type.SpnFunctionDescriptor;

/**
 * A method registration: the resolved {@link CallTarget} plus its type
 * descriptor. Used by the parser's method dispatch (keyed by
 * {@code "TypeName.methodName"}) and populated from both user-defined
 * {@code pure TypeName.method(...) = ...} declarations and stdlib builtins
 * annotated with {@code @SpnBuiltin(receiver = ...)}.
 */
public record MethodEntry(CallTarget callTarget, SpnFunctionDescriptor descriptor) {}
