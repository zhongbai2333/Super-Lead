package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.RopeAttachment;
import com.zhongbai233.super_lead.lead.UpdateRopeAttachmentSignText;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.HangingSignEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.HangingSignBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

public final class RopeAttachmentSignEditor {
    private RopeAttachmentSignEditor() {
    }

    public static boolean open(LeadConnection connection, RopeAttachment attachment) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (mc.player == null || level == null) {
            return false;
        }
        ItemStack stack = attachment.stack();
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof SignBlock)) {
            return false;
        }

        BlockState state = orientForEditor(blockItem.getBlock().defaultBlockState(), attachment.frontSide());
        boolean hanging = stack.getItem() instanceof net.minecraft.world.item.HangingSignItem
                || blockItem.getBlock() instanceof HangingSignBlock;
        LoadedSignText text = loadText(stack, level, state, hanging);
        BlockPos editorPos = mc.player.blockPosition();
        // Player on front side edits front text; on back side edits back text.
        boolean frontText = isViewerOnFrontSide(connection, attachment, mc.player);
        boolean filtered = mc.player.isTextFilteringEnabled();

        if (hanging) {
            mc.setScreen(new HangingScreen(connection.id(), attachment.id(),
                    new FakeHangingSignBlockEntity(editorPos, state, text.front(), text.back()),
                    frontText, filtered));
        } else {
            mc.setScreen(new StandingScreen(connection.id(), attachment.id(),
                    new FakeSignBlockEntity(editorPos, state, text.front(), text.back()),
                    frontText, filtered));
        }
        return true;
    }

    private static LoadedSignText loadText(ItemStack stack, ClientLevel level, BlockState state, boolean hanging) {
        SignBlockEntity sign = hanging
                ? new HangingSignBlockEntity(BlockPos.ZERO, state)
                : new SignBlockEntity(BlockPos.ZERO, state);
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data != null && data.type() == sign.getType()) {
            data.loadInto(sign, level.registryAccess());
        }
        sign.applyComponentsFromItemStack(stack);
        return new LoadedSignText(sign.getFrontText(), sign.getBackText());
    }

    private static BlockState orientForEditor(BlockState state, int frontSide) {
        net.minecraft.core.Direction front = frontSide >= 0
                ? net.minecraft.core.Direction.SOUTH
                : net.minecraft.core.Direction.NORTH;
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, front);
        } else if (state
                .hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, front);
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16)) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16,
                    frontSide >= 0 ? 8 : 0);
        }
        return state;
    }

    public static boolean isViewerOnFrontSideStatic(LeadConnection connection, RopeAttachment attachment,
            net.minecraft.client.player.LocalPlayer player) {
        return isViewerOnFrontSide(connection, attachment, player);
    }

    private static boolean isViewerOnFrontSide(LeadConnection connection, RopeAttachment attachment,
            net.minecraft.client.player.LocalPlayer player) {
        var level = player.level();
        net.minecraft.world.phys.Vec3 from = connection.from().attachmentPoint(level);
        net.minecraft.world.phys.Vec3 to = connection.to().attachmentPoint(level);
        net.minecraft.world.phys.Vec3 signPos = from.lerp(to, attachment.t());
        net.minecraft.world.phys.Vec3 viewerPos = player.getEyePosition(1.0F);
        // frontSide determines which direction the "front" face points.
        // frontSide >=0 → side direction; otherwise use a simple chord-based facing.
        net.minecraft.world.phys.Vec3 tangent = to.subtract(from).normalize();
        net.minecraft.world.phys.Vec3 up = new net.minecraft.world.phys.Vec3(0, 1, 0)
                .subtract(tangent.scale(tangent.y));
        if (up.lengthSqr() < 1.0e-6D)
            up = new net.minecraft.world.phys.Vec3(0, 1, 0);
        up = up.normalize();
        net.minecraft.world.phys.Vec3 side = tangent.cross(up);
        double frontDir = attachment.frontSide() >= 0 ? 1.0D : -1.0D;
        double dot = viewerPos.subtract(signPos).dot(side.scale(frontDir));
        return dot < 0.0D;
    }

    private static void sendText(UUID connectionId, UUID attachmentId, SignBlockEntity sign, boolean frontText) {
        SignText text = sign.getText(frontText);
        String[] lines = new String[4];
        boolean filtered = Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.isTextFilteringEnabled();
        for (int i = 0; i < lines.length; i++) {
            lines[i] = text.getMessage(i, filtered).getString();
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new UpdateRopeAttachmentSignText(connectionId, attachmentId, frontText,
                        lines[0], lines[1], lines[2], lines[3]));
        Minecraft.getInstance().textInputManager().stopTextInput();
    }

    private record LoadedSignText(SignText front, SignText back) {
    }

    private static final class StandingScreen extends SignEditScreen {
        private final UUID connectionId;
        private final UUID attachmentId;
        private final boolean frontText;

        StandingScreen(UUID connectionId, UUID attachmentId, SignBlockEntity sign,
                boolean frontText, boolean filtered) {
            super(sign, frontText, filtered);
            this.connectionId = connectionId;
            this.attachmentId = attachmentId;
            this.frontText = frontText;
        }

        @Override
        public void removed() {
            sendText(connectionId, attachmentId, this.sign, frontText);
        }
    }

    private static final class HangingScreen extends HangingSignEditScreen {
        private final UUID connectionId;
        private final UUID attachmentId;
        private final boolean frontText;

        HangingScreen(UUID connectionId, UUID attachmentId, SignBlockEntity sign,
                boolean frontText, boolean filtered) {
            super(sign, frontText, filtered);
            this.connectionId = connectionId;
            this.attachmentId = attachmentId;
            this.frontText = frontText;
        }

        @Override
        public void removed() {
            sendText(connectionId, attachmentId, this.sign, frontText);
        }
    }

    private static class FakeSignBlockEntity extends SignBlockEntity {
        private SignText front;
        private SignText back;

        FakeSignBlockEntity(BlockPos pos, BlockState state, SignText front, SignText back) {
            super(pos, state);
            this.front = front == null ? new SignText() : front;
            this.back = back == null ? new SignText() : back;
        }

        @Override
        public SignText getText(boolean frontText) {
            return frontText ? front : back;
        }

        @Override
        public SignText getFrontText() {
            return front;
        }

        @Override
        public SignText getBackText() {
            return back;
        }

        @Override
        public boolean setText(SignText text, boolean frontText) {
            if (frontText) {
                front = text == null ? new SignText() : text;
            } else {
                back = text == null ? new SignText() : text;
            }
            return true;
        }

        @Override
        public boolean playerIsTooFarAwayToEdit(UUID playerId) {
            return false;
        }
    }

    private static final class FakeHangingSignBlockEntity extends HangingSignBlockEntity {
        private SignText front;
        private SignText back;

        FakeHangingSignBlockEntity(BlockPos pos, BlockState state, SignText front, SignText back) {
            super(pos, state);
            this.front = front == null ? new SignText() : front;
            this.back = back == null ? new SignText() : back;
        }

        @Override
        public SignText getText(boolean frontText) {
            return frontText ? front : back;
        }

        @Override
        public SignText getFrontText() {
            return front;
        }

        @Override
        public SignText getBackText() {
            return back;
        }

        @Override
        public boolean setText(SignText text, boolean frontText) {
            if (frontText) {
                front = text == null ? new SignText() : text;
            } else {
                back = text == null ? new SignText() : text;
            }
            return true;
        }

        @Override
        public boolean playerIsTooFarAwayToEdit(UUID playerId) {
            return false;
        }
    }
}