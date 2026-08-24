# Ruby Anonymous FQN Burst Guard

Minimal branch. Four classes, no measurement, no UI — just the runtime patch that stops RubyMine's
Ruby analysis from hanging forever on files that reference ActiveRecord models.

For the instrumented version, with a tool window that reports stub-key histograms, sampled recursion
depth and a stall log, see `master`.

## The bug

`AnonymousDefiningCallType.getAnonymousClassName` builds an anonymous class/module FQN as:

```java
"$$ANON" + (isClass() ? "$C" : "$M")
        + Base64(Integer.toString(((31 * start + end) << 15) ^ callText.hashCode()))
        + "$$"
```

The containing file is not part of the identity. Any two files whose anonymous defining call has the
same text at the same offset therefore collapse into a single symbol. In the codebase this was written
for, eleven files open with `ActiveRecord::Base.class_eval` at offset 0 — `(31*0 + 29) << 15` xor
`"ActiveRecord::Base.class_eval".hashCode()` = `914550953` — and all eleven become
`$$ANON$COTE0NTUwOTUz$$`.

The merged symbol resolves to eleven real elements, not to nothing, and that is what makes it fatal.
`SymbolHierarchy.getAncestorsFromAnonymousDefiningCalls` looks the FQN up, then re-enters ancestor
resolution once per element returned. Eleven wide, ~13 deep, and nothing along the way is memoizable
because `RecursionManager` holds the stack stamp for the whole cycle. One measured stall:

| | |
|---|---|
| lookups of that one key | 33,669,997 |
| lookup rate | ~113k/s |
| share of all CPU samples in that call tree | 53% |
| observed recursion depth | 11–14 |

Four separate inspections enter it (`RubyNilAnalysisVisitor`, `RubyDeprecatedSymbolInspection`,
`RubyMismatchedArgumentTypeInspection`, plain `RubyElementVisitor`), so there is no single inspection
to switch off.

## The fix

`BurstGuard` keeps a per-thread, per-key lookup count. When one `$$ANON` key is requested more than
`burstMax` times on one thread with no `burstQuietMillis` pause, its stub-index lookup is served
empty. An empty lookup empties the caller's element loop, so nothing is expanded and the recursion
ends.

The threshold has five orders of magnitude of headroom: the busiest key in the same session that was
*not* the runaway was requested 225 times. Legitimate repeat resolution of one FQN is bounded by
`RubySymbolsLookupCache` — a rate this high is itself the signature of that caching being defeated.

## Why it hooks the stub index

The obvious place to cut is `SymbolHierarchy.getAncestorsCaching`. Advice woven there **does not
execute** in a real IDE: the tool window reported `wove SymbolHierarchy (on load)` and
`getAncestorsCaching entered : 0` at the same time, with no linkage error. It is not a matcher fault —
applying `named("getAncestorsCaching").and(takesArguments(2))` to the shipped
`intellij.ruby.backend.jar` offline matches 1 of 44 methods and inlines the calls at bytecode offsets
1 and 5. The break is between the transformer and the executing class, most likely the Ruby plugin's
classloader being rebuilt after the agent attaches.

`RubyStringStubIndexExtension.getElements` is the one point demonstrably live in the field — it
counted all 33.7M of those lookups — so the cut lives there instead.

## Layout

| file | loader | why |
|---|---|---|
| `BurstGuard` | bootstrap | The advice is inlined into a class owned by the Ruby plugin's classloader, which cannot see this plugin's jar. Bootstrap is visible everywhere and guarantees one copy of the per-thread state. |
| `StubKeyAdvice` | plugin | Inlined into `getElements`; skips the body and substitutes an empty collection. |
| `Installer` | plugin | Self-attaches ByteBuddy, appends the boot jar, weaves. |
| `Bootstrap` | plugin | `AppLifecycleListener`, so the weave happens before the first analysis pass loads the target. |

## Trade-off and knobs

A cut lookup means an anonymous class's ancestor list can come back incomplete.

```
-Drubyprobe.disabled=true            # off entirely
-Drubyprobe.burstMax=512             # lookups of one key, per thread, per burst
-Drubyprobe.burstQuietMillis=250     # pause that ends a burst
```

The first time the guard engages it logs one line to `idea.log`, tagged `rubyprobe`.

## Build

```sh
./gradlew clean test buildPlugin     # -> build/distributions/
./gradlew installLocal               # quit RubyMine first
```

Pinned to build 262 on purpose: this instruments private RubyMine internals by name, and a platform
upgrade can move them.

## Upstream

The underlying defect is JetBrains'. The only fixes that remove it rather than clip it are putting the
containing file into the anonymous FQN, or de-colliding the calls in the affected project.
