package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum AlertSound
{
    EXPERIENCE_ORB(SoundEvents.EXPERIENCE_ORB_PICKUP, "Experience Orb"),
    LEVEL_UP(SoundEvents.PLAYER_LEVELUP, "Level Up"),
    ITEM_PICKUP(SoundEvents.ITEM_PICKUP, "Item Pickup"),
    BELL(SoundEvents.BELL_BLOCK, "Bell"),
    AMETHYST_CHIME(SoundEvents.AMETHYST_BLOCK_CHIME, "Amethyst Chime"),
    CHIME(SoundEvents.ENCHANTMENT_TABLE_USE, "Chime"),
    ANVIL_LAND(SoundEvents.ANVIL_LAND, "Anvil Land"),
    TOTEM(SoundEvents.TOTEM_USE, "Totem"),
    VILLAGER_YES(SoundEvents.VILLAGER_YES, "Villager Yes"),
    FIREWORK_LAUNCH(SoundEvents.FIREWORK_ROCKET_LAUNCH, "Firework Launch"),
    BEACON_ACTIVATE(SoundEvents.BEACON_ACTIVATE, "Beacon Activate"),
    VAULT_ACTIVATE(SoundEvents.VAULT_ACTIVATE, "Vault Activate"),
    RAVAGER_ROAR(SoundEvents.RAVAGER_ROAR, "Ravager Roar"),
    LIGHTNING_THUNDER(SoundEvents.LIGHTNING_BOLT_THUNDER, "Thunder"),
    WARDEN_SONIC_BOOM(SoundEvents.WARDEN_SONIC_BOOM, "Warden Sonic Boom"),
    WARDEN_ROAR(SoundEvents.WARDEN_ROAR, "Warden Roar"),
    SCULK_SHRIEK(SoundEvents.SCULK_SHRIEKER_SHRIEK, "Sculk Shriek"),
    EVOKER_CAST_SPELL(SoundEvents.EVOKER_CAST_SPELL, "Evoker Cast"),
    ENDER_DRAGON_GROWL(SoundEvents.ENDER_DRAGON_GROWL, "Dragon Growl"),
    WITHER_SPAWN(SoundEvents.WITHER_SPAWN, "Wither Spawn"),
    // The 8 goat horn instrument sounds, in the vanilla Instruments declaration order - Seek and
    // Call are the two that actually sound like an alarm/horn blast, the rest are more musical/tonal.
    GOAT_HORN_PONDER(SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0).value(), "Goat Horn: Ponder"),
    GOAT_HORN_SING(SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(1).value(), "Goat Horn: Sing"),
    GOAT_HORN_SEEK(SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2).value(), "Goat Horn: Seek"),
    GOAT_HORN_FEEL(SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(3).value(), "Goat Horn: Feel"),
    GOAT_HORN_ADMIRE(SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(4).value(), "Goat Horn: Admire"),
    GOAT_HORN_CALL(SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(5).value(), "Goat Horn: Call"),
    GOAT_HORN_YEARN(SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(6).value(), "Goat Horn: Yearn"),
    GOAT_HORN_DREAM(SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(7).value(), "Goat Horn: Dream"),
    EXPLOSION(SoundEvents.GENERIC_EXPLODE.value(), "Explosion");

    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/AlertSound");
    private static boolean loggedOptions;

    private final SoundEvent sound;
    private final String label;

    AlertSound(SoundEvent sound, String label)
    {
        this.sound = sound;
        this.label = label;
    }

    public SoundEvent getSound()
    {
        return sound;
    }

    public String getLabel()
    {
        return label;
    }

    /**
     * Plays this sound at the given volume, genuinely - not just up to 1.0. Vanilla's own
     * SoundEngine#calculateVolume hard-clamps to [0, 1] before category/master scaling even
     * applies; SoundEngineVolumeMixin widens that ceiling (to 20.0) directly at its source, and
     * ChannelMaxGainMixin does the same for OpenAL's own separate AL_MAX_GAIN clamp underneath
     * that - so the volume passed in here just works normally all the way through, no need to
     * fight the engine after the fact.
     */
    public SimpleSoundInstance play(float volume)
    {
        if (!loggedOptions)
        {
            // If the player's own in-game UI or Master category slider is turned down, that
            // multiplies on top of whatever we send and would make every alert quiet/silent no
            // matter what this mod's own volume controls are set to - ruling that in or out.
            var options = Minecraft.getInstance().options;
            LOGGER.info("Sound options: UI category={} master category={}",
                    options.getSoundSourceVolume(SoundSource.UI), options.getSoundSourceVolume(SoundSource.MASTER));
            loggedOptions = true;
        }

        if (volume <= 0.0f)
        {
            return null;
        }

        SimpleSoundInstance instance = SimpleSoundInstance.forUI(sound, 1.0f, volume);
        Minecraft.getInstance().getSoundManager().play(instance);
        return instance;
    }

    public static AlertSound fromName(String name)
    {
        for (AlertSound sound : values())
        {
            if (sound.name().equals(name))
            {
                return sound;
            }
        }
        return EXPERIENCE_ORB;
    }
}
