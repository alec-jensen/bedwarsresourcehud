package me.alecjensen.bedwarsresourcehud.mixin.client;

import me.alecjensen.bedwarsresourcehud.client.StealthHighlighter;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * This is the actual per-frame, client-only check the renderer uses to decide whether to draw an
 * entity's glow outline - it's also how the spectator-mode "hold key to outline players" feature
 * works, which is why it's a clean hook: unlike Entity#setGlowingTag (backed by networked entity
 * data the server keeps overwriting), this never touches anything synced.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin
{
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void bedwarsresourcehud$forceGlow(Entity entity, CallbackInfoReturnable<Boolean> cir)
    {
        if (StealthHighlighter.shouldGlow(entity))
        {
            cir.setReturnValue(true);
        }
    }
}
