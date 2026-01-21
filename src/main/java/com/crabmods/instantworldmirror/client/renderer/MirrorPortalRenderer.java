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
import net.minecraft.util.Mth;

/**
 * Mirror Portal Entity Renderer
 * Renders using custom Blockbench model
 */
public class MirrorPortalRenderer extends EntityRenderer<MirrorPortalEntity> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            InstantWorldMirror.MODID, "textures/entity/mirror_portal.png"
    );
    
    // Beacon beam texture from vanilla
    private static final ResourceLocation BEAM_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/beacon_beam.png");
    
    private final MirrorPortalModel model;

    public MirrorPortalRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new MirrorPortalModel(context.bakeLayer(MirrorPortalModel.LAYER_LOCATION));
    }

    @Override
    public void render(MirrorPortalEntity entity, float entityYaw, float partialTicks, 
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        
        // Render beacon beam for return portal (in mirror world)
        if (entity.isReturnPortal() && !entity.isLoading()) {
            renderBeaconBeam(entity, partialTicks, poseStack, buffer);
        }
        
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
    
    /**
     * Render beacon-like beam effect for return portal
     */
    private void renderBeaconBeam(MirrorPortalEntity entity, float partialTicks, 
                                   PoseStack poseStack, MultiBufferSource buffer) {
        float time = entity.tickCount + partialTicks;
        float beamHeight = 256.0F; // Height of the beam
        
        poseStack.pushPose();
        
        // Position beam at entity location
        poseStack.translate(0, 0.5, 0);
        
        // Render the beam using vanilla-style rendering
        renderBeamSegment(poseStack, buffer, BEAM_TEXTURE, partialTicks, 1.0F, time, 
                0, (int) beamHeight, 
                new float[]{0.5F, 0.8F, 1.0F}, // Light blue color (cyan-ish)
                0.2F, 0.25F); // Inner and outer radius
        
        poseStack.popPose();
    }
    
    /**
     * Render a single beam segment (simplified beacon beam)
     */
    private static void renderBeamSegment(PoseStack poseStack, MultiBufferSource buffer, 
                                           ResourceLocation texture, float partialTicks, float alpha,
                                           float time, int yStart, int yEnd, 
                                           float[] colors, float innerRadius, float outerRadius) {
        int height = yEnd - yStart;
        
        poseStack.pushPose();
        poseStack.translate(0.0D, yStart, 0.0D);
        
        // Texture animation
        float texOffset = -time * 0.2F;
        float texV1 = Mth.frac(texOffset);
        float texV2 = texV1 + height;
        
        // Render inner beam (more opaque)
        VertexConsumer innerConsumer = buffer.getBuffer(RenderType.beaconBeam(texture, false));
        renderBeamQuads(poseStack, innerConsumer, colors[0], colors[1], colors[2], alpha * 0.8F,
                height, 0, innerRadius, innerRadius, 0, innerRadius, -innerRadius, 
                -innerRadius, -innerRadius, -innerRadius, innerRadius,
                0.0F, 1.0F, texV1, texV2);
        
        // Render outer beam (more transparent, larger)
        VertexConsumer outerConsumer = buffer.getBuffer(RenderType.beaconBeam(texture, true));
        renderBeamQuads(poseStack, outerConsumer, colors[0], colors[1], colors[2], alpha * 0.25F,
                height, -outerRadius, outerRadius, outerRadius, -outerRadius, outerRadius, -outerRadius, 
                outerRadius, -outerRadius, outerRadius, outerRadius,
                0.0F, 1.0F, texV1, texV2);
        
        poseStack.popPose();
    }
    
    /**
     * Render the quad faces of the beam
     */
    private static void renderBeamQuads(PoseStack poseStack, VertexConsumer consumer, 
                                         float r, float g, float b, float a, int height,
                                         float x1, float z1, float x2, float z2, 
                                         float x3, float z3, float x4, float z4, 
                                         float x5, float z5,
                                         float u1, float u2, float v1, float v2) {
        PoseStack.Pose pose = poseStack.last();
        
        // Render 4 sides of the beam
        renderQuadFace(pose, consumer, r, g, b, a, height, x1, z1, x2, z2, u1, u2, v1, v2);
        renderQuadFace(pose, consumer, r, g, b, a, height, x3, z3, x4, z4, u1, u2, v1, v2);
        renderQuadFace(pose, consumer, r, g, b, a, height, x2, z2, x3, z3, u1, u2, v1, v2);
        renderQuadFace(pose, consumer, r, g, b, a, height, x4, z4, x1, z1, u1, u2, v1, v2);
    }
    
    /**
     * Render a single quad face
     */
    private static void renderQuadFace(PoseStack.Pose pose, VertexConsumer consumer,
                                        float r, float g, float b, float a, int height,
                                        float x1, float z1, float x2, float z2,
                                        float u1, float u2, float v1, float v2) {
        addVertex(pose, consumer, r, g, b, a, height, x1, z1, u2, v1);
        addVertex(pose, consumer, r, g, b, a, 0, x1, z1, u2, v2);
        addVertex(pose, consumer, r, g, b, a, 0, x2, z2, u1, v2);
        addVertex(pose, consumer, r, g, b, a, height, x2, z2, u1, v1);
    }
    
    /**
     * Add a single vertex to the buffer
     */
    private static void addVertex(PoseStack.Pose pose, VertexConsumer consumer,
                                   float r, float g, float b, float a, int y,
                                   float x, float z, float u, float v) {
        consumer.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(MirrorPortalEntity entity) {
        return TEXTURE;
    }
}
