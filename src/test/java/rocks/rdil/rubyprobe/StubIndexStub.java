package rocks.rdil.rubyprobe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Stands in for {@code RubyStringStubIndexExtension.getElements(Project, SearchScope, String)}, whose
 * erased return type is {@code Collection} -- which is what {@link StubKeyAdvice} has to assign to.
 *
 * Returns a non-empty collection by default, because that is what the collided anonymous FQN
 * actually does: it resolves to every file that shares the call text and offset.
 */
public class StubIndexStub {

    private static volatile int realLookups;
    private static volatile int elements = 11;

    static int realLookups() {
        return realLookups;
    }

    static void reset() {
        realLookups = 0;
        elements = 11;
    }

    static void setElements(int count) {
        elements = count;
    }

    public Collection<Object> getElements(Object project, Object scope, String key) {
        realLookups++;
        int n = elements;
        if (n <= 0) {
            return Collections.emptyList();
        }
        Collection<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(key + "#" + i);
        }
        return out;
    }
}
