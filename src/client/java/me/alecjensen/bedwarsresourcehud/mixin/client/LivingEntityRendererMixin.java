package me.alecjensen.bedwarsresourcehud.mixin.client;

import me.alecjensen.bedwarsresourcehud.client.StealthHighlighter;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * shouldShowName is the single choke point that decides whether a player's nametag renders at
 * all - it's what normally hides an invisible player's name (and a sneaking player's name past
 * ~32 blocks). There's no Fabric API event for this, so bypassing it for a highlighted target
 * requires hooking the method directly.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin
{
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void bedwarsresourcehud$forceShowName(LivingEntity entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir)
    {
        if (StealthHighlighter.shouldForceNameTag(entity))
        {
            cir.setReturnValue(true);
        }
    }
}
