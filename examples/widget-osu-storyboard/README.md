# osu! storyboard, as a widget module

A second worked example, written to answer one question and not to ship a feature:
**can a widget module built only against the published kernel carry a genuinely complex
piece of motion, or does the API run out first?**

An osu! storyboard is a good test because nobody designed it for this launcher. It is a
declarative sprite timeline: images positioned in a 640x480 space, driven by typed tweens
over fade, move, scale, vector scale, rotation, colour, additive blending and flips, with
nested loops. It is decorative motion at about the hardest setting a launcher would ever
want.

Measured against a real beatmap (`takehirotei - Haiboku no Altra Vita`):

| | |
|---|---|
| sprites | 939 |
| tweens | 19691 |
| distinct images | 281 |
| duration | 179 seconds |
| something visible | 713 of 717 sampled instants |

Frames render off-screen through `RenderFramesTest`, which writes PNGs into
`build/storyboard-frames/`. Point either test at a folder with
`-Dosusb.folder=/path/to/Songs/<beatmap>`.

## What the API carried

All of this needed nothing but `widget-model` and `widget-api`:

- `@Widget` with a `@Serializable` props class, and `rememberProps` to read it typed.
- Arbitrary Compose drawing. Canvas, per-sprite transforms, `BlendMode.Plus` for additive
  sprites, `ColorFilter.tint` for colour commands. Nothing was out of reach.
- Image decoding from disk, through the Skia that already comes with Compose rather than
  through anything the launcher owns.
- A frame clock, via `withFrameNanos`.
- Registration. KSP emits the module's own registry object and its `ServiceLoader` entry,
  the jar carries three manifest attributes, and the launcher discovers it from the widgets
  directory. The launcher was not modified and is not compiled against this module.

The drawing half of the API is not the problem. A module can draw anything.

## What the API did not carry

Each of these is a real gap found by hitting it, not a wish.

1. **No audio.** A storyboard is meant to be synchronised to the track beside it. The
   cross-widget command set is three entries and none of them plays a sound. A module can
   depend on a media library directly, which is what the pixel-player example does, but
   then every module ships its own media stack: no shared transport, no ducking against
   the launcher's own player, and two widgets playing at once simply fight.
2. **No time source.** The clock here accumulates wall time. There is no media position,
   no transport, no shared clock of any kind in the SPI, so nothing can be synchronised to
   anything.
3. **No theme.** `widget-api` exposes no colour and no typography, so the letterbox is a
   hardcoded black. In a light palette this is a black rectangle in a pale application, and
   the module has no way to ask what it should have been.
4. **No strings.** `displayName` resolves through a table the launcher owns, and an
   unknown key returns itself, so this widget is listed in the palette as
   `osusb.storyboard`. A module cannot name itself in the reader's language.
5. **No asset or file story.** The beatmap folder is a free-text prop: the user types an
   absolute path into a text box. A module cannot ask for a folder, cannot declare that a
   prop is a path, and has nowhere of its own to keep files.
6. **No lifecycle.** Nothing tells a widget that its surface was left or that it is no
   longer visible. The frame loop runs for as long as the composition does, which is
   correct here and would be wrong for anything expensive left behind a screen.
7. **No declared footprint.** The widget fills whatever it is given. It cannot say that it
   wants 16:9, so in a narrow column it letterboxes itself into a strip and nothing warns
   anyone.

## What this implementation does not do

Separate from the API, and honest about it:

- `Animation` objects (a sprite with numbered frames) are read as static sprites, so seven
  of the 281 images resolve to nothing.
- `T` (trigger) commands are parsed and dropped. They fire on hit sounds, which this
  application has no notion of.
- The easing table covers the curves this storyboard uses and falls back to linear
  elsewhere.
- Loops are expanded at parse time, with a ceiling so a pathological file cannot exhaust
  the heap.

## Building

```sh
./gradlew :examples:widget-osu-storyboard:installWidget
```

Drops the jar into `~/.local/share/nexira/widgets`, which is the entire install procedure.
Then place the widget on any surface and set its `folder` prop to a beatmap directory.

## Three widgets, and why there are three

The first one was a player, and a player is not an event. The module now carries three,
which is the argument rather than a feature list:

| id | what it is | what it proved |
|---|---|---|
| `osusb.storyboard` | replays the authored timeline | the kernel can carry heavy declarative motion |
| `osusb.altravita` | composes the art into a live surface | the kernel can carry authored motion and pointer response |
| `osusb.rhythm` | a playable round off the real beatmap | the kernel can carry a clock, input and a result |

The rhythm round reads the six difficulties beside the art (235 to 980 notes, AR 4 to 9.5)
and runs one: notes approach on the beatmap's own timing, the pointer answers them, and the
judgement windows come from the map's own OD. It keeps a combo, an accuracy and a best run.

And it is still not a performance, which is the real finding. A performance would take the
screen, play the track it is timed to, dim everything else, and give it all back at the
end. The kernel offers a widget none of that: it cannot seize a surface, cannot go
fullscreen, cannot play the audio the beatmap is written against, cannot quiet the rest of
the application, and is never told when it is over. So the round is silent, confined to its
slot, and ends by simply running out of notes.

That is the honest answer to "can a module stage an event": it can build every part that
happens inside its own rectangle, and none of the parts that make it an event.
