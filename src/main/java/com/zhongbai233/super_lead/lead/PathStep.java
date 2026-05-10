package com.zhongbai233.super_lead.lead;

final class PathStep {
    final LeadConnection rope;
    final boolean reverse;

    PathStep(LeadConnection rope, boolean reverse) {
        this.rope = rope;
        this.reverse = reverse;
    }
}
