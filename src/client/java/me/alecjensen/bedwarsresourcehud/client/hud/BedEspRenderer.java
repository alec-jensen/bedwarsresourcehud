package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Map;

/**
 * A dot and label over every bed discovered anywhere on the map (see AllBedsTracker), always
 * visible - straight screen-space projection like the generator waypoints, so it renders through
 * walls and terrain unconditionally. Unlike the generator waypoints, this deliberately does NOT
 * check for player occlusion either - this is meant to be unconditional ESP, not "visible unless
 * someone's physically standing in the way".
 */
public final class BedEspRenderer
{
    private static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("bedwarsresourcehud", "bed_esp");
    private static final int DOT_RADIUS = 3;
    private static final int LABEL_Y_OFFSET = 14;

    private BedEspRenderer()
    {
    }

    public static void register()
    {
        HudElementRegistry.addLast(ELEMENT_ID, (graphics, tickCounter) ->
        {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.level == null || !HudConfig.get().showBedEsp)
            {
                return;
            }
            render(client, graphics);
        });
    }

    private static void render(Minecraft client, GuiGraphicsExtractor graphics)
    {
        Camera camera = client.gameRenderer.mainCamera();
        if (!camera.isInitialized())
        {
            return;
        }

        Map<BlockPos, DyeColor> beds = AllBedsTracker.getBeds();
        if (beds.isEmpty())
        {
            return;
        }

        BlockPos ownBed = BedAlarmTracker.getBedPos();
        Vec3 cameraPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        Font font = client.font;

        for (Map.Entry<BlockPos, DyeColor> entry : beds.entrySet())
        {
            BlockPos pos = entry.getKey();
            Vec3 target = Vec3.atCenterOf(pos);
            double relX = target.x - cameraPos.x;
            double relY = target.y - cameraPos.y;
            double relZ = target.z - cameraPos.z;
            double distanceSq = relX * relX + relY * relY + relZ * relZ;
            if (distanceSq < 0.001)
            {
                continue;
            }

            Vector4f clip = new Vector4f((float) relX, (float) relY, (float) relZ, 1.0f);
            viewProjection.transform(clip);
            if (clip.w <= 0.0f)
            {
                continue;
            }

            int screenX = Math.round((clip.x / clip.w + 1f) / 2f * screenWidth);
            int screenY = Math.round((1f - clip.y / clip.w) / 2f * screenHeight);
            if (screenX < 0 || screenX > screenWidth || screenY < 0 || screenY > screenHeight)
            {
                continue;
            }

            DyeColor dyeColor = entry.getValue();
            int color = 0xFF000000 | dyeColor.getTextureDiffuseColor();
            graphics.fill(screenX - DOT_RADIUS, screenY - DOT_RADIUS, screenX + DOT_RADIUS, screenY + DOT_RADIUS, color);

            boolean isOwnBed = pos.equals(ownBed);
            int distance = (int) Math.round(Math.sqrt(distanceSq));
            String label = (isOwnBed ? "Your Bed" : capitalize(dyeColor.getSerializedName()) + " Bed") + " (" + distance + "m)";
            int textWidth = font.width(label);
            graphics.text(font, Component.literal(label), screenX - textWidth / 2, screenY - LABEL_Y_OFFSET - font.lineHeight, color, true);
        }
    }

    private static String capitalize(String s)
    {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ');
    }
}
