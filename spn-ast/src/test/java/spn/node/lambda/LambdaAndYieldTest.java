package spn.node.lambda;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.SpnRootNode;
import spn.node.expr.*;
import spn.node.local.SpnReadLocalVariableNodeGen;
import spn.node.local.SpnWriteLocalVariableNodeGen;
import spn.type.FieldType;
import spn.type.SpnFunctionDescriptor;
import spn.node.func.SpnFunctionRootNode;

import static org.junit.jupiter.api.Assertions.*;

class LambdaAndYieldTest {

    // ════════════════════════════════════════════════════════════════════════
    // HELPER: Build a range(start, end) producer that yields each integer
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Builds a range(start, end) producer function. The function:
     *   - Takes 3 args: start (Long), end (Long), yieldContext (SpnYieldContext)
     *   - Loops from start to end-1, yielding each value
     *   - Returns the count of yielded values
     *
     * Equivalent to:
     *   function range(start, end, __ctx) {
     *       var i = start
     *       var count = 0
     *       while (i < end) { yield i; i = i + 1; count = count + 1 }
     *       return count
     *   }
     */
    static SpnFunctionRootNode buildRangeProducer() {
        var desc = SpnFunctionDescriptor.pure("range")
                .param("start", FieldType.LONG)
                .param("end", FieldType.LONG)
                .param("__ctx")  // yield context, passed by SpnStreamBlockNode
                .returns(FieldType.LONG)
                .build();

        var fdBuilder = FrameDescriptor.newBuilder();
        int startSlot = fdBuilder.addSlot(FrameSlotKind.Object, "start", null);
        int endSlot = fdBuilder.addSlot(FrameSlotKind.Object, "end", null);
        int ctxSlot = fdBuilder.addSlot(FrameSlotKind.Object, "__ctx", null);
        int iSlot = fdBuilder.addSlot(FrameSlotKind.Object, "i", null);
        int countSlot = fdBuilder.addSlot(FrameSlotKind.Object, "count", null);

        // Body: i = start; count = 0; while (i < end) { yield i; i = i+1; count = count+1 }; return count
        var body = new SpnExpressionNode() {
            @Child SpnExpressionNode initI = SpnWriteLocalVariableNodeGen.create(
                    SpnReadLocalVariableNodeGen.create(startSlot), iSlot);
            @Child SpnExpressionNode initCount = SpnWriteLocalVariableNodeGen.create(
                    new SpnLongLiteralNode(0), countSlot);

            // The loop body: yield i; i = i + 1; count = count + 1
            @Child SpnExpressionNode yieldI = new SpnYieldNode(
                    SpnReadLocalVariableNodeGen.create(iSlot), ctxSlot);
            @Child SpnExpressionNode incrI = SpnWriteLocalVariableNodeGen.create(
                    SpnAddNodeGen.create(
                            SpnReadLocalVariableNodeGen.create(iSlot),
                            new SpnLongLiteralNode(1)),
                    iSlot);
            @Child SpnExpressionNode incrCount = SpnWriteLocalVariableNodeGen.create(
                    SpnAddNodeGen.create(
                            SpnReadLocalVariableNodeGen.create(countSlot),
                            new SpnLongLiteralNode(1)),
                    countSlot);
            @Child SpnExpressionNode readCount = SpnReadLocalVariableNodeGen.create(countSlot);

            // Condition: i < end
            @Child SpnExpressionNode condition = SpnLessThanNodeGen.create(
                    SpnReadLocalVariableNodeGen.create(iSlot),
                    SpnReadLocalVariableNodeGen.create(endSlot));

            @Override
            public Object executeGeneric(VirtualFrame frame) {
                initI.executeGeneric(frame);
                initCount.executeGeneric(frame);

                try {
                    while ((boolean) condition.executeGeneric(frame)) {
                        yieldI.executeGeneric(frame);
                        incrI.executeGeneric(frame);
                        incrCount.executeGeneric(frame);
                    }
                } catch (ClassCastException e) {
                    throw new SpnException("Loop condition must be boolean", this);
                }

                return readCount.executeGeneric(frame);
            }
        };

        return new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                new int[]{startSlot, endSlot, ctxSlot}, body);
    }

    // ════════════════════════════════════════════════════════════════════════
    // BASIC STREAM BLOCK: sum of range
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class BasicStreamBlock {

        @Test
        void sumOfRange() {
            // var sum = 0
            // stream range(1, 6) { n -> sum = sum + n }
            // return sum  → 1+2+3+4+5 = 15

            var rangeProducer = buildRangeProducer();

            var fdBuilder = FrameDescriptor.newBuilder();
            int sumSlot = fdBuilder.addSlot(FrameSlotKind.Object, "sum", null);
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var callerDesc = fdBuilder.build();

            // Lambda body: sum = sum + n
            var lambdaBody = SpnWriteLocalVariableNodeGen.create(
                    SpnAddNodeGen.create(
                            SpnReadLocalVariableNodeGen.create(sumSlot),
                            SpnReadLocalVariableNodeGen.create(nSlot)),
                    sumSlot);

            var lambdaNode = new SpnLambdaNode(lambdaBody, nSlot);

            // Caller body: sum = 0; stream range(1, 6) { n -> ... }; return sum
            var callerBody = new SpnExpressionNode() {
                @Child SpnExpressionNode initSum = SpnWriteLocalVariableNodeGen.create(
                        new SpnLongLiteralNode(0), sumSlot);
                @Child SpnExpressionNode streamBlock = new SpnStreamBlockNode(
                        lambdaNode, rangeProducer.getCallTarget(),
                        new SpnLongLiteralNode(1),
                        new SpnLongLiteralNode(6));
                @Child SpnExpressionNode readSum = SpnReadLocalVariableNodeGen.create(sumSlot);

                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    initSum.executeGeneric(frame);
                    streamBlock.executeGeneric(frame);
                    return readSum.executeGeneric(frame);
                }
            };

            var root = new SpnRootNode(null, callerDesc, callerBody, "test");
            assertEquals(15L, root.getCallTarget().call());
        }

        @Test
        void productOfRange() {
            // var product = 1
            // stream range(1, 6) { n -> product = product * n }
            // return product  → 1*2*3*4*5 = 120

            var rangeProducer = buildRangeProducer();

            var fdBuilder = FrameDescriptor.newBuilder();
            int prodSlot = fdBuilder.addSlot(FrameSlotKind.Object, "product", null);
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var callerDesc = fdBuilder.build();

            // Lambda body: product = product * n (using inline mul)
            var lambdaBody = new SpnExpressionNode() {
                @Child SpnExpressionNode readProd = SpnReadLocalVariableNodeGen.create(prodSlot);
                @Child SpnExpressionNode readN = SpnReadLocalVariableNodeGen.create(nSlot);
                @Child SpnExpressionNode writeProd = SpnWriteLocalVariableNodeGen.create(
                        new SpnExpressionNode() {
                            @Child SpnExpressionNode rp = SpnReadLocalVariableNodeGen.create(prodSlot);
                            @Child SpnExpressionNode rn = SpnReadLocalVariableNodeGen.create(nSlot);
                            @Override
                            public Object executeGeneric(VirtualFrame frame) {
                                return (long) rp.executeGeneric(frame) * (long) rn.executeGeneric(frame);
                            }
                        }, prodSlot);
                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    return writeProd.executeGeneric(frame);
                }
            };

            var callerBody = new SpnExpressionNode() {
                @Child SpnExpressionNode initProd = SpnWriteLocalVariableNodeGen.create(
                        new SpnLongLiteralNode(1), prodSlot);
                @Child SpnExpressionNode streamBlock = new SpnStreamBlockNode(
                        new SpnLambdaNode(lambdaBody, nSlot),
                        rangeProducer.getCallTarget(),
                        new SpnLongLiteralNode(1), new SpnLongLiteralNode(6));
                @Child SpnExpressionNode readProd = SpnReadLocalVariableNodeGen.create(prodSlot);

                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    initProd.executeGeneric(frame);
                    streamBlock.executeGeneric(frame);
                    return readProd.executeGeneric(frame);
                }
            };

            var root = new SpnRootNode(null, callerDesc, callerBody, "test");
            assertEquals(120L, root.getCallTarget().call());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LAMBDA SCOPE: reading and writing parent variables
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class LambdaScope {

        @Test
        void lambdaReadsParentScope() {
            // var multiplier = 10
            // var result = 0
            // stream range(1, 4) { n -> result = result + n * multiplier }
            // return result  → 10 + 20 + 30 = 60

            var rangeProducer = buildRangeProducer();

            var fdBuilder = FrameDescriptor.newBuilder();
            int multSlot = fdBuilder.addSlot(FrameSlotKind.Object, "multiplier", null);
            int resultSlot = fdBuilder.addSlot(FrameSlotKind.Object, "result", null);
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var callerDesc = fdBuilder.build();

            // Lambda: result = result + n * multiplier
            var lambdaBody = new SpnExpressionNode() {
                @Child SpnExpressionNode readResult = SpnReadLocalVariableNodeGen.create(resultSlot);
                @Child SpnExpressionNode readN = SpnReadLocalVariableNodeGen.create(nSlot);
                @Child SpnExpressionNode readMult = SpnReadLocalVariableNodeGen.create(multSlot);

                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    long r = (long) readResult.executeGeneric(frame);
                    long n = (long) readN.executeGeneric(frame);
                    long m = (long) readMult.executeGeneric(frame);
                    frame.setObject(resultSlot, r + n * m);
                    return frame.getObject(resultSlot);
                }
            };

            var callerBody = new SpnExpressionNode() {
                @Child SpnExpressionNode initMult = SpnWriteLocalVariableNodeGen.create(
                        new SpnLongLiteralNode(10), multSlot);
                @Child SpnExpressionNode initResult = SpnWriteLocalVariableNodeGen.create(
                        new SpnLongLiteralNode(0), resultSlot);
                @Child SpnExpressionNode stream = new SpnStreamBlockNode(
                        new SpnLambdaNode(lambdaBody, nSlot),
                        rangeProducer.getCallTarget(),
                        new SpnLongLiteralNode(1), new SpnLongLiteralNode(4));
                @Child SpnExpressionNode readResult = SpnReadLocalVariableNodeGen.create(resultSlot);

                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    initMult.executeGeneric(frame);
                    initResult.executeGeneric(frame);
                    stream.executeGeneric(frame);
                    return readResult.executeGeneric(frame);
                }
            };

            var root = new SpnRootNode(null, callerDesc, callerBody, "test");
            assertEquals(60L, root.getCallTarget().call());
        }

        @Test
        void lambdaWritesParentScope() {
            // var last = -1
            // stream range(0, 5) { n -> last = n }
            // return last  → 4  (last value yielded)

            var rangeProducer = buildRangeProducer();

            var fdBuilder = FrameDescriptor.newBuilder();
            int lastSlot = fdBuilder.addSlot(FrameSlotKind.Object, "last", null);
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var callerDesc = fdBuilder.build();

            var lambdaBody = SpnWriteLocalVariableNodeGen.create(
                    SpnReadLocalVariableNodeGen.create(nSlot), lastSlot);

            var callerBody = new SpnExpressionNode() {
                @Child SpnExpressionNode initLast = SpnWriteLocalVariableNodeGen.create(
                        new SpnLongLiteralNode(-1), lastSlot);
                @Child SpnExpressionNode stream = new SpnStreamBlockNode(
                        new SpnLambdaNode(lambdaBody, nSlot),
                        rangeProducer.getCallTarget(),
                        new SpnLongLiteralNode(0), new SpnLongLiteralNode(5));
                @Child SpnExpressionNode readLast = SpnReadLocalVariableNodeGen.create(lastSlot);

                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    initLast.executeGeneric(frame);
                    stream.executeGeneric(frame);
                    return readLast.executeGeneric(frame);
                }
            };

            var root = new SpnRootNode(null, callerDesc, callerBody, "test");
            assertEquals(4L, root.getCallTarget().call());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class EdgeCases {

        @Test
        void emptyRange() {
            // stream range(5, 5) { n -> ... }  → no yields, sum stays 0

            var rangeProducer = buildRangeProducer();

            var fdBuilder = FrameDescriptor.newBuilder();
            int sumSlot = fdBuilder.addSlot(FrameSlotKind.Object, "sum", null);
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var callerDesc = fdBuilder.build();

            var lambdaBody = SpnWriteLocalVariableNodeGen.create(
                    SpnAddNodeGen.create(
                            SpnReadLocalVariableNodeGen.create(sumSlot),
                            SpnReadLocalVariableNodeGen.create(nSlot)),
                    sumSlot);

            var callerBody = new SpnExpressionNode() {
                @Child SpnExpressionNode initSum = SpnWriteLocalVariableNodeGen.create(
                        new SpnLongLiteralNode(0), sumSlot);
                @Child SpnExpressionNode stream = new SpnStreamBlockNode(
                        new SpnLambdaNode(lambdaBody, nSlot),
                        rangeProducer.getCallTarget(),
                        new SpnLongLiteralNode(5), new SpnLongLiteralNode(5));
                @Child SpnExpressionNode readSum = SpnReadLocalVariableNodeGen.create(sumSlot);

                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    initSum.executeGeneric(frame);
                    stream.executeGeneric(frame);
                    return readSum.executeGeneric(frame);
                }
            };

            var root = new SpnRootNode(null, callerDesc, callerBody, "test");
            assertEquals(0L, root.getCallTarget().call());
        }

        @Test
        void singleYield() {
            // stream range(42, 43) { n -> ... }  → yields exactly once (42)

            var rangeProducer = buildRangeProducer();

            var fdBuilder = FrameDescriptor.newBuilder();
            int resultSlot = fdBuilder.addSlot(FrameSlotKind.Object, "result", null);
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var callerDesc = fdBuilder.build();

            var lambdaBody = SpnWriteLocalVariableNodeGen.create(
                    SpnReadLocalVariableNodeGen.create(nSlot), resultSlot);

            var callerBody = new SpnExpressionNode() {
                @Child SpnExpressionNode initResult = SpnWriteLocalVariableNodeGen.create(
                        new SpnLongLiteralNode(-1), resultSlot);
                @Child SpnExpressionNode stream = new SpnStreamBlockNode(
                        new SpnLambdaNode(lambdaBody, nSlot),
                        rangeProducer.getCallTarget(),
                        new SpnLongLiteralNode(42), new SpnLongLiteralNode(43));
                @Child SpnExpressionNode readResult = SpnReadLocalVariableNodeGen.create(resultSlot);

                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    initResult.executeGeneric(frame);
                    stream.executeGeneric(frame);
                    return readResult.executeGeneric(frame);
                }
            };

            var root = new SpnRootNode(null, callerDesc, callerBody, "test");
            assertEquals(42L, root.getCallTarget().call());
        }

        @Test
        void producerReturnsCount() {
            // The stream block returns the producer's return value (count)

            var rangeProducer = buildRangeProducer();

            var fdBuilder = FrameDescriptor.newBuilder();
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var callerDesc = fdBuilder.build();

            var lambdaBody = SpnReadLocalVariableNodeGen.create(nSlot); // no-op

            var streamBlock = new SpnStreamBlockNode(
                    new SpnLambdaNode(lambdaBody, nSlot),
                    rangeProducer.getCallTarget(),
                    new SpnLongLiteralNode(0), new SpnLongLiteralNode(10));

            var root = new SpnRootNode(null, callerDesc, streamBlock, "test");
            assertEquals(10L, root.getCallTarget().call()); // range(0,10) yields 10 values
        }

        @Test
        void yieldOutsideStreamBlockThrows() {
            // yield without a context → error
            var fdBuilder = FrameDescriptor.newBuilder();
            int ctxSlot = fdBuilder.addSlot(FrameSlotKind.Object, "ctx", null);
            var desc = fdBuilder.build();

            // Write null to the ctx slot, then try to yield
            var body = new SpnExpressionNode() {
                @Child SpnExpressionNode initCtx = SpnWriteLocalVariableNodeGen.create(
                        new SpnLongLiteralNode(0), ctxSlot); // not a YieldContext!
                @Child SpnExpressionNode yield = new SpnYieldNode(
                        new SpnLongLiteralNode(42), ctxSlot);

                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    initCtx.executeGeneric(frame);
                    return yield.executeGeneric(frame);
                }
            };

            var root = new SpnRootNode(null, desc, body, "test");
            assertThrows(SpnException.class, () -> root.getCallTarget().call());
        }
    }
}
