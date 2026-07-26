## Summary

<!-- What does this PR do? Link any related issues. -->

Closes #

## Type of change

- [ ] 🐛 Bug fix
- [ ] ✨ New feature
- [ ] 🔒 Security fix
- [ ] 🔨 Refactor / internal improvement
- [ ] 📝 Documentation
- [ ] ⚙️ CI / build

## Changes

<!-- List the key changes made. -->

-

## Testing

<!-- How was this tested? -->

- [ ] Debug APK built and smoke-tested on device
- [ ] Relevant unit / integration tests added or updated
- [ ] No regressions in existing flows

## Architecture checklist

- [ ] All Firestore reads/writes go through `FirebaseCostGuard`
- [ ] New `RecyclerView` adapters use `DiffUtil` (no `notifyDataSetChanged()`)
- [ ] No `Toast` calls on background threads — used `Handler(Looper.getMainLooper()).post(...)`
- [ ] Any new `ExecutorService` field is shut down in `onDestroy()`
- [ ] Sensitive new activities extend `BaseActivity`
- [ ] `MessageBuilder` includes `"id"` field in every Firestore document
- [ ] Any `status` write guards against `read → delivered` downgrade

## Security impact

<!-- Does this PR affect encryption, key storage, authentication, or Firestore rules? -->
<!-- If yes, describe the impact and how it was reviewed. -->

N/A
