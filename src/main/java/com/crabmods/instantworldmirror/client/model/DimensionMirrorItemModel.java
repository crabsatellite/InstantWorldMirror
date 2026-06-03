package com.crabmods.instantworldmirror.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.entity.Entity;

/**
 * Dimension mirror item model.
 * Source: supplied Dimension Mirror Blockbench model.
 */
public class DimensionMirrorItemModel extends EntityModel<Entity> {
    private final ModelPart group;

    public DimensionMirrorItemModel(ModelPart root) {
        this.group = root.getChild("group");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        partdefinition.addOrReplaceChild("group", CubeListBuilder.create()
                .texOffs(32, 6).addBox(4.5F, -1.0F, 10.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(32, 3).addBox(-5.5F, -1.0F, 10.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(24, 10).addBox(-3.5F, -1.0F, 2.0F, 7.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(-3.5F, -0.5F, 3.0F, 7.0F, 1.0F, 9.0F, new CubeDeformation(0.0F))
                .texOffs(24, 19).addBox(-3.5F, -1.0F, 1.0F, 7.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(24, 16).addBox(-3.5F, -1.0F, 12.0F, 7.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(24, 13).addBox(-3.5F, -1.0F, 13.0F, 7.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(32, 0).addBox(-1.5F, -1.0F, 0.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(24, 27).addBox(3.5F, -1.5F, 4.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(24, 22).addBox(-5.5F, -1.5F, 3.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(4, 14).addBox(3.5F, -1.0F, 6.0F, 1.0F, 2.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(9, 36).addBox(3.5F, -1.0F, 2.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 23).addBox(-4.5F, -1.0F, 2.0F, 1.0F, 2.0F, 11.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(-1.0F, 24.0F, 0.0F, -1.5708F, 0.0F, 3.1416F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
        group.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}
