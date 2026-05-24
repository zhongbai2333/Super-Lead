# Super Lead / 万物皆可绳

[简体中文](README_zh.md)

**One rope. Every connection.**

Super Lead is a NeoForge mod that turns the humble lead into a universal connection tool. Redstone, energy, items, fluids — all through a single familiar rope.

---

## Lead Kinds

Right-click a rope with the matching material to convert or upgrade it.

| Kind | Convert with | Upgrade with | Effect |
|------|-------------|-------------|--------|
| **Redstone** | Redstone Block | — | Bidirectional redstone signal between anchors |
| **Energy (FE)** | Iron Block | Redstone Block | FE transfer, tiered throughput (1x → 2x → 4x…) |
| **Item** | Hopper | Chest | Item transport, round-robin routing |
| **Fluid** | Cauldron | Bucket | Fluid transport between tanks |
| **Pressurized** | Steel Block | Reinforced Alloy | Mekanism chemical/gas transfer |
| **Thermal** | Copper Block | Reinforced Alloy | Mekanism heat transfer |
| **AE Network** | Fluix Block | 16³ Spatial Component | AE2 channel bridging |

### Energy
- Base throughput: 256 FE/t at tier 0, doubles per tier
- Maximum tier configurable (default 30)
- Powered ropes glow and emit particles

### Item & Fluid
- Toggle extract direction by clicking an endpoint with the conversion item
- Tier 0: 1 item / 1000 mB per pulse; doubles per tier
- Multi-rope fence-knot routing with round-robin

---

## Usage

### Placing Ropes
Right-click a block face → set first anchor. Right-click another block → create connection. Ropes attach to any block face, not just fences.

### Extending
New ropes: after setting the first anchor, click the same anchor again to spend 1 lead and extend the pending length by 1× (up to 4×). Existing ropes: sneak + right-click an anchor to extend. Extended ropes cost proportionally more to upgrade.

### Cutting
Cutting a rope drops leads equal to its length multiplier (2× rope drops 2 leads, 4× drops 4).

### Attachments
Hold an attachable item (lantern, sign, block) in your main hand and String in your offhand. Right-click a rope to hang it. Signs are editable in-world. Toggle block/item display modes.

### Cutting
Shears on a rope → cut and drop. Shears on a fence knot → cut all ropes on that knot.

### Ziplines
Attach a Chain to start ziplining along any rope.

---

## Physics

Client-side Verlet simulation with gravity, damping, block collision, rope-to-rope repulsion. Ropes auto-freeze when settled — zero CPU cost at rest.

---

## Configuration

`config/super_lead-common.toml`:

| Setting | Default | Description |
|---------|---------|-------------|
| `energy.base_transfer_per_tick` | 256 | Base FE/t at tier 0 |
| `energy.tier_max_level` | 30 | Maximum energy upgrade tier |
| `network.max_leash_distance` | 12.0 | Blocks per length unit |
| `network.max_ropes_per_block_face` | 8 | Max ropes per block face |
| `network.item_transfer_interval_ticks` | 4 | Ticks between item transfers |
| `network.fluid_bucket_amount` | 1000 | mB per fluid transfer |

---

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21

Optional: [Mekanism](https://www.curseforge.com/minecraft/mc-mods/mekanism), [Applied Energistics 2](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2)

---

## License

Apache 2.0

