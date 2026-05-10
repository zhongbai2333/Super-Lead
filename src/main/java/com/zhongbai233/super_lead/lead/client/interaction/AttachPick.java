package com.zhongbai233.super_lead.lead.client.interaction;

import java.util.UUID;

public final class AttachPick {
    public final UUID connectionId;
    public final double t;

    public AttachPick(UUID connectionId, double t) {
        this.connectionId = connectionId;
        this.t = t;
    }
}
