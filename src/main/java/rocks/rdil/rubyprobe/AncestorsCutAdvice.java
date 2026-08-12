package rocks.rdil.rubyprobe;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code SymbolHierarchy.getAncestorsCaching(Symbol, PsiElement)}.
 *
 * Does two things: records the symbol being resolved (measurement), and skips the body when this
 * anonymous FQN is already being resolved further up the same thread's stack (the fix). Skipping
 * returns {@code Collections.emptyList()}, which is byte-for-byte what the method itself returns when
 * its own {@code RecursionManager.doPreventingRecursion} guard trips -- callers already handle it.
 *
 * References ProbeState/ProbePatch directly rather than through an indirection. That is deliberate:
 * the equivalent direct reference from StubKeyAdvice resolves fine from inside the Ruby plugin's
 * module classloader, whereas the previous attempt at routing through a BiConsumer parked in
 * {@code System.getProperties()} recorded nothing -- a Properties table is not a dependable place to
 * leave a non-String value.
 */
public final class AncestorsCutAdvice {

    private AncestorsCutAdvice() {
    }

    /**
     * Returning true skips the method body ({@code skipOn = OnNonDefaultValue}). If this advice
     * throws it is suppressed and yields false, so a failure here degrades to stock behaviour.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.Argument(0) Object symbol) {
        // The catch exists to tell two very different failures apart, because `suppress` hides both:
        // an advice that was never inlined, and an advice that runs but cannot link ProbePatch from
        // the Ruby module's classloader. java.lang.System resolves from any classloader, so if this
        // property ever appears the advice IS in the method and the problem is linkage. If neither
        // this nor the entry counter ever moves, the advice was never woven into the method at all.
        // Costs nothing unless it is already broken.
        try {
            ProbeState.enterAncestors(symbol);
            return ProbePatch.enterAncestors(symbol);
        } catch (Throwable t) {
            System.setProperty("rubyprobe.adviceError", t.getClass().getName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Runs whether the body ran, was skipped, or threw -- so the per-thread depth accounting stays
     * balanced across ProcessCanceledException, which is routine on this path.
     */
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter boolean cut,
                            @Advice.Return(readOnly = false) java.util.List returned) {
        ProbeState.exitAncestors();
        ProbePatch.leaveAncestors();
        if (cut) {
            returned = java.util.Collections.emptyList();
        }
    }
}
