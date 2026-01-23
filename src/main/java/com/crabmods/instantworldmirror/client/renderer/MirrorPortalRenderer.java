package com.crabmods.instantworldmirror.client.renderer;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.model.MirrorPortalModel;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Mirror Portal Entity Renderer
 * Renders using custom Blockbench model
 */
public class MirrorPortalRenderer extends EntityRenderer<MirrorPortalEntity> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            InstantWorldMirror.MODID, "textures/entity/mirror_portal.png"
    );
    
    private final MirrorPortalModel model;

    public MirrorPortalRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new MirrorPortalModel(context.bakeLayer(MirrorPortalModel.LAYER_LOCATION));
    }

    @Override
    public void render(MirrorPortalEntity entity, float entityYaw, float partialTicks, 
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        // Calculate floating animation
        float time = entity.tickCount + partialTicks;
        float bob = (float) Math.sin(time * 0.1) * 0.1F;

        // Position adjustment - place mirror at eye level with floating effect
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
        
        // Scale the mirror model
        float scale = 1.5F;
        poseStack.scale(scale, scale, scale);

        // Blink effect (when about to disappear)
        int lifetime = entity.getLifetime();
        float alpha = 1.0F;
        if (lifetime > 0 && lifetime < 40 && !isLoading) { // Last 2 seconds blink (non-loading state)
            alpha = (lifetime % 10 >= 5) ? 0.5F : 1.0F;
        }

        // Use full brightness for magical effect
        int fullBright = LightTexture.FULL_BRIGHT;
        
        // Get render type and render model
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityTranslucent(TEXTURE));
        
        // Pack color with alpha
        int color = ((int)(alpha * 255) << 24) | 0xFFFFFF;
        
        model.renderToBuffer(poseStack, vertexConsumer, fullBright, OverlayTexture.NO_OVERLAY, color);

        poseStack.popPose();

        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(MirrorPortalEntity entity) {
        return TEXTURE;
    }
}
