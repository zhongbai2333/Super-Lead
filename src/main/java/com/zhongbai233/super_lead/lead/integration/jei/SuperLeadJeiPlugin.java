package com.zhongbai233.super_lead.lead.integration.jei;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.lead.client.cargo.CargoManifestScreen;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

@JeiPlugin
public final class SuperLeadJeiPlugin implements IModPlugin {
    private static final Identifier UID = Identifier.fromNamespaceAndPath(Super_lead.MODID, "jei");

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(CargoManifestScreen.class, new CargoManifestGhostIngredientHandler());
    }

    private static final class CargoManifestGhostIngredientHandler
            implements IGhostIngredientHandler<CargoManifestScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(CargoManifestScreen gui, ITypedIngredient<I> ingredient,
                boolean doStart) {
            ItemStack stack = ingredient.getItemStack().orElse(ItemStack.EMPTY).copy();
            List<CargoManifestScreen.JeiDropTarget> dropTargets = gui.jeiDropTargets(stack);
            if (dropTargets.isEmpty()) {
                return List.of();
            }
            List<Target<I>> targets = new ArrayList<>(dropTargets.size());
            for (CargoManifestScreen.JeiDropTarget dropTarget : dropTargets) {
                targets.add(new CargoManifestTarget<>(gui, dropTarget, stack));
            }
            return targets;
        }

        @Override
        public <I> boolean quickMove(CargoManifestScreen gui, ITypedIngredient<I> ingredient) {
            ItemStack stack = ingredient.getItemStack().orElse(ItemStack.EMPTY).copy();
            return gui.quickMoveJeiIngredient(stack);
        }

        @Override
        public void onComplete() {
        }
    }

    private record CargoManifestTarget<I>(CargoManifestScreen screen, CargoManifestScreen.JeiDropTarget target,
            ItemStack stack) implements IGhostIngredientHandler.Target<I> {
        @Override
        public Rect2i getArea() {
            return target.area();
        }

        @Override
        public void accept(I ingredient) {
            screen.acceptJeiIngredient(target.slotId(), stack);
        }
    }
}