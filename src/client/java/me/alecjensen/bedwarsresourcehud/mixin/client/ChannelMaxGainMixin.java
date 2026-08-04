package me.alecjensen.bedwarsresourcehud.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OpenAL's own AL_MAX_GAIN source property defaults to 1.0, and per the base 1.1 spec, any value
 * written to it gets silently clamped right back into [0, 1] too - a completely separate clamp
 * from anything in Minecraft's own Java code (SoundEngineVolumeMixin widens Minecraft's clamp, but
 * that only controls what volume Minecraft asks OpenAL for; OpenAL then clamps that request AGAIN
 * on its own, invisibly, at the driver level). The AL_SOFT_gain_clamp_ex extension - which OpenAL
 * Soft (what LWJGL/Minecraft ships) implements - is what allows AL_MAX_GAIN itself to actually be
 * raised above 1.0; without explicitly setting it, every source is silently stuck at the default
 * ceiling no matter what AL_GAIN is set to.
 */
@Mixin(Channel.class)
public abstract class ChannelMaxGainMixin
{
    private static final float MAX_GAIN = 20.0f;

    @Shadow
    @Final
    private int source;

    @Inject(method = "setVolume", at = @At("HEAD"))
    private void bedwarsresourcehud$raiseMaxGain(float volume, CallbackInfo ci)
    {
        AL10.alSourcef(this.source, AL10.AL_MAX_GAIN, MAX_GAIN);
    }
}
