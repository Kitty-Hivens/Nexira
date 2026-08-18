# experimental

Not the shape the launcher is built in. Groundwork and notes for a module-loading
architecture, parked deliberately: the launcher's near-term work is issues, widget
injection and bugs, and this is a marathon that would sit across all of it.

Kept rather than deleted because the reasoning is expensive to re-derive and most of
it is measurement rather than opinion.

| | |
|---|---|
| `ui-layer-map.md` | what the UI layer IS, every figure measured from source read end to end at `63b6f097`. Useful on its own, independent of any architecture decision. |
| `widget-plugin-plan.md` | the module model: taxonomy, the core-assembles-itself pivot, three tiers, two configs, extension points, and the measured blockers. |
| `player-cluster-audit.md` | the music/video widgets checked against their six open issues; four of them describe a state the code has left. |
| `client-boot/` | the boot config and its reader, with tests. The one piece of the model that exists as code. |

`:experimental:client-boot` stays in the Gradle build so it keeps compiling and its
tests keep running. Nothing depends on it.
