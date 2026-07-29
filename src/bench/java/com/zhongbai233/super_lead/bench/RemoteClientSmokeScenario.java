package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.fml.ModList;

/** Minimal remote-client smoke that does not require an integrated server. */
final class RemoteClientSmokeScenario implements BenchClientScenario {
    private static final int MEASURE_TICKS = 100;
    private static final BenchMetricDescriptor MEASURED_TICKS = new BenchMetricDescriptor(
            "super_lead.remote_client.measured_ticks", "ticks", MetricDirection.HIGHER_IS_BETTER);
    private final AtomicInteger ticks = new AtomicInteger();

    @Override
    public void setup(BenchClientContext context) {
        if (!ModList.get().isLoaded("super_lead")) {
            throw new IllegalStateException("Super Lead is not loaded in the remote client");
        }
        if (context.minecraft().getSingleplayerServer() != null) {
            throw new IllegalStateException("remote-client smoke unexpectedly has an integrated server");
        }
        context.automation().stopMovement();
        context.automation().setHudHidden(true);
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready()
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        context.automation().stopMovement();
        int current = ticks.incrementAndGet();
        return current >= MEASURE_TICKS
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (ticks.get() != MEASURE_TICKS) {
            throw new AssertionError("remote client smoke measured " + ticks.get() + " ticks");
        }
        context.metrics().record(MEASURED_TICKS, ticks.get());
    }
}
