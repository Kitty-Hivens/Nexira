# puppet — CLI driver for Aura's in-process control surface

Aura's UI exposes a small HTTP control surface (see
`client-ui/src/desktopMain/kotlin/hivens/ui/puppet/`) when launched with
`-Daura.puppet.port=N`. These scripts are thin curl wrappers around
that surface, intended for ad-hoc UX debugging and automated regression
scenarios.

## Enabling puppet mode

```sh
./gradlew :client-ui:run \
    -Pkotlin.jvm.target.validation.mode=ignore \
    -Daura.puppet.port=58000
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
