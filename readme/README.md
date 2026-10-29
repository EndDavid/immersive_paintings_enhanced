# Immersive Paintings: Enhanced

An addon for **Immersive Paintings** (Fabric, Minecraft 1.18.2) that keeps the original mod
untouched but fixes the places where it does not fit the rest of a heavily modded world.

No new items, blocks or entity types are added - everything is done by teaching the existing
painting entity and painting item new tricks.

Current enhancements:

| Enhancement | Default | Config key |
| --- | --- | --- |
| Hang pictures at **45 degrees** (sneak + right click a wall) | on | - |
| Angled pictures **ignore the support rule** and never drop | on | `angledIgnoresSupport` |
| **Precise picking** for angled pictures (the oversized box no longer eats clicks) | on | `preciseAngledPicking` |
| **Create contraptions / trains ignore pictures** instead of pushing, teleporting and killing them | on | `disableContraptionCollision`, `onlyTrains` |

## Usage

| Action | Result |
| --- | --- |
| Right click a wall | normal, axis aligned painting (unchanged) |
| **Sneak + right click a wall** | painting tilted 45 degrees |

While sneaking, the diagonal is chosen by *where on the block face you click*: the picture is
centred on the vertical block edge closest to your crosshair, and the canvas is tilted away from
the block so that it only touches that edge. Clicking the left half or the right half of a face
therefore gives you the two mirror-image diagonals.

The angle is stored in Immersive Paintings' existing `rotation` field, which is already written to
NBT and sent to clients in `PaintingModifyMessage`, so:

* angled paintings survive a server restart,
* other players see them correctly without any extra packets,
* worlds stay readable if the addon is removed (they simply become normal wall paintings).

## Configuration

The first launch writes `config/immersive_paintings_enhanced.json`:

```json
{
  "disableContraptionCollision": true,
  "onlyTrains": false,
  "angledIgnoresSupport": true,
  "preciseAngledPicking": true
}
```

| Key | Meaning |
| --- | --- |
| `disableContraptionCollision` | Hide paintings from every Create contraption (trains, bearings, pistons, gantries). |
| `onlyTrains` | Only hide paintings from train carriages, leave other contraptions alone. |
| `angledIgnoresSupport` | 45 degree paintings never need a block behind them and never turn into a dropped item. |
| `preciseAngledPicking` | Crosshair and projectiles use the real tilted canvas instead of the axis aligned box. |

The file is read once at startup. The server side is authoritative for collisions, so on a dedicated
server only the server's file matters for the Create fix.

## Create compatibility

Create pushes every entity it can "see" within a box that is stretched 32 blocks upwards, and the
whole pipeline funnels through a single predicate:

```java
world.getEntitiesByClass(Entity.class, box, contraption::collidesWith)
```

Everything that predicate accepts is then bounced, teleported (`setPosition`) and damaged
(`speed * 16`). Paintings can do none of those things safely:

* `setPosition` makes a painting **re-anchor itself to a block position**, so a train used to fling
  pictures around the platform,
* `damage` kills the entity and **drops the picture**,
* a train starting from a standstill used to reach Create's movement code with an entity that had
  already been relocated or removed, which could **crash the server**.

`ContraptionCollisionMixin` cancels `AbstractContraptionEntity#collidesWith` for Immersive
Paintings entities, so the painting is never returned as a candidate at all - no maths, no state
changes. Block collision of the train is untouched, and so is every other entity.

## Compatibility

* **Immersive Paintings 0.6.7+1.18.2** - required.
* **Create 0.5.1 (Fabric 1.18.2)** - optional. Verified against `f-build.1415` and `i-build.1629`.
  The mixin is only applied when `create` is loaded.
* **Immersive Painting Blocks (`imb`)** - optional. The graffiti converter *ignores* angled
  graffiti instead of turning them into a block, because that mod's block renderer only supports
  the six cardinal facings. The compatibility hook is a mixin that is only applied when `imb` is
  loaded, and it is referenced by class name, so this addon does not need the blocks mod to
  compile or to run.
  *Converting angled graffiti is possible in principle*: the block entity already stores a yaw, so
  the block renderer would only need to apply `Vec3f.POSITIVE_Y.getDegreesQuaternion(-yaw)` for
  horizontal facings as well. That is left for a follow-up because it has to be verified visually.
* Glow paintings and graffiti (transparent pictures) support angled placement as well - they share
  the same base entity.

None of the optional integrations is a compile time dependency; a missing mod only disables the
corresponding mixin config.

## How it works

### Angled placement

`AbstractImmersiveDecorationEntity#updateAttachmentPosition` is the single place where a painting
turns its `(attachmentPos, facing, rotation)` triple into a position, a yaw and a bounding box.
For `rotation % 90 != 0` that method is replaced by:

1. pick the vertical edge of the clicked face whose plane does not cut through the block,
2. put the canvas centre `1/32` block in front of that edge along the diagonal normal,
3. yaw the entity by `facing.getHorizontal() * 90 + rotation` - Immersive Paintings' renderer
   rotates by `-yaw`, so the canvas, frame and normals follow automatically,
4. build an axis aligned box around the tilted rectangle.

The item is untouched apart from the rotation argument of `newPainting`, so permission checks,
glow/graffiti variants, sounds, item consumption and the client-server handshake keep working.

### Files

* `angled/DiagonalGeometry` - all the 45 degree maths, no Minecraft side effects.
* `angled/mixin/AbstractDecorationMixin` - diagonal position / bounding box, support bypass.
* `angled/mixin/ImmersivePaintingItemMixin` - sneak-to-angle placement.
* `angled/mixin/ProjectileUtilMixin` - precise crosshair picking against the tilted canvas.
* `angled/mixin/ImbEventsMixin` + `ImbMixinPlugin` - optional blocks mod compatibility.
* `create/mixin/ContraptionCollisionMixin` + `CreateMixinPlugin` - optional Create compatibility.
* `config/IpeConfig` - the JSON config.

The Create mixin targets the method by its runtime name (`method_30949`, because Create overrides
`Entity#collidesWith`) *and* by its source name, both with `remap = false` and `require = 0`. That
way it works both in a production instance and in a development environment where Create is
remapped to yarn names, and an unexpected Create update degrades to "feature off" instead of a
crash.

## Building

Requires a JDK 21 to run Gradle (the mod itself targets Java 17):

```powershell
$env:JAVA_HOME = "D:\codename_EndDavid\hello java\jdk-21.0.2"
.\gradlew.bat build
```

The jar ends up in `build/libs/`.

## Known limitations

* The entity's bounding box is still the axis aligned cube around the diagonal; only *picking* and
  projectile collision use the real canvas. `isCollidable()` is untouched, so the box never blocks
  movement.
* `Entity#collides` is intentionally *not* overridden. The client uses it as the predicate for
  crosshair picking, so returning `false` would make angled paintings impossible to edit or break.
* Only walls (horizontal faces) are supported. Floors and ceilings keep their vanilla behaviour.
* Angled paintings are centred on a block edge, not freely positionable; multi-block pictures
  extend symmetrically from the edge you clicked.
* Teleport commands (`/tp @e[type=immersive_paintings:painting]`) already re-derive the attachment
  block by flooring the entity centre in Immersive Paintings itself; for angled paintings that can
  shift them by one block. Placing, editing and reloading are unaffected.
* The Create fix is a mixin against Create's `collidesWith`. If a future Create version stops using
  that predicate for contraption collisions, the fix silently stops working (the game keeps
  running, and the addon logs whether the mixin was applied).

## Picking and interaction

An axis aligned box around a 45 degree rectangle is unavoidable (Minecraft stores a single `Box`),
and it covers roughly 41% more area than the picture. Vanilla picks entities by raycasting against
that box, so at first the empty corners swallowed clicks: right clicking a block that happened to
sit inside the box opened the painting editor, and blocks could not be placed or broken there.

`ProjectileUtilMixin` fixes this. Every entity raycast (`ProjectileUtil.raycast`, used by
`GameRenderer#pick` for the crosshair) and every projectile collision test is re-validated against
the real rotated canvas:

* a hit that only touched the empty corner is discarded and the search continues, so the block or
  entity behind it is selected instead;
* a real hit reports the exact canvas intersection rather than the box entry point, which matters
  because vanilla compares that position with the block hit to decide what the crosshair is on.

The box itself still exists (it also drives entity tracking), so a hitbox debug view will keep
showing the large diagonal cube. It simply no longer influences interaction.

Cardinal paintings are never touched by this mixin.

## License

This mod is licensed under the **GNU General Public License v3.0 only** (`GPL-3.0-only`), the same
license as Immersive Paintings itself. See [LICENSE](LICENSE) for the full text.

Why it has to be GPL rather than something permissive: this is not a standalone program that merely
happens to be loaded next to Immersive Paintings. It is compiled against that mod, imports its
classes (`ImmersivePaintingEntity`, `AbstractImmersiveDecorationEntity`, `ImmersivePaintingItem`),
reads its protected fields through `@Shadow`, and rewrites its bytecode at runtime through Mixin.
That makes it a derivative work of a GPL-3.0-only project, so it must be distributed under the same
license. The optional Create and Immersive Painting Blocks integrations are covered by the same
license, because they ship in the same jar.

Attribution:

* Immersive Paintings - Copyright Luke100000 and contributors, GPL-3.0-only
  (<https://github.com/Luke100000/ImmersivePaintings>).
* This addon - Copyright its authors, GPL-3.0-only.

Because the GPL requires that recipients can obtain the corresponding source, any distributed jar
should be accompanied by this repository or by the sources jar produced by `./gradlew build`
(`build/libs/*-sources.jar`). If you distribute a modified version, state the changes and keep the
license and copyright notices intact - you may not relicense it under a more permissive or a more
restrictive license.

