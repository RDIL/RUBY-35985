package rocks.rdil.rubyprobe;

import java.util.Collection;
import java.util.Collections;

/**
 * Stands in for {@code RubyStringStubIndexExtension.getElements(Project, SearchScope, String)}, whose
 * erased return type is {@code Collection} -- which is what {@link StubKeyAdvice} has to assign to.
 *
 * Always returns empty, mirroring the measured behaviour for the collided anonymous FQN.
 */
public class StubIndexStub {

    private static volatile int realLookups;

    static int realLookups() {
        return realLookups;
    }

    static void resetObserved() {
        realLookups = 0;
    }

    public Collection<Object> getElements(Object project, Object scope, String key) {
        realLookups++;
        return Collections.emptyList();
    }
}
