## Summary

<!-- What does this PR do? Link any related issues. -->

Closes #

## Changes

<!-- List the key changes made. -->

- 

## Testing

<!-- How was this tested? -->

- [ ] Debug APK built and smoke-tested on device
- [ ] Relevant unit tests added or updated
- [ ] No regressions in existing flows

## Checklist

- [ ] All Firestore reads/writes go through `FirebaseCostGuard`
- [ ] New `RecyclerView` adapters use `DiffUtil`
- [ ] No `Toast` calls on background threads (use `Handler(Looper.getMainLooper()).post(...)`)
- [ ] Any new `ExecutorService` field is shut down in `onDestroy()`
- [ ] Sensitive new activities extend `BaseActivity`
- [ ] `MessageBuilder` includes `"id"` field in every Firestore document
