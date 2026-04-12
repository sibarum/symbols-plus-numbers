package spn.node.func;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;
import spn.language.SpnException;
import spn.language.SpnLanguage;
import spn.type.FieldDescriptor;
import spn.type.FieldType;
import spn.type.SpnFunctionDescriptor;

/**
 * Root node for a pure function. Handles argument binding, type validation,
 * body execution, and return type validation.
 *
 * Execution flow:
 *   1. Read arguments from frame.getArguments() (passed by the caller)
 *   2. Validate each argument against its declared FieldType (if typed)
 *   3. Bind arguments to frame slots (so the body can read them as locals)
 *   4. Execute the body expression
 *   5. Validate the return value against the declared return type (if typed)
 *   6. Return the result
 *
 * KEY TRUFFLE CONCEPT: frame.getArguments()
 *
 * In Truffle, function arguments are passed via the arguments array on the frame.
 * The caller (SpnInvokeNode) evaluates argument expressions and passes them to
 * callTarget.call(args). The callee reads them with frame.getArguments().
 * After binding to frame slots, the body reads them as regular local variables.
 *
 * Usage (what an AST builder produces):
 * <pre>
 *   var descriptor = SpnFunctionDescriptor.pure("add")
 *       .param("a", FieldType.LONG)
 *       .param("b", FieldType.LONG)
 *       .returns(FieldType.LONG)
 *       .build();
 *
 *   var fdBuilder = FrameDescriptor.newBuilder();
 *   int aSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
 *   int bSlot = fdBuilder.addSlot(FrameSlotKind.Object, "b", null);
 *
 *   var body = SpnAddNodeGen.create(
 *       SpnReadLocalVariableNodeGen.create(aSlot),
 *       SpnReadLocalVariableNodeGen.create(bSlot));
 *
 *   var root = new SpnFunctionRootNode(null, fdBuilder.build(), descriptor,
 *       new int[]{aSlot, bSlot}, body);
 *   var callTarget = root.getCallTarget();
 *   // callTarget.call(3L, 4L) → 7L
 * </pre>
 */
public final class SpnFunctionRootNode extends RootNode {

    @Child private spn.node.SpnExpressionNode body;

    private final SpnFunctionDescriptor descriptor;

    @CompilationFinal(dimensions = 1)
    private final int[] paramSlots;

    @CompilationFinal(dimensions = 1)
    private final FieldType[] paramTypes;

    @CompilationFinal
    private final FieldType returnType;

    @CompilationFinal
    private final boolean needsArgValidation;

    @CompilationFinal
    private final boolean needsReturnValidation;

    // Source location for error messages
    @CompilationFinal private String sourceFile;
    @CompilationFinal private int sourceLine = -1;
    @CompilationFinal private int sourceCol = -1;

    /** Set source position for error reporting. */
    public void setSourcePosition(String file, int line, int col) {
        this.sourceFile = file;
        this.sourceLine = line;
        this.sourceCol = col;
    }

    public SpnFunctionRootNode(SpnLanguage language, FrameDescriptor frameDescriptor,
                               SpnFunctionDescriptor descriptor, int[] paramSlots,
                               spn.node.SpnExpressionNode body) {
        super(language, frameDescriptor);
        this.body = body;
        this.descriptor = descriptor;
        this.paramSlots = paramSlots;

        FieldDescriptor[] params = descriptor.getParams();
        this.paramTypes = new FieldType[params.length];
        boolean anyTyped = false;
        for (int i = 0; i < params.length; i++) {
            this.paramTypes[i] = params[i].type();
            if (params[i].isTyped()) anyTyped = true;
        }
        this.needsArgValidation = anyTyped;
        this.returnType = descriptor.getReturnType();
        this.needsReturnValidation = descriptor.hasTypedReturn();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();

        if (args.length != paramSlots.length) {
            throw new SpnException("Function '" + descriptor.getName() + "' expects "
                    + paramSlots.length + " arguments, got " + args.length, this);
        }

        if (needsArgValidation) {
            validateArgs(args);
        }

        // Trace recording (zero-cost when inactive: single null check)
        spn.trace.TraceRecorder recorder = spn.trace.TraceRecorder.current();
        long callSeq = -1;
        long startTime = 0;
        if (recorder != null) {
            callSeq = recorder.recordCall(descriptor.getName(), descriptor.isPure(), args);
            startTime = System.nanoTime();
        }

        bindArgs(frame, args);

        try {
            Object result = body.executeGeneric(frame);

            if (needsReturnValidation) {
                validateReturn(result);
            }

            if (recorder != null) {
                recorder.recordReturn(callSeq, descriptor.getName(), descriptor.isPure(),
                        args, result, System.nanoTime() - startTime);
            }

            return result;
        } catch (Exception e) {
            if (recorder != null) {
                recorder.recordError(callSeq, descriptor.getName(), descriptor.isPure(),
                        args, e.getMessage(), System.nanoTime() - startTime);
            }
            throw e;
        }
    }

    @ExplodeLoop
    private void validateArgs(Object[] args) {
        for (int i = 0; i < paramTypes.length; i++) {
            if (!paramTypes[i].accepts(args[i])) {
                throw new SpnException("Argument '" + descriptor.getParams()[i].name()
                        + "' of function '" + descriptor.getName()
                        + "' expects " + paramTypes[i].describe()
                        + ", got " + args[i].getClass().getSimpleName(),
                        this);
            }
        }
    }

    @ExplodeLoop
    private void bindArgs(VirtualFrame frame, Object[] args) {
        for (int i = 0; i < paramSlots.length; i++) {
            frame.setObject(paramSlots[i], args[i]);
        }
    }

    private void validateReturn(Object result) {
        if (!returnType.accepts(result)) {
            String msg = "Function '" + descriptor.getName()
                    + "' return type is " + returnType.describe()
                    + ", but body produced " + SpnTypeName.of(result);
            if (sourceLine >= 0) {
                throw new SpnException(msg, sourceFile, sourceLine, sourceCol);
            }
            throw new SpnException(msg, this);
        }
    }

    @Override
    public String getName() {
        return descriptor.getName();
    }

    public SpnFunctionDescriptor getDescriptor() {
        return descriptor;
    }
}
