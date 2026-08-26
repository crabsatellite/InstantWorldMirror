package com.crabmods.instantworldmirror.client.renderer;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.model.DimensionMirrorItemModel;
import com.crabmods.instantworldmirror.client.model.FirstDreamMirrorItemModel;
import com.crabmods.instantworldmirror.client.model.HeavenMirrorItemModel;
import com.crabmods.instantworldmirror.client.model.StrandedMirrorItemModel;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Mirror Portal Entity Renderer
 * Renders using custom Blockbench model
 */
public class MirrorPortalRenderer extends EntityRenderer<MirrorPortalEntity> {
    private static final double MODEL_Y_OFFSET = 2.17D;
    private static final float MODEL_SCALE = 1.5F;

    private static final ResourceLocation DIMENSION_MIRROR_TEXTURE = new ResourceLocation(
            InstantWorldMirror.MODID, "textures/item/dimension_mirror.png");
    private static final ResourceLocation HEAVEN_MIRROR_TEXTURE = new ResourceLocation(
            InstantWorldMirror.MODID, "textures/item/heaven_mirror.png");
    private static final ResourceLocation FIRST_DREAM_MIRROR_TEXTURE = new ResourceLocation(
            InstantWorldMirror.MODID, "textures/item/first_dream_mirror.png");
    private static final ResourceLocation STRANDED_MIRROR_TEXTURE = new ResourceLocation(
            InstantWorldMirror.MODID, "textures/item/stranded_mirror.png");
    
    private final DimensionMirrorItemModel dimensionMirrorModel;
    private final HeavenMirrorItemModel heavenMirrorModel;
    private final FirstDreamMirrorItemModel firstDreamMirrorModel;
    private final StrandedMirrorItemModel strandedMirrorModel;

    public MirrorPortalRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.dimensionMirrorModel = new DimensionMirrorItemModel(DimensionMirrorItemModel.createBodyLayer().bakeRoot());
        this.heavenMirrorModel = new HeavenMirrorItemModel(HeavenMirrorItemModel.createBodyLayer().bakeRoot());
        this.firstDreamMirrorModel = new FirstDreamMirrorItemModel(FirstDreamMirrorItemModel.createBodyLayer().bakeRoot());
        this.strandedMirrorModel = new StrandedMirrorItemModel(StrandedMirrorItemModel.createBodyLayer().bakeRoot());
    }

    @Override
    public void render(MirrorPortalEntity entity, float entityYaw, float partialTicks, 
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        // Calculate floating animation
        float time = entity.tickCount + partialTicks;
        float bob = (float) Math.sin(time * 0.1) * 0.1F;

        // The Blockbench models have an item-space pivot; raise them so placed mirrors sit on the block.
        poseStack.translate(0, MODEL_Y_OFFSET + bob, 0);

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
        
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);

        // Blink effect (when about to disappear)
        int lifetime = entity.getLifetime();
        float alpha = 1.0F;
        if (lifetime > 0 && lifetime < 40 && !isLoading) { // Last 2 seconds blink (non-loading state)
            alpha = (lifetime % 10 >= 5) ? 0.5F : 1.0F;
        }

        // Use full brightness for magical effect
        int fullBright = LightTexture.FULL_BRIGHT;
        
        MirrorKind kind = entity.getMirrorKind();
        ResourceLocation texture = textureFor(kind);
        EntityModel<Entity> model = modelFor(kind);
        RenderType renderType = alpha < 1.0F
                ? RenderType.entityTranslucent(texture)
                : RenderType.entityCutoutNoCull(texture);
        VertexConsumer vertexConsumer = buffer.getBuffer(renderType);
        
        // Extract RGBA components for 1.20.1 API
        float red = 1.0F;
        float green = 1.0F;
        float blue = 1.0F;
        
        model.renderToBuffer(poseStack, vertexConsumer, fullBright, OverlayTexture.NO_OVERLAY, red, green, blue, alpha);

        poseStack.popPose();

        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(MirrorPortalEntity entity) {
        return textureFor(entity.getMirrorKind());
    }

    private EntityModel<Entity> modelFor(MirrorKind kind) {
        return switch (kind) {
            case HEAVEN -> heavenMirrorModel;
            case FIRST_DREAM -> firstDreamMirrorModel;
            case STRANDED -> strandedMirrorModel;
            case DIMENSION -> dimensionMirrorModel;
        };
    }

    private static ResourceLocation textureFor(MirrorKind kind) {
        return switch (kind) {
            case HEAVEN -> HEAVEN_MIRROR_TEXTURE;
            case FIRST_DREAM -> FIRST_DREAM_MIRROR_TEXTURE;
            case STRANDED -> STRANDED_MIRROR_TEXTURE;
            case DIMENSION -> DIMENSION_MIRROR_TEXTURE;
        };
    }
}
