package com.zhongbai233.super_lead.lead.client.interaction;

import java.util.UUID;

public final class AttachmentPick {
    public final UUID connectionId;
    public final UUID attachmentId;

    public AttachmentPick(UUID connectionId, UUID attachmentId) {
        this.connectionId = connectionId;
        this.attachmentId = attachmentId;
    }
}
