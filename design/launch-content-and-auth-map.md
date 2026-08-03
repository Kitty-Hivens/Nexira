# Launch: who decides what, and where

Written from the code as of `01f72219`. Covers the two launch paths, the content
sweep and the second-factor flow, because those three now interact and the
interaction is not obvious from any single file.

## The two paths

| | entry | prepare | content sweep | auth |
|---|---|---|---|---|
| Pack | `launchPackInstance` | `preparePackLaunch` | yes, if the pack declares a binding | `preparePackAuth` -> `prepareScAuth` |
| SC server list | `launch` | `prepareServerLaunch` | no (SC sync owns `mods/`) | inline branch |

Both funnel into `launchInternal`, which owns the abort token, the MDC, the spawn
and the exit handling.

## Pack launch, in order

```
launchPackInstance(session, instance)
  |
  +-- resolveOrFetchManifest            manifest snapshot + instance write-back
  +-- clientDir exists?                 no -> LaunchError.OfflineNoClient
  |
  +-- serverBound = manifestSnapshot.authRequirement != null
  |     NOTE: the pack's OWN declaration, not PackAuthRouter.requirementFor --
  |     the router falls back to Microsoft for every mirror pack, so its answer
  |     is true even for a solo pack and cannot be used to scope anything.
  |
  +-- if serverBound: enforceRoster(clientDir)  -> RosterVerdict
  |     removed[]  -> ForeignContentRemoved event (console + notification)
  |     verified   -> false when there is no roster, or when a file refused to go
  |
  +-- session:
  |     offline mode OR not verified  -> toOffline()   (uuid re-minted, token "")
  |     else, requirement present     -> preparePackAuth
  |
  +-- spawn: launchPackClient(session, ..., redirectAuthHost = scServerId != null)
```

`toOffline()` is the single place a launch loses its token: blank `accessToken`,
offline uuid, `--userType legacy` downstream.

## What the sweep touches

`SmrtSyncService.pruneForeignEntries` -- one implementation, three callers
(`sync`, `applyUpdate` indirectly through the roster, `enforceRoster`):

- only `.jar` / `.zip` (`ModArchives.isLoadable`) -- a loader executes those and
  nothing else under `mods/`;
- dot-directories are not walked (`.connector` remapped jars, `.nexira-blocks`
  block maps); a dot-NAMED file in `mods/` is still swept, since the loader reads
  it like any other;
- directories are never removed;
- a delete that fails lands in `blocked`, which makes the verdict unverified --
  making a file undeletable is how one keeps it across a launch.

The roster (`.nexira-mods`) is written by `sync` and by `applyUpdate`, and by
`verifyAndRepair` only when the repair left nothing unresolved.

## Second factor

Measured protocol facts live in the project memory; the shape here follows from
them: a `login` mints a new `uid` and invalidates the previous one, and `twoauth`
returns a bare status, so the session to use is the one that arrived WITH the
demand.

```
launch (bound pack, twoFactor && !mintedNow)
  -> fail(LaunchError.TwoFactorExpired)          the launch stops, nothing spawns
  -> LaunchDriver.onError sees it, and instead of reporting a failure:
       clears the indication, dismisses the activity,
       parks TwoFactorLaunchGate.request(label, serverId) { session -> relaunch }
  -> TwoFactorPromptHost (composed INSIDE NxTheme -- a Dialog gets its own
       composition and one raised outside the theme takes the shell down):
       login(serverId)  -> TwoFactorRequiredException carries uid
       ConfirmCodeDialog -> completeTwoFactor(uid, code)
       session.copy(twoFactor = true, mintedNow = true), saved
  -> gate.resume(session) -> relaunch of the same target
  -> prepareScAuth sees mintedNow and lets it through
```

`mintedNow` is never persisted (the credential store rebuilds sessions without
it), which is what stops the relaunch from tripping the same demand again.

`twoFactor` IS persisted, and stickily: an ordinary save cannot clear it, only an
explicit clear when a login succeeds without a demand. It is set the first time a
launch meets the gate (`TwoFactorDetected` -> `AppShell` writes it), because a
session restored from disk predates the flag and would otherwise let the launcher
keep re-authenticating.

Auto-sync never logs in for such an account: the request itself is the damage.

## Closed while drawing this

Both paths used to carry the STORED session forward on the first
`TwoFactorRequiredException` -- before the account is flagged -- with a cached
manifest standing in for the sync. That was right when the plan was "carry the
session as-is" and wrong once launches mint their own: it meant the very first
launch of a 2FA account spawned with a token nothing minted for it. Both now stop
with `TwoFactorExpired` and let the gate ask for a code, so the flagged and
unflagged cases behave the same.
