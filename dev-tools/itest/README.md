# itest -- integration runs against the GUI

Drives the real launcher window through the puppet control surface and checks
what it did to the instance and to the process it spawned. The terminal front
end (`gum`) only collects what cannot be automated: which account, its
password, the code when the server asks for one, which pack is bound and which
is not.

This is not a headless test suite. It signs in for real, launches real games
and takes minutes. What it covers is exactly the part unit tests cannot reach:
the rules that only exist once a launch is running.

## Requirements

`gum`, `jq`, `curl`; `Xvfb` for the isolated display (recommended -- a run
opens windows and starts games).

## Running

```sh
./dev-tools/itest/run.sh
```

The run asks for a data directory and refuses the real one. Everything the
launcher writes -- instances, clients, libraries, the keyring's local
counterparts -- lands there, so a run cannot damage the installation being
developed against.

## What it does NOT isolate

The system keyring. It is not under the data directory, so a run that signs in
overwrites the saved account of the developer's own launcher, and a real
SmartyCraft sign-in invalidates whatever session was live -- the previous one
dies the moment a new one is minted. The run says so before it asks for a
password.

## Scenarios

| file | what it pins |
|---|---|
| `10-login.sh` | the form signs in, and asks for a code exactly when the account has a second factor |
| `20-bound-pack-sweep.sh` | a bound pack loses foreign mods and keeps configs, resource packs, loader caches and non-archives |
| `30-unbound-pack-untouched.sh` | a pack with no server keeps what the user put in it |
| `40-undeletable-mod-costs-the-token.sh` | a mod that refuses to be deleted drops the launch to the offline identity |

Scenarios read their session verdict off the game's own command line
(`--userType`): `mojang` for a real session, `legacy` for the offline
identity. The access token sits next to it and is never read, printed or
logged.

## Adding one

A scenario is a file in `scenarios/` that sets `NAME` and defines `run`. It
gets `lib/common.sh` (control surface, waits, process probes) and
`lib/assert.sh` (checks and the tally) already sourced, plus the run config in
the environment: `ITEST_DATA_DIR`, `ITEST_ACCOUNT`, `ITEST_USER`,
`ITEST_PASS`, and for pack scenarios `ITEST_BOUND_PACK` / `ITEST_BOUND_DIR`.

Report through `ok` / `fail` (or the `assert_*` helpers) rather than exiting:
a scenario's checks explain each other, and one failure should not hide the
rest.
