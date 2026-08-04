package me.alecjensen.bedwarsresourcehud.mixin.client;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SoundEngine#calculateVolume(float, SoundSource) hard-clamps the instance's own volume to [0, 1]
 * before any category/master scaling even applies - a channel-level gain override (an earlier
 * attempt) gets silently reset back to this because the engine periodically recomputes volume
 * through this exact method for already-playing channels too, not just at the initial play() call.
 * Patching the clamp's own ceiling here, instead of fighting the recompute after the fact, is what
 * real published "louder sounds" mods (e.g. BVengo/sound-controller, offering per-sound sliders up
 * to 200%) actually do - and it means every future recompute already agrees with us instead of
 * needing to be reapplied. Only the upper bound changes; whatever the engine's real minimum was
 * (always 0 in practice) is left alone.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineVolumeMixin
{
    private static final float MAX_INSTANCE_VOLUME = 20.0f;

    @Redirect(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"))
    private float bedwarsresourcehud$widenVolumeClamp(float value, float min, float max)
    {
        return Mth.clamp(value, min, MAX_INSTANCE_VOLUME);
    }
}
