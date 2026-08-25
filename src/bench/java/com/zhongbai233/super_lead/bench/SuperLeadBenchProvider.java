package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchApiVersion;
import com.zhongbai233.bench.api.BenchCompatibility;
import com.zhongbai233.bench.api.ScenarioDescriptor;
import com.zhongbai233.bench.api.neoforge.client.BenchClientProvider;
import com.zhongbai233.bench.api.neoforge.client.BenchClientRegistrar;
import com.zhongbai233.bench.api.neoforge.server.BenchServerContext;
import com.zhongbai233.bench.api.neoforge.server.BenchServerProvider;
import com.zhongbai233.bench.api.neoforge.server.BenchServerRegistrar;
import com.zhongbai233.bench.api.neoforge.server.BenchServerScenario;
import com.zhongbai233.bench.api.neoforge.server.BenchStepResult;
import java.time.Duration;
import java.util.Set;
import net.neoforged.fml.ModList;

/** BenchMod scenarios for validating Super Lead in isolated server and client runs. */
public final class SuperLeadBenchProvider implements BenchServerProvider, BenchClientProvider {
    @Override
    public String id() {
        return "super-lead-bench";
    }

    @Override
    public BenchCompatibility compatibility() {
        return BenchApiVersion.currentCompatibility();
    }

    @Override
    public void registerServer(BenchServerRegistrar registrar) {
        registrar.register(
                new ScenarioDescriptor("super_lead.server-load", "Super Lead Server Load", Set.of("super_lead", "smoke"),
                        Duration.ofSeconds(20)),
                context -> new ServerLoadScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.item-same-face-fanout", "Super Lead Item Same-Face Fanout",
                Set.of("super_lead", "server", "item", "transfer", "fanout", "performance"),
                Duration.ofSeconds(45)),
            context -> new ItemSameFaceFanoutServerScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.item-unloaded-source-index",
                "Super Lead Item Unloaded Source Index",
                Set.of("super_lead", "server", "item", "transfer", "index", "jfr", "performance"),
                Duration.ofSeconds(50)),
            context -> new ItemUnloadedSourceIndexServerScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.redstone-vanilla-control-before",
                "Super Lead Redstone Vanilla Control Before",
                Set.of("super_lead", "server", "redstone", "control", "performance"),
                Duration.ofSeconds(45)),
            context -> new RedstoneVanillaControlServerScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.redstone-network-load", "Super Lead Redstone Network Load",
                Set.of("super_lead", "server", "redstone", "network", "performance"),
                Duration.ofSeconds(45)),
            context -> new RedstoneNetworkLoadServerScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.redstone-vanilla-control-after",
                "Super Lead Redstone Vanilla Control After",
                Set.of("super_lead", "server", "redstone", "control", "performance"),
                Duration.ofSeconds(45)),
            context -> new RedstoneVanillaControlServerScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.energy-mekanism-fanout", "Super Lead Mekanism Energy Fanout",
                Set.of("super_lead", "server", "energy", "mekanism", "fanout", "performance"),
                Duration.ofSeconds(45)),
            context -> new MekanismEnergyFanoutServerScenario());
            registrar.register(
                new ScenarioDescriptor("super_lead.paired-server-cadence", "Super Lead Paired Server Cadence Rig",
                    Set.of("super_lead", "paired", "server", "cadence"), Duration.ofSeconds(30)),
                context -> new ServerCadenceRigScenario());
    }

    @Override
    public void registerClient(BenchClientRegistrar registrar) {
        registrar.register(
            new ScenarioDescriptor("super_lead.paired-remote-cadence", "Super Lead Paired Remote Cadence",
                Set.of("super_lead", "paired", "remote", "cadence"), Duration.ofSeconds(90)),
            context -> new RemoteCadenceClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.remote-client-smoke", "Super Lead Remote Client Smoke",
                Set.of("super_lead", "client", "remote", "smoke"), Duration.ofSeconds(30)),
            context -> new RemoteClientSmokeScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-animation-cadence", "Super Lead Rope Animation Cadence",
                Set.of("super_lead", "client", "render", "cadence", "dense"), Duration.ofSeconds(90)),
            context -> new RopeAnimationCadenceClientScenario());
        registrar.register(
                new ScenarioDescriptor("super_lead.rope-air-rest", "Super Lead Rope Air Rest",
                        Set.of("super_lead", "client", "physics"), Duration.ofSeconds(60)),
                context -> new RopeAirRestClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-long-span", "Super Lead Rope Long Span",
                Set.of("super_lead", "client", "physics", "extended"), Duration.ofSeconds(75)),
            context -> new RopeLongSpanClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-kind-matrix", "Super Lead Rope Kind Matrix",
                Set.of("super_lead", "client", "physics", "render"), Duration.ofSeconds(75)),
            context -> new RopeKindMatrixClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-attachments", "Super Lead Rope Attachments",
                Set.of("super_lead", "client", "physics", "render", "attachment"),
                Duration.ofSeconds(75)),
            context -> new RopeAttachmentClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-item-work", "Super Lead Rope Item Work",
                Set.of("super_lead", "client", "work", "item", "interaction"), Duration.ofSeconds(60)),
            context -> new RopeItemWorkClientScenario());
        registrar.register(
                new ScenarioDescriptor("super_lead.rope-stack-contact", "Super Lead Rope Stack Contact",
                        Set.of("super_lead", "client", "physics"), Duration.ofSeconds(60)),
                context -> new RopeStackContactClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-stack-order", "Super Lead Rope Stack Order",
                Set.of("super_lead", "client", "physics", "regression"), Duration.ofSeconds(75)),
            context -> new RopeStackOrderClientScenario());
        registrar.register(
                new ScenarioDescriptor("super_lead.rope-player-collision", "Super Lead Rope Player Collision",
                        Set.of("super_lead", "client", "physics", "interaction"), Duration.ofSeconds(90)),
                context -> new RopePlayerCollisionClientScenario());
        registrar.register(
                new ScenarioDescriptor("super_lead.rope-slack-adjust", "Super Lead Rope Slack Adjust",
                        Set.of("super_lead", "client", "physics", "interaction"), Duration.ofSeconds(90)),
                context -> new RopeSlackAdjustClientScenario());
        registrar.register(
                new ScenarioDescriptor("super_lead.rope-multi-layer", "Super Lead Rope Multi Layer",
                        Set.of("super_lead", "client", "physics"), Duration.ofSeconds(90)),
                context -> new RopeMultiLayerStackClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-supported-mesh-matrix",
                "Super Lead Rope Supported Mesh Matrix",
                Set.of("super_lead", "client", "physics", "terrain", "chunk_mesh"),
                Duration.ofSeconds(90)),
            context -> new RopeSupportedMeshMatrixClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-lod-mesh-handoff",
                "Super Lead Rope LOD Mesh Handoff",
                Set.of("super_lead", "client", "render", "lod", "chunk_mesh"),
                Duration.ofSeconds(120)),
            context -> new RopeLodMeshHandoffClientScenario());
        registrar.register(
            new ScenarioDescriptor("super_lead.rope-shared-section-isolation",
                "Super Lead Rope Shared Section Isolation",
                Set.of("super_lead", "client", "render", "chunk_mesh", "regression"),
                Duration.ofSeconds(90)),
            context -> new RopeSharedSectionIsolationClientScenario());
    }

    private static final class ServerLoadScenario implements BenchServerScenario {
        private int ticks;

        @Override
        public void setup(BenchServerContext context) {
            if (!ModList.get().isLoaded("super_lead")) {
                throw new IllegalStateException("Super Lead is not loaded in the BenchMod server run");
            }
            if (!context.server().isRunning() || context.level() != context.server().overworld()) {
                throw new IllegalStateException("Dedicated server context is not ready");
            }
        }

        @Override
        public BenchStepResult measure(BenchServerContext context) {
            ticks++;
            return ticks >= 20 ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
        }

        @Override
        public void verify(BenchServerContext context) {
            if (ticks != 20) {
                throw new AssertionError("Expected 20 measured server ticks, got " + ticks);
            }
        }
    }
}