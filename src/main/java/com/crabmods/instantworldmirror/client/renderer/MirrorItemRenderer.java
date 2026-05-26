package com.crabmods.instantworldmirror.client.renderer;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.client.model.DimensionMirrorItemModel;
import com.crabmods.instantworldmirror.client.model.HeavenMirrorItemModel;
import com.crabmods.instantworldmirror.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class MirrorItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ResourceLocation DIMENSION_MIRROR_TEXTURE = new ResourceLocation(
            InstantWorldMirror.MODID, "textures/item/dimension_mirror.png");
    private static final ResourceLocation HEAVEN_MIRROR_TEXTURE = new ResourceLocation(
            InstantWorldMirror.MODID, "textures/item/heaven_mirror.png");

    private static MirrorItemRenderer instance;

    private final DimensionMirrorItemModel dimensionMirrorModel;
    private final HeavenMirrorItemModel heavenMirrorModel;

    private MirrorItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        this.dimensionMirrorModel = new DimensionMirrorItemModel(DimensionMirrorItemModel.createBodyLayer().bakeRoot());
        this.heavenMirrorModel = new HeavenMirrorItemModel(HeavenMirrorItemModel.createBodyLayer().bakeRoot());
    }

    public static MirrorItemRenderer getInstance() {
        if (instance == null) {
            instance = new MirrorItemRenderer();
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        boolean isHeavenMirror = stack.is(ModItems.HEAVEN_MIRROR.get());

        poseStack.pushPose();
        applyItemTransform(displayContext, poseStack, isHeavenMirror);

        ResourceLocation texture = isHeavenMirror ? HEAVEN_MIRROR_TEXTURE : DIMENSION_MIRROR_TEXTURE;
        EntityModel<Entity> model = isHeavenMirror ? heavenMirrorModel : dimensionMirrorModel;
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        model.renderToBuffer(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F);

        poseStack.popPose();
    }

    private static void applyItemTransform(ItemDisplayContext displayContext, PoseStack poseStack, boolean isHeavenMirror) {
        if (displayContext == ItemDisplayContext.GUI) {
            if (isHeavenMirror) {
                poseStack.translate(0.46D, 1.43D, 0.5D);
            } else {
                poseStack.translate(0.45D, 1.44D, 0.5D);
            }
        } else {
            poseStack.translate(0.5D, 1.5D, 0.5D);
        }
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));

        if (displayContext == ItemDisplayContext.GUI) {
            poseStack.scale(0.95F, 0.95F, 0.95F);
            poseStack.mulPose(Axis.XP.rotationDegrees(25.0F));
            poseStack.mulPose(Axis.YP.rotationDegrees(-35.0F));
        } else if (displayContext.firstPerson()) {
            poseStack.scale(0.9F, 0.9F, 0.9F);
            poseStack.mulPose(Axis.XP.rotationDegrees(-10.0F));
        } else if (displayContext == ItemDisplayContext.GROUND) {
            poseStack.scale(0.65F, 0.65F, 0.65F);
        } else if (displayContext == ItemDisplayContext.FIXED) {
            poseStack.scale(0.9F, 0.9F, 0.9F);
        } else {
            poseStack.scale(0.85F, 0.85F, 0.85F);
        }
    }
}
