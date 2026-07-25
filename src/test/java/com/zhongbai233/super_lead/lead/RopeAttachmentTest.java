package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.handler.codec.DecoderException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RopeAttachmentTest {
    @Test
    void modelStateEntryCountHasAProtocolBound() {
        assertDoesNotThrow(() -> RopeAttachment.validateModelStateCount(
                RopeAttachment.MAX_MODEL_STATE_ENTRIES));
        assertThrows(DecoderException.class,
                () -> RopeAttachment.validateModelStateCount(RopeAttachment.MAX_MODEL_STATE_ENTRIES + 1));
        assertThrows(DecoderException.class, () -> RopeAttachment.validateModelStateCount(-1));
    }

    @Test
    void modelStateTotalCharactersHaveAProtocolBound() {
        assertDoesNotThrow(() -> RopeAttachment.validateModelStateTotalChars(
                RopeAttachment.MAX_MODEL_STATE_TOTAL_CHARS));
        assertThrows(DecoderException.class,
                () -> RopeAttachment.validateModelStateTotalChars(
                        RopeAttachment.MAX_MODEL_STATE_TOTAL_CHARS + 1));
    }

        @Test
        void modelStateNormalizationEnforcesPersistentAndWireBounds() {
                Map<String, String> oversized = new LinkedHashMap<>();
                for (int i = 0; i < RopeAttachment.MAX_MODEL_STATE_ENTRIES + 10; i++) {
                        oversized.put("property_" + i + "_" + "x".repeat(80), "value_" + "y".repeat(80));
                }

                Map<String, String> normalized = RopeAttachment.normalizeModelStateOverride(oversized);
                int totalChars = normalized.entrySet().stream()
                                .mapToInt(entry -> entry.getKey().length() + entry.getValue().length())
                                .sum();

                assertTrue(normalized.size() <= RopeAttachment.MAX_MODEL_STATE_ENTRIES);
                assertTrue(totalChars <= RopeAttachment.MAX_MODEL_STATE_TOTAL_CHARS);
                assertTrue(normalized.keySet().stream()
                                .allMatch(key -> key.length() <= RopeAttachment.MAX_MODEL_STATE_COMPONENT_CHARS));
                assertTrue(normalized.values().stream()
                                .allMatch(value -> value.length() <= RopeAttachment.MAX_MODEL_STATE_COMPONENT_CHARS));
                assertEquals(normalized, RopeAttachment.normalizeModelStateOverride(normalized));
        }
}