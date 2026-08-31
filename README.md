# Changed Creator

A **no-code custom latex form (transfur) maker** for [Changed: Minecraft Mod](https://github.com/LtxProgrammer/Changed-Minecraft-Mod) (Forge 1.20.1, Changed v0.15.7).

Create your own latex forms — model preview, texture painting, glow (emissive) layers — entirely from a **web editor** opened from inside the game. No Java, no Gradle, no restart needed for most changes.

## Features

- **WebUI editor** embedded in the game (local server at `http://127.0.0.1:28654`, openable from the main menu / pause menu button)
- **Real-time 3D model preview** — drag to rotate, arrow keys to pan, U to reset; click a cube to select, click again to drill into inner cubes
- **Pixel texture editor** — brush / fill bucket / color picker, RGBA sliders + HEX + color wheel, zoom & pan, undo/redo
- **Glow (emissive) layers** — paint fluorescent areas with any color; the model preview and in-game rendering show them
- **Live form editing** — edit id / base_entity / transfur_mode / abilities / properties / tint / texture
- **Hot-register** — add or update a form **without restarting the game** (server-friendly)
- **Delete forms** at runtime (registry + config files)
- **Tint** recolors the latex body AND the UI (inventory / ability wheel)
- **Cross-platform** — Windows / Linux (Forge 1.20.1)

## Requirements

| Item | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.x |
| [Changed: Minecraft Mod](https://github.com/LtxProgrammer/Changed-Minecraft-Mod) | 0.15.7 (bundles mixinextras itself) |
| Changed Creator | this mod (jar) |

## Installation

```
mods/
  changedcreator-1.0.0.jar          ← this mod
  Changed-m1.20.1-v0.15.7-all.jar   ← Changed (download from its releases)
```

First launch auto-creates `config/changedcreator/forms/`. Recommended: set `downloadPatreonContent = false` in `config/changed-common.toml` to avoid network hangs at startup.

## Quick Start

1. Launch the game → click the **square editor button** on the title screen (next to the language button) or the pause menu
2. Click "在浏览器打开 WebUI" / **"Open WebUI in browser"** (`http://127.0.0.1:28654`)
3. Pick an **original example** on the left for reference, or click **"＋ New form"**
4. Fill in: `id` (lowercase letters/digits/underscore), `base_entity` (**pick from the dropdown** — a registered latex entity), abilities/properties
5. Click **Save** → click **Hot-register (no restart)** → in game: `/transfur @s changedcreator:<your-id>`

> After changing `id`/`base_entity`/abilities/properties you must **hot-register again or restart** (registry is frozen at startup).
> **tint / texture** changes apply automatically within ~2 seconds.

## Editor Highlights

- **3D preview**: drag to rotate; arrow keys move the camera (up/down = vertical), **U** resets; **E** enters edit mode (selected cube opaque, others translucent) to paint directly on that cube's texture region
- **Texture editing**: pixel brush / flood fill / eyedropper; zoom with wheel, pan with arrow keys; **Ctrl+Z / Ctrl+Y** undo/redo
- **Glow layer**: click「发光层」to edit the emissive layer (base texture shown at 50% as reference); painted color = glow color; export saves `textures/<id>_emissive.png`
- **Form management**: ✕ deletes a form at runtime; 「热注册」registers/updates without restart

## Configuration

```
config/changedcreator/
  forms/<id>.json            ← form definition
  appearance.json            ← tint / texture overrides
  textures/<id>.png          ← exported body texture
  textures/<id>_emissive.png ← exported glow texture
  models/                    ← model cache (auto-generated)
  imports/                   ← in-game import drop folder
```

See [`docs/使用说明.md`](docs/使用说明.md) (Chinese) for the full field reference and examples.

## Multiplayer

- **Forms (variants)**: registered on the server → synced to clients on join; both server and clients need this mod + Changed
- **Hot-register**: run it on the server AND on each client; already-connected players must **rejoin** to see new forms
- **Appearance (tint/texture/glow)**: client-side; distribute the same `config/changedcreator/` if you want identical looks
- **Not verified!**

## FAQ

| Issue | Fix |
|---|---|
| `/transfur` error | Check `abilities` for an invalid id (the editor lists valid ones and blocks bad input) |
| New form missing in game | Hot-register or restart; ensure `base_entity` is a valid entity from the dropdown |
| Startup hang | Set `downloadPatreonContent = false` |
| Editor won't open | Check log for `Editor WebUI available at http://127.0.0.1:28654`; port auto-falls back if busy |
| No glow | Make sure you painted the glow layer, saved (exports `_emissive.png`), and restarted/hot-registered |

## License

MIT — see [LICENSE](LICENSE). This mod is an independent addon; all Changed assets/API belong to their respective authors.
