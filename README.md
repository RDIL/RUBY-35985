# Ruby Analysis Probe + Patch

A RubyMine plugin that works around a defect in RubyMine's anonymous-symbol identity which can stop
Ruby code analysis from ever completing, and reports on what analysis is doing while it happens.

Built for RubyMine 2026.2 (`build 262.*`) with Gradle and the
[IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html),
laid out after the
[IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

## The defect

`AnonymousDefiningCallType#getAnonymousClassName(RPsiElement)` derives an anonymous class/module's
fully-qualified name from the call's text range and text, and nothing else:

```java
element = (e instanceof RBlockCall) ? ((RBlockCall) e).getCall() : e;
return String.format("%s%s%s$$", "$$ANON", isClass() ? "$C" : "$M",
    ENCODER.encodeToString(Integer.toString(
        (element.getTextRange().hashCode() << 15) ^ element.getText().hashCode()
    ).getBytes(Charsets.US_ASCII)));
```

`TextRange#hashCode()` is `31 * startOffset + endOffset`, so with `end == start + text.length` the
identity reduces to `((32*start + len) << 15) ^ text.hashCode()`. **There is no file in it.** Any two
anonymous defining calls with the same text at the same offset in different files get the same name
and collapse into one symbol.

Worked example — eleven monkeypatch files each beginning at offset 0 with
`ActiveRecord::Base.class_eval do`:

| step | value |
|---|---|
| `text` | `"ActiveRecord::Base.class_eval"` (len 29) |
| `TextRange.hashCode()` = `31*0 + 29` | 29 |
| `<< 15` | 950272 |
| `text.hashCode()` | 915173545 |
| xor | **914550953** |
| `base64("914550953")` | `OTE0NTUwOTUz` |
| resulting FQN | **`$$ANON$COTE0NTUwOTUz$$`** |

The merged symbol resolves to zero index elements, and ancestor resolution for it does not converge.
RubyMine tries to stop this twice and both guards miss, because both key on Symbol *identity* while
the collision is in the *name*:

- `SymbolHierarchy.getAncestorsCaching` wraps the work in
  `RecursionManager.doPreventingRecursion(Pair.create(symbol, invocationPoint), false, …)`.
  Re-entering for the same FQN through a freshly built `Symbol` instance is not recognised as
  recursion.
- `SymbolHierarchy.getAncestorsFromAnonymousDefiningCalls` checks
  `symbol instanceof AnonymousClassModuleSymbol && visited.contains(symbol)`, but `visited` is
  allocated inside `computeAncestors`, i.e. per `getAncestorsCaching` call — which is exactly the
  boundary the cycle crosses. It is also why the `::$$SINGLETON$$` alternation slips through: a
  `SingletonClassSymbol` is not an `AnonymousClassModuleSymbol`.

Caching is gated on `RecursionGuard.StackStamp.mayCacheNow()`, which is false once
`doPreventingRecursion` has tripped — so nothing memoises and the daemon repeats the whole
computation on every pass.

## What the plugin patches

Both patches are restricted to synthesized `$$ANON` FQNs, so ordinary resolution is untouched (a real
Ruby constant path cannot contain `$`). Both are toggleable at runtime from the tool window.

**Cut anon cycles** — a per-thread guard keyed on the FQN *string*, spanning invocations. Re-entering
`getAncestorsCaching` for an anonymous FQN already being resolved on the same thread skips the body
and returns `Collections.emptyList()`, which is exactly what the method itself returns when its own
recursion guard trips, so callers doing `result.addAll(…)` are unaffected.

Because the cut lands *before* RecursionManager is consulted, the outer frame's `mayCacheNow()` is no
longer forced false and `RubySymbolsLookupCache.registerAncestors` can actually memoise — which
addresses the "recomputed on every daemon pass" half of the problem too.

**Suppress empty lookups** — anonymous stub keys measured to return nothing are served empty for up
to 2s at a time instead of re-querying the index. In the profile that motivated this, roughly half of
all CPU during a stall sat inside `RubyStringStubIndexExtension.getElements` returning nothing.
`RubyAnonymousDefiningCallIndex extends RubyFqnStubIndexExtension extends
RubyStringStubIndexExtension`, so the anonymous-defining-call lookups all funnel through it.

### Known trade-off

A cycle-cut ancestor list may be incomplete, and it is now cacheable. An anonymous class's hierarchy
can therefore be missing entries, which is a real regression against correctness, traded for analysis
finishing at all. Uncheck **Cut anon cycles** to restore stock behaviour.

This makes the collision survivable; it does not remove it. The source-level fix is to ensure no two
anonymous defining calls share an identical `(offset, text)` pair — e.g. rewrite
`ActiveRecord::Base.class_eval do` as `class ActiveRecord::Base`, one line per file.

## How it works

A self-attached Java agent (ByteBuddy) weaves two methods. Self-attach works with no `.vmoptions`
edit because RubyMine already ships `-Djdk.attach.allowAttachSelf=true`.

| target | jar | advice |
|---|---|---|
| `SymbolHierarchy.getAncestorsCaching(Symbol, PsiElement)` | `intellij.ruby.backend.jar` | `AncestorsCutAdvice` |
| `RubyStringStubIndexExtension.getElements(Project, SearchScope, String)` | `intellij.ruby.core.jar` | `StubKeyAdvice` |

`ProbeState` (measurement) and `ProbePatch` (the fix) are JDK-only and are appended to the
**bootstrap** classloader, so there is exactly one copy: the instrumented Ruby code in its module
classloader and the tool window in this plugin's classloader see the same statics. Two copies would
silently split the state, so that invariant is expressed in the build as a separate `bootstrap` source
set which is on `compileClasspath` only — never packaged, never on a runtime classpath — and asserted
by the `verifyPluginLayout` task.

The boot jar is embedded as a resource inside the plugin jar and unpacked to a temp file. IntelliJ's
`PathClassLoader` does not populate a usable `CodeSource` location, so deriving the path from disk is
not dependable; the embedded resource is the primary lookup, with descriptor-path and `CodeSource`
fallbacks.

The advice references `ProbeState`/`ProbePatch` **directly**. An earlier version routed through a
`java.util.function.BiConsumer` parked in `System.getProperties()`, on the theory that the Ruby module
classloader could not resolve `rocks.rdil.rubyprobe.*`; it recorded nothing. Direct references do
resolve, and a `Properties` table is not a dependable place to leave a non-String value.

If the weaving of `getAncestorsCaching` ever fails, the tool window says so outright —
`[advice NOT woven -- cycle cutting is INACTIVE]` — rather than looking like a patch that did nothing.

## Build

```sh
./gradlew build          # compile, test, verify layout
./gradlew buildPlugin    # -> build/distributions/ruby-analysis-probe-<version>.zip
./gradlew test           # JUnit 5 only
./gradlew runIde         # launch a sandboxed RubyMine with the plugin loaded
```

Requires JDK 21 (the Foojay toolchain resolver will provision one if absent) and a local RubyMine
install — the plugin compiles against the IDE's own jars, including the bundled Ruby plugin, which are
not redistributable and are not vendored here. `RubyMine.app` is auto-detected in the usual places;
override with:

```sh
./gradlew build -PplatformLocalPath=/Applications/RubyMine.app
```

Install via **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pointing at the zip from
`buildPlugin`. Restart, then open **View → Tool Windows → Ruby Probe** and check the `runtime patch`
block.

### Deliberate deviations from the template

- **Java, not Kotlin.** All sources are Java and `ProbeState`/`ProbePatch` must stay strictly JDK-only,
  so the Kotlin plugin would contribute nothing but a stdlib that must not be bundled.
- **A local platform rather than a downloaded one.** The plugin needs RubyMine's bundled Ruby plugin;
  resolving against the installed IDE keeps the build honest about what it compiles against and avoids
  a multi-gigabyte download.
- **No `TestFrameworkType.Platform`.** The tests are plain JUnit 5 exercising bytecode weaving; they
  need no IDE fixture, sandbox, or application.

## Tests

`./gradlew test` — JUnit 5, single-forked because the patch state is static and shared.

- **`AnonymousCycleCutTest`** — the cycle terminates; the cut returns a non-null empty list; it fires
  at the first repeat rather than after deep expansion; non-anonymous symbols are never cut; 50
  exceptions do not leak the per-thread depth (`ProcessCanceledException` is routine on this path);
  the advice is genuinely woven; and **with cutting disabled the cycle runs away again**, so a pass
  cannot come from the stand-in terminating on its own.
- **`NegativeLookupCacheTest`** — anonymous keys stop reaching the index after the confirming lookups,
  ordinary keys always reach it, and disabling the cache restores every lookup.
- **`ProbeInstallerTest`** — the boot jar is reachable as a classpath resource, the bootstrap classes
  are *not* also on the application classpath, `installOnce()` reports `installed`, and the toggles
  round-trip.

`SymbolHierarchyStub` reproduces the *defect*, not merely a deep call: it alternates between an
anonymous symbol and its singleton, building a fresh `Symbol` instance every time, which is precisely
why RubyMine's own guards fail to break the cycle. `ProbeFixture` installs the agent the way
`ProbeInstaller` does — self-attach, append the boot jar to the bootstrap loader, then weave — so the
tests drive the real advice through real ByteBuddy weaving.

## Runtime knobs

Toggles in the tool window, or system properties in `Help → Edit Custom VM Options`:

| property | default | effect |
|---|---|---|
| `rubyprobe.cutCycles` | `true` | cut re-entrant anonymous ancestor resolution |
| `rubyprobe.negativeCache` | `true` | suppress anonymous lookups measured to return nothing |
| `rubyprobe.cutMinDepth` | `1` | only start cycle-checking at this depth |
| `rubyprobe.negTtlMillis` | `2000` | how long a suppressed key stays suppressed |
| `rubyprobe.negMinZeros` | `3` | consecutive empty results before suppressing |
| `rubyprobe.stallSeconds` | `10` | wedged for this long → snapshot to `~/ruby-probe-stall.log` |
| `rubyprobe.pkg` | `org.jetbrains.plugins.ruby` | package prefix the stack sampler matches |

**Measure** turns off the per-resolution bookkeeping while leaving both patches active — worth doing
for daily use, since the measurement runs on every ancestor resolution.

## Layout

```
src/bootstrap/java/   ProbeState (measurement) + ProbePatch (the fix) -- JDK-only, bootstrap-loaded
src/main/java/        advice, agent installer, tool window -- compiled against RubyMine's jars
src/main/resources/   META-INF/plugin.xml
src/test/java/        JUnit 5 tests, stand-ins, and the agent fixture
```

## Caveats

- Pinned to `sinceBuild=262` / `untilBuild=262.*`. It instruments private internals by name; a
  RubyMine upgrade can move them, and the reported weaving status is how you find out.
- Everything above about `SymbolHierarchy` and `AnonymousDefiningCallType` came from reading shipped
  bytecode (`javap -c -p` on `intellij.ruby.backend.jar` and `intellij.ruby.psi.impl.jar`).
- The patches are verified against stand-ins carrying RubyMine's exact signatures, driven through real
  ByteBuddy weaving. Whether `intellij.ruby.backend.jar` accepts the transform **inside the IDE** is a
  separate question, which the tool window answers on first load.
- Not a general-purpose plugin. It exists to keep one large Rails codebase analysable while the
  upstream defect is open.
