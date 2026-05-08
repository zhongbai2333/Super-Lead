# Super Lead / 万物皆可绳

[简体中文](README_zh.md)

**Super Lead** is a Minecraft NeoForge mod built around one simple question:

> Have you ever wondered whether a lead could do more than just leash pigs?

This mod expands the vanilla lead into a visual, lightweight, no-pipe connection
tool. Redstone signaling, FE energy transfer, item transport, and fluid transfer
all live on a single familiar object: a rope.

Simple interactions, readable visuals, and that classic vanilla sagging line —
because connection does not have to be complicated.

## Core Idea

Super Lead keeps the vanilla intuition of the lead: two points, one visible
connection. Instead of pipe blocks or invisible wireless links, the lead becomes
a small engineering language for connecting blocks, machines, signals, and
inventories directly in the world.

- The vanilla `Lead` item is the only base item.
- Anchors snap to block faces, not just mobs and fence knots.
- Familiar hanging rope curve, rendered with a thin square geometry.
- Connections are stored with the world and synced to all clients.
- All upgrades and toggles use single right-clicks; no machine GUIs.
- Distance, capacity, and per-rope tiers control the balance.

## Lead Kinds

A lead is upgraded in place by right-clicking it (or a connection in the world)
with a specific material. Each connection has exactly one kind.

| Kind | Convert with | Tier-up with | Color | Purpose |
| --- | --- | --- | --- | --- |
| Normal | — | — | brown | Cosmetic / staging |
| Redstone | Redstone Block | — | red / dark red | Two-way redstone signal between anchors |
| Energy | Iron Block | Redstone Block | orange | FE energy transfer (tiered throughput) |
| Item | Hopper | Chest | steel blue | Item transfer (tiered batch size) |
| Fluid | Cauldron | Bucket | teal | Fluid transfer (tiered batch size) |

Conversion materials work either by right-clicking an existing rope in the world
or by right-clicking the held lead with the material in the off-hand. Tier-up
materials only apply to ropes of the matching kind.

### Energy Lead

- Power and tier are stored on the connection.
- Tier 0 connects two FE handlers with a small base throughput.
- Each redstone-block tier-up doubles the per-tick throughput.
  Total cost to reach tier `T+1` from `T` is `1 << T` redstone blocks.
- The tier ceiling is configurable.
- Powered ropes brighten and emit redstone particles; idle ropes go dim.

### Item Lead

- Right-click an endpoint block with a hopper to **toggle that anchor as the
  extract source**. The chosen end of the rope renders permanently fat as a
  visual indicator of "this is where items come from".
- Tier 0 transfers 1 item per pulse. Tier `t` transfers `min(64, 1 << t)`
  per pulse, up to tier 6 (one full stack).
- Each chest tier-up costs `1 << t` chests total; the held chest counts as one.
- A "snake bulge" travels along the rope each time a transfer happens, in the
  direction of flow.
- Multi-rope routing: if several Item leads share a fence knot, the knot acts as
  a junction and round-robins through the outgoing ropes (DFS through the
  knot graph). Targets that refuse the insertion are skipped without losing
  the cursor position, so a full chest never blocks a parallel one.

### Fluid Lead

- Same toggle / tier / animation rules as the Item lead.
- Cauldron right-click on an endpoint block toggles the extract source.
- Tier 0 moves 1000 mB (1 bucket) per pulse. Tier `t` moves
  `1000 mB × (1 << t)`, up to tier 4 (16 buckets per pulse).
- Each bucket tier-up costs `1 << t` buckets total.
- Works on any block exposing `Capabilities.Fluid.BLOCK`.

## Visuals and Controls

### Block-to-block leads

Right-click a block face with a vanilla lead to set the first anchor; right-click
another block to create the connection. Anchors are computed from the block
shape and clicked face so ropes land naturally on full blocks, partial blocks,
machines, fences, and decorative blocks.

### Physical rope rendering

Super Lead uses custom rope geometry — a small square thickness — rather than
the flat vanilla leash strip. The rope simulation is a client-side Verlet
solver with gravity, damping, distance constraints, block-AABB collision, and
rope-to-rope separation. Settled ropes auto-freeze each tick to keep the
simulation cheap and visually stable.

### Hover ghost preview (client-validated)

When holding a tool that targets a connection (shears, redstone block, iron
block, hopper, chest, cauldron, bucket), the rope you are aiming at gets a
colored ghost overlay. The overlay is what tells the server which connection to
upgrade or cut: the client picks the target, then sends a `UseConnectionAction`
packet to the server. The server only acts on the connection the client
confirmed (with a reach check). This means:

- if no rope is highlighted, vanilla item behavior runs normally
  (e.g. a bucket scooping water from a cauldron is never hijacked into a
  rope upgrade unless your crosshair is actually on the rope);
- the visual feedback you see and the action you trigger are always in sync.

### Cutting connections

Use shears on any rope to cut it; the corresponding lead item drops in the
world. Shears on a fence knot cut every rope attached to it.

### Tooltips

Aiming at a connection while holding shears shows a small crosshair tooltip with
the rope kind, tier multiplier (where applicable), and powered state.

## Persistence and networking

- Connections are stored in saved data per dimension and survive world reload,
  block break recovery (when both endpoints still exist), and server restart.
- A removed endpoint, an air block under an anchor, or a stretched-too-far
  endpoint will prune the connection automatically.
- Fence knots are recreated on load to match stored connections.
- Sync packet (`SyncConnections`) updates every client in the dimension on any
  change. Item/fluid transfers also broadcast a tiny `ItemPulse` payload per
  successful transfer to drive the bulge animation.

## Balance

Super Lead is not meant to replace pipe or cable mods. It focuses on flexibility,
visibility, and vanilla-like simplicity:

- limited connection distance (12 blocks per rope segment);
- one functional kind per rope;
- fully visible connections in the world, no hidden networks;
- per-tier exponential material cost for higher throughput;
- both endpoints must expose the matching capability;
- invalid or overextended connections are pruned each tick.

## Roadmap

| Milestone | Status | Highlights |
| --- | --- | --- |
| 1. Connection prototype | done | Block-face anchoring, persistent connections, in-world ropes. |
| 2. Functional leads | done | Normal / Redstone / Energy / Item / Fluid. |
| 3. Transfer systems | done | Tiered FE / item / fluid transfer with extract toggling, fence-knot routing, and per-pulse animation. |
| 4. Polish | in progress | Tooltips, client-validated targeting, sound, particles, screenshots. |

## Why Not Pipes?

- Lighter than pipes — does not occupy a full block space.
- More readable than wireless transfer — every connection is visible.
- More vanilla than a brand-new system — players already know how a lead works.

One ordinary item, many extraordinary connections.

## Requirements

- Minecraft: `26.1.2`
- NeoForge: `26.1.2.43-beta` or compatible `26.x`
- Java: `25`

## Build

This is a Gradle-based NeoForge project. From the repository root:

```bash
./gradlew build
```

The runnable jar is produced under `build/libs/`.

## License

Apache 2.0
