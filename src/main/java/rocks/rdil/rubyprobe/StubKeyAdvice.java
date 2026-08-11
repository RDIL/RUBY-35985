package rocks.rdil.rubyprobe;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into RubyStringStubIndexExtension.getElements(Project, SearchScope, String).
 *
 * The third argument is the FQN string being looked up in the stub index -- the single most direct
 * answer to "what is it looking for right now".
 *
 * This is also where the negative cache lives. RubyAnonymousDefiningCallIndex extends
 * RubyFqnStubIndexExtension extends RubyStringStubIndexExtension, so the anonymous-defining-call
 * lookups that dominated the profile all funnel through here -- and were measured returning nothing,
 * millions of times, while roughly half of all CPU sat inside this method.
 */
public final class StubKeyAdvice {

    private StubKeyAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.Argument(2) Object key) {
        // Counted before the skip decision, so the readout still shows what the IDE asked for and
        // suppression is visible as "high query rate, low CPU" rather than by disappearing.
        ProbeState.stubQuery(key);
        return ProbePatch.shouldSkipLookup(key);
    }

    /**
     * The returned collection holds the PSI elements the index matched for this key -- i.e. the
     * declarations themselves. For an anonymous symbol, whose name is only a hash, this is the one
     * place a real source location is available.
     */
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Argument(2) Object key,
                            @Advice.Enter boolean skipped,
                            @Advice.Return(readOnly = false) java.util.Collection returned) {
        if (skipped) {
            returned = java.util.Collections.emptyList();
            return;
        }
        ProbeState.stubResult(key, returned);
        ProbePatch.recordLookup(key, returned);
    }
}
