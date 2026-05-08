# Super Lead / 万物皆可绳

[简体中文](README_zh.md)

**Super Lead** is a Minecraft NeoForge mod built around one simple question:

> Have you ever wondered whether a lead could do more than just leash pigs?

This mod expands the vanilla lead into a visual, lightweight, no-pipe connection tool. Redstone signaling, energy transfer, item transport, and fluid flow are all designed around one familiar object: a rope.

Simple interactions, readable visuals, and that classic vanilla sagging line.

After all, connection does not have to be complicated.

## Core Idea

Super Lead keeps the vanilla intuition of the lead: two points, one visible connection. Instead of introducing bulky pipe blocks or invisible wireless links, it turns the lead into a small engineering language for connecting blocks, machines, signals, and inventories directly in the world.

The design goal is to feel like something that naturally grew out of Minecraft:

- use the vanilla lead item as the base interaction;
- attach leads to block faces, not just mobs and fence knots;
- keep the familiar hanging rope curve;
- show connections directly in the world;
- avoid large UI flows for basic setup;
- use distance, capacity, and single-purpose upgrades for balance.

## Features

### Block-to-block leads

Right-click a block face with a lead to select the first anchor, then right-click another block to create a connection. Anchors are calculated from the block shape and face direction so that ropes land naturally on full blocks, partial blocks, machines, fences, and decorative blocks.

Connections are saved with the world and synchronized to clients.

### Physical rope rendering

Super Lead renders custom rope geometry with a small square thickness instead of relying on a flat leash strip. The rope simulation uses a client-side Verlet-style solver with gravity, damping, distance constraints, block collision, and rope-to-rope separation.

The result is a visible connection that still feels close to the vanilla leash silhouette.

### Redstone Lead

The first functional upgrade is the **Redstone Lead**.

- Upgrade a vanilla lead with a redstone block.
- Use a redstone block on an existing normal lead connection to upgrade it.
- Redstone leads use a red and dark-red visual gradient.
- Powered redstone leads become brighter, emit light visually, and occasionally spawn redstone particles.
- Redstone power is transmitted between the two connected anchors.

When holding a redstone block and aiming at an upgradeable lead, the target connection shows a red ghost overlay. Holding shears still shows the cut preview overlay.

### Cutting connections

Use shears on a lead connection to cut it and drop the corresponding lead item. The same reusable connection-action system is also used by redstone upgrades, making future lead interactions easier to add.

## Planned Functional Leads

Super Lead is designed as a single-base-item system: a lead can be upgraded into one functional type at a time. This keeps the mechanics understandable and the visuals easy to read.

| Type | Example material | Visual direction | Purpose |
| --- | --- | --- | --- |
| Redstone Lead | Redstone block | Red / dark red | Transmit redstone signal |
| Item Lead | Hopper / iron | Gray | Slow item transfer between inventories |
| Energy Lead | Copper / redstone | Orange | Transfer FE-compatible energy |
| Fluid Lead | Bucket / glass bottle | Blue | Connect fluid containers |

## Balance

Super Lead is not meant to replace every pipe, cable, or logistics mod. It focuses on flexibility, visibility, and vanilla-like simplicity.

Current and planned balance principles:

- limited connection distance;
- one function per lead;
- visible connections instead of hidden networks;
- lower throughput than specialized logistics systems;
- connections require compatible endpoints;
- invalid or overextended connections are pruned.

## Roadmap

### Milestone 1: Connection Prototype

- Block-face anchoring.
- Persistent connections.
- Visible ropes in-world.

### Milestone 2: Functional Leads

- Redstone lead upgrade.
- Functional coloring.
- Upgrade and cut previews.

### Milestone 3: Transfer Systems

- Item, energy, or fluid transfer.
- Distance and capacity configuration.
- Better handling for block break, unload, and dimension edge cases.

### Milestone 4: Polish

- More particles, sounds, and tooltips.
- Better screenshots and showcase scenes.
- Optional relay nodes for longer networks.

## Why Not Pipes?

Super Lead is intentionally not another pipe block system.

- Compared with pipes, it is lighter and does not occupy a full block space.
- Compared with wireless transfer, it is more readable because the connection is visible.
- Compared with a completely new system, it is more vanilla because players already understand how leads work.

One ordinary item, many extraordinary connections.

## Requirements

- Minecraft: `26.1.2`
- NeoForge: `26.1.2.43-beta` or compatible `26.x`
- Java: `25`

## Build

This is a Gradle-based NeoForge project. To compile the mod locally, run the Gradle build task from the repository root.

## License

Apache 2.0