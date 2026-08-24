package rocks.rdil.rubyprobe;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code RubyStringStubIndexExtension.getElements(Project, SearchScope, String)}, whose
 * third argument is the FQN being looked up.
 * {@code RubyAnonymousDefiningCallIndex} extends {@code RubyFqnStubIndexExtension} extends
 * {@code RubyStringStubIndexExtension}, so every anonymous-defining-call lookup funnels through
 * here. This is also the one instrumentation point in this plugin shown to be reliably woven in a
 * real IDE -- advice on {@code SymbolHierarchy.getAncestorsCaching} was observed woven but never
 * entered, so the cut lives here instead.
 * References {@link BurstGuard} directly. That resolves from inside the Ruby plugin's module
 * classloader because the class is appended to the bootstrap classloader search at install time.
 */
public final class StubKeyAdvice {

    private StubKeyAdvice() {
    }

    /** Returning true skips the method body, so the index is never consulted. */
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    @SuppressWarnings("unused")
    public static boolean enter(@Advice.Argument(2) Object key) {
        return BurstGuard.shouldSkipLookup(key);
    }

    /**
     * A skipped body leaves the default return value (null), and callers here iterate the result, so
     * it has to become an empty collection rather than stay null.
     */
    @SuppressWarnings({"rawtypes", "ParameterCanBeLocal", "UnusedAssignment", "unused"})
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Enter boolean skipped,
                            @Advice.Return(readOnly = false) java.util.Collection returned) {
        // this looks like a noop (modifying a parameter as a local variable) but at runtime it will
        // be woven into the target class's bytecode directly
        if (skipped) {
            returned = java.util.Collections.emptyList();
        }
    }
}
