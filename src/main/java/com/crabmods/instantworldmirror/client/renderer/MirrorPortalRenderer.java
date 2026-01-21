package com.crabmods.instantworldmirror.client.renderer;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Mirror Portal Entity Renderer
 * Renders as a placed mirror item model
 */
public class MirrorPortalRenderer extends EntityRenderer<MirrorPortalEntity> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            InstantWorldMirror.MODID, "textures/item/dimension_mirror.png"
    );

    public MirrorPortalRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(MirrorPortalEntity entity, float entityYaw, float partialTicks, 
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        // Calculate floating animation
        float time = entity.tickCount + partialTicks;
        float bob = (float) Math.sin(time * 0.1) * 0.1F;

        // Position adjustment - raise a bit
        poseStack.translate(0, 1.0 + bob, 0);

        // Check if in loading state
        boolean isLoading = entity.isLoading();
        
        if (isLoading) {
            // Loading: fast spin animation
            float spinAngle = time * 10.0F; // Fast rotation
            poseStack.mulPose(Axis.YP.rotationDegrees(spinAngle));
            
            // Pulsing scale effect during loading
            float pulse = 1.0F + (float) Math.sin(time * 0.3) * 0.15F;
            poseStack.scale(pulse, pulse, pulse);
        } else {
            // Loading complete: face player
            poseStack.mulPose(Axis.YP.rotationDegrees(-this.entityRenderDispatcher.camera.getYRot()));
        }

        // Scale up mirror model
        float scale = 2.5F;
        poseStack.scale(scale, scale, scale);

        // Blink effect (when about to disappear)
        int lifetime = entity.getLifetime();
        if (lifetime < 40 && !isLoading) { // Last 2 seconds blink (non-loading state)
            if (lifetime % 10 >= 5) {
                poseStack.scale(0.9F, 0.9F, 0.9F);
            }
        }

        // Render mirror item with full brightness - ensure visibility
        int fullBright = LightTexture.FULL_BRIGHT;
        
        // Render mirror item
        ItemStack mirrorStack = new ItemStack(ModItems.DIMENSION_MIRROR.get());
        Minecraft.getInstance().getItemRenderer().renderStatic(
                mirrorStack,
                ItemDisplayContext.FIXED,
                fullBright,  // Use full brightness
                OverlayTexture.NO_OVERLAY,
                poseStack,
                buffer,
                entity.level(),
                entity.getId()
        );

        poseStack.popPose();

        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(MirrorPortalEntity entity) {
        return TEXTURE;
    }
}
