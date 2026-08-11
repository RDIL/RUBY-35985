# Ruby Analysis Probe + Patch Changelog

## [Unreleased]

## [0.2.0]

### Added

- Runtime patch: cut re-entrant ancestor resolution of an anonymous FQN already being resolved on the
  same thread. Restores memoization as a side effect, since the cut lands before `RecursionManager` is
  consulted and no longer forces `mayCacheNow()` to false.
- Runtime patch: suppress anonymous stub-index lookups measured to return nothing, for up to 2s at a
  time.
- Tool window toggles for both patches, plus `Measure` to drop the per-resolution bookkeeping while
  leaving the patches active.

### Changed

- Advice now references the bootstrap-loaded state directly instead of a `BiConsumer` parked in
  `System.getProperties()`, which recorded nothing. This is why the ancestor hook reports anything at
  all.
- Package renamed to `rocks.rdil.rubyprobe`.
- Build moved to Gradle and the IntelliJ Platform Gradle Plugin; tests are JUnit 5.

## [0.1.0]

### Added

- Initial diagnostic build: tool window naming the symbol under resolution, recursion depth,
  stub-key histograms, and stall snapshots to `~/ruby-probe-stall.log`.
