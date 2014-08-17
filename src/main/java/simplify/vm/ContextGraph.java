package simplify.vm;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.util.ReferenceUtil;
import org.jf.dexlib2.writer.builder.BuilderMethod;
import org.jf.util.SparseArray;

import simplify.Main;
import simplify.SmaliClassUtils;
import simplify.vm.ops.Op;
import simplify.vm.ops.OpFactory;
import simplify.vm.types.UnknownValue;

public class ContextGraph implements Iterable<ContextNode> {

    private static final Logger log = Logger.getLogger(Main.class.getSimpleName());

    private static SparseArray<List<ContextNode>> buildAddressToNodePile(VirtualMachine vm, String methodDescriptor,
                    List<BuilderInstruction> instructions) {
        OpFactory handlerFactory = new OpFactory(vm, methodDescriptor);

        SparseArray<List<ContextNode>> result = new SparseArray<List<ContextNode>>(instructions.size());
        for (BuilderInstruction instruction : instructions) {
            int address = instruction.getLocation().getCodeAddress();
            Op handler = handlerFactory.create(instruction, address);
            ContextNode node = new ContextNode(handler);

            // Most node piles will be a template node and one node with context.
            List<ContextNode> nodePile = new ArrayList<ContextNode>(2);
            nodePile.add(node);

            result.put(address, nodePile);
        }

        return result;
    }

    private static TIntList buildTerminatingAddresses(List<BuilderInstruction> instructions) {
        TIntList result = new TIntArrayList(1);

        for (BuilderInstruction instruction : instructions) {
            int address = instruction.getLocation().getCodeAddress();
            /*
             * Array payload is a weird pseudo instruction. We treat it like a normal one but perhaps a better way would
             * be to make it easier for operations to execute other operations, perhaps looking up by address. This
             * would eliminate the need for MethodContext.pseudoInstructionReturnAddress, and Context's getParent().
             */
            Opcode op = instruction.getOpcode();
            if (op.canContinue() || (op == Opcode.ARRAY_PAYLOAD) || op.name.startsWith("goto")) {
                continue;
            }
            result.add(address);
        }

        return result;
    }

    private final SparseArray<List<ContextNode>> addressToNodePile;

    private final String methodDescriptor;

    private final TIntList terminatingAddresses;

    ContextGraph(ContextGraph other) {
        methodDescriptor = other.methodDescriptor;

        addressToNodePile = new SparseArray<List<ContextNode>>(other.addressToNodePile.size());
        for (int i = 0; i < other.addressToNodePile.size(); i++) {
            int address = other.addressToNodePile.keyAt(i);
            List<ContextNode> otherNodePile = other.addressToNodePile.get(address);
            List<ContextNode> nodePile = new ArrayList<ContextNode>(otherNodePile.size());
            for (ContextNode otherNode : otherNodePile) {
                nodePile.add(new ContextNode(otherNode));
            }

            addressToNodePile.put(address, nodePile);
        }

        terminatingAddresses = other.terminatingAddresses;
    }

    ContextGraph(VirtualMachine vm, BuilderMethod method) {
        methodDescriptor = ReferenceUtil.getMethodDescriptor(method);

        MutableMethodImplementation implementation = (MutableMethodImplementation) method.getImplementation();
        List<BuilderInstruction> instructions = implementation.getInstructions();

        addressToNodePile = buildAddressToNodePile(vm, methodDescriptor, instructions);

        terminatingAddresses = buildTerminatingAddresses(instructions);
    }

    public void addNode(int address, ContextNode child) {
        addressToNodePile.get(address).add(child);
    }

    public TIntList getAddresses() {
        TIntList addresses = new TIntArrayList(addressToNodePile.size());

        for (int i = 0; i < addressToNodePile.size(); i++) {
            addresses.add(addressToNodePile.keyAt(i));
        }

        return addresses;
    }

    public TIntList getConnectedTerminatingAddresses() {
        TIntList result = new TIntArrayList(1);
        for (int i = 0; i < terminatingAddresses.size(); i++) {
            int address = terminatingAddresses.get(i);
            if (wasAddressReached(address)) {
                result.add(address);
            }
        }

        return result;
    }

    public String getMethodDescriptor() {
        return methodDescriptor;
    }

    public List<ContextNode> getNodePile(int address) {
        List<ContextNode> result = addressToNodePile.get(address);
        if (address > 0) {
            result = result.subList(1, result.size()); // remove template node
        }

        return result;
    }

    public Op getOpHandler(int address) {
        List<ContextNode> pile = addressToNodePile.get(address);
        ContextNode bottomNode = pile.get(0);

        return bottomNode.getOpHandler();
    }

    public Object getRegisterConsensus(int address, int register) {
        TIntList addresses = new TIntArrayList(1);
        addresses.add(address);

        return getRegisterConsensus(addresses, register);
    }

    public Object getRegisterConsensus(TIntList addressList, int register) {
        ContextNode firstNode = getNodePile(addressList.get(0)).get(0);
        Object value = firstNode.getContext().peekRegister(register);
        int[] addresses = addressList.toArray();
        for (int address : addresses) {
            for (ContextNode node : getNodePile(address)) {
                Object otherValue = node.getContext().peekRegister(register);

                if (value != otherValue) {
                    log.finer("No conensus value for register #" + register + ", returning unknown");

                    return new UnknownValue(SmaliClassUtils.getValueType(value));
                }
            }

        }

        return value;
    }

    public MethodContext getRootContext() {
        return getRootNode().getContext();
    }

    public SideEffect.Type getStrongestSideEffectType() {
        SideEffect.Type result = SideEffect.Type.NONE;
        for (ContextNode node : this) {
            SideEffect.Type type = node.getOpHandler().sideEffectType();
            switch (type) {
            case STRONG:
                return type;
            case WEAK:
                result = type;
                break;
            case NONE:
                break;
            }
        }

        return result;
    }

    public Object[] getTerminatingRegisterConsensus(int register) {
        return getTerminatingRegisterConsensus(new int[] { register });
    }

    public Object[] getTerminatingRegisterConsensus(int[] registers) {
        TIntList addresses = getConnectedTerminatingAddresses();
        Object[] result = new Object[registers.length];
        for (int i = 0; i < registers.length; i++) {
            result[i] = getRegisterConsensus(addresses, registers[i]);
        }

        return result;
    }

    @Override
    public Iterator<ContextNode> iterator() {
        return new ContextGraphIterator(this);
    }

    public String toGraph() {
        return getRootNode().toGraph();
    }

    public boolean wasAddressReached(int address) {
        if (address == 0) {
            // Root is always reachable
            return true;
        }

        List<ContextNode> nodePile = addressToNodePile.get(address);

        // If this address was reached during execution there will be clones in the pile.
        return nodePile.size() > 1;
    }

    int getNodeCount() {
        return addressToNodePile.size();
    }

    ContextNode getRootNode() {
        // There is only one entry point for a method.
        return addressToNodePile.get(0).get(0);
    }

    ContextNode getTemplateNode(int address) {
        return addressToNodePile.get(address).get(0);
    }

    void setRootContext(MethodContext mctx) {
        getRootNode().setContext(mctx);
    }
}