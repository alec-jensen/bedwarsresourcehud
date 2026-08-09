package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Map;

/**
 * A real world-space box outline around every bed discovered anywhere on the map (see
 * AllBedsTracker), drawn via the vanilla Gizmos API and marked always-on-top so it still renders
 * through walls and terrain unconditionally, the same as the old screen-space dot did - this is
 * meant to be unconditional ESP, not "visible unless something's physically in the way". A small
 * floating label (whose bed, and how far away) is drawn separately in screen space since Gizmos
 * text placement isn't as controllable as a plain HUD element.
 */
public final class BedEspRenderer
{
    private static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("bedwarsresourcehud", "bed_esp");
    private static final float OUTLINE_WIDTH = 2.0f;
    private static final int LABEL_Y_OFFSET = 14;

    private BedEspRenderer()
    {
    }

    public static void register()
    {
        LevelRenderEvents.BEFORE_GIZMOS.register(context ->
        {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.level == null || !HudConfig.get().showBedEsp)
            {
                return;
            }
            renderOutlines(client);
        });

        HudElementRegistry.addLast(ELEMENT_ID, (graphics, tickCounter) ->
        {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.level == null || !HudConfig.get().showBedEsp)
            {
                return;
            }
            renderLabels(client, graphics);
        });
    }

    private static void renderOutlines(Minecraft client)
    {
        for (Map.Entry<BlockPos, DyeColor> entry : AllBedsTracker.getBeds().entrySet())
        {
            AABB aabb = bedAabb(client, entry.getKey());
            if (aabb == null)
            {
                continue;
            }
            int color = 0xFF000000 | entry.getValue().getTextureDiffuseColor();
            Gizmos.cuboid(aabb, GizmoStyle.stroke(color, OUTLINE_WIDTH)).setAlwaysOnTop();
        }
    }

    private static void renderLabels(Minecraft client, GuiGraphicsExtractor graphics)
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
            BlockPos footPos = entry.getKey();
            Vec3 target = Vec3.atCenterOf(footPos);
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
            boolean isOwnBed = footPos.equals(ownBed);
            int distance = (int) Math.round(Math.sqrt(distanceSq));
            String label = (isOwnBed ? "Your Bed" : capitalize(dyeColor.getSerializedName()) + " Bed") + " (" + distance + "m)";
            int textWidth = font.width(label);
            graphics.text(font, Component.literal(label), screenX - textWidth / 2, screenY - LABEL_Y_OFFSET - font.lineHeight, color, true);
        }
    }

    /** The full two-block AABB spanning both halves of the bed at footPos, or null if it's no longer a valid bed. */
    private static AABB bedAabb(Minecraft client, BlockPos footPos)
    {
        BlockState state = client.level.getBlockState(footPos);
        if (!(state.getBlock() instanceof BedBlock))
        {
            return null;
        }
        Direction toOtherHalf = BedBlock.getConnectedDirection(state);
        BlockPos headPos = footPos.relative(toOtherHalf);
        return AABB.encapsulatingFullBlocks(footPos, headPos);
    }

    private static String capitalize(String s)
    {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ');
    }
}
