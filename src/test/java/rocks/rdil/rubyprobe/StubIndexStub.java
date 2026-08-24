package rocks.rdil.rubyprobe;

import java.util.Collection;
import java.util.Collections;

/**
 * Stands in for {@code RubyStringStubIndexExtension.getElements(Project, SearchScope, String)}, whose
 * erased return type is {@code Collection} -- which is what {@link StubKeyAdvice} has to assign to.
 *
 * Returns empty by default. {@link #setElements(int)} makes it return a non-empty collision set
 * instead, which is what the collided anonymous FQN actually does: {@code $$ANON$COTE0NTUwOTUz$$}
 * resolves to the 11 files that share {@code ActiveRecord::Base.class_eval} at offset 0. That case is
 * the one the negative cache cannot touch, because the lookup is not a negative lookup.
 */
public class StubIndexStub {

    private static volatile int realLookups;
    private static volatile int elements;

    static int realLookups() {
        return realLookups;
    }

    static void resetObserved() {
        realLookups = 0;
        elements = 0;
    }

    /** @param count elements every lookup returns; 0 (the default) means empty. */
    static void setElements(int count) {
        elements = count;
    }

    public Collection<Object> getElements(Object project, Object scope, String key) {
        realLookups++;
        int n = elements;
        if (n <= 0) {
            return Collections.emptyList();
        }
        Collection<Object> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(key + "#" + i);
        }
        return out;
    }
}
