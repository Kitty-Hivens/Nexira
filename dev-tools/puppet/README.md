# puppet — CLI driver for Nexira's in-process control surface

Nexira's UI exposes a small HTTP control surface (see
`client-ui/src/desktopMain/kotlin/hivens/ui/puppet/`) when launched with
`-Dnexira.puppet.port=N`. These scripts are thin curl wrappers around
that surface, intended for ad-hoc UX debugging and automated regression
scenarios.

## Enabling puppet mode

```sh
./gradlew :client-ui:run \
    -Pkotlin.jvm.target.validation.mode=ignore \
    -Dnexira.puppet.port=58000
```

Without the system property, the server never binds — release builds
cannot accidentally expose this surface.

## Scripts

All scripts default to `127.0.0.1:58000`. Override via
`PUPPET_HOST=...` and `PUPPET_PORT=...` env vars.

* `screen.sh`              — current top-level screen name
* `elements.sh`            — full snapshot (screen + registered widgets)
* `click.sh <id>`          — click a button / icon
* `set-field.sh <id> <v>`  — fill a text field
* `set-toggle.sh <id> <v>` — flip a switch (true/false)

## Scenarios

* `login.sh <user> <pass>`         — login form -> dashboard
* `select-server.sh <assetDir>`    — click a server card
* `launch-server.sh <assetDir>`    — full flow: select + click PLAY

## Diagnostics (read-only)

Structured JSON dumps of JVM internals aimed at AI / automated profiling
rather than human eyes. Handlers run off `Dispatchers.Swing` on purpose
so the diagnostic call never blocks (or unblocks) the thread it is meant
to inspect. Pipe through `jq` for human-readable form.

* `diag-threads.sh`   — ThreadMXBean dump (state, stack, locks held,
                        deadlock detection)
* `diag-jvm.sh`       — memory, GC, runtime, OS load
* `diag-actions.sh`   — last 64 `ActionRing` entries (Nexira's in-process
                        breadcrumb log)
* `diag-snapshot.sh`  — all of the above + the UI snapshot in one
                        round-trip; the "something is wrong, give me
                        everything" call

Suggested freeze-diagnosis workflow:

```sh
./diag-snapshot.sh > /tmp/baseline.json     # before the suspect action
./launch-server.sh skyblock                 # trigger the suspect action
./diag-snapshot.sh > /tmp/frozen.json       # while symptom is visible
diff <(jq . /tmp/baseline.json) <(jq . /tmp/frozen.json)
```
