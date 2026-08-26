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
 * Stranded Mirror model supplied through issue #15.
 */
public class StrandedMirrorItemModel extends EntityModel<Entity> {
    private final ModelPart group;

    public StrandedMirrorItemModel(ModelPart root) {
        this.group = root.getChild("group");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("group", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.5F, -0.5F, 3.0F, 7.0F, 1.0F, 9.0F, new CubeDeformation(0.0F))
                .texOffs(0, 10).addBox(-5.5F, -1.5F, 1.0F, 11.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 14).addBox(-4.5F, -1.0F, 2.0F, 9.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 23).addBox(-4.5F, -1.0F, 3.0F, 3.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(8, 31).addBox(1.5F, -1.0F, 3.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(30, 26).addBox(3.5F, -1.0F, 3.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(28, 30).addBox(2.5F, -1.0F, 9.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 31).addBox(4.5F, -1.0F, 9.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(18, 17).addBox(3.5F, -1.5F, 4.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(10, 22).addBox(-5.5F, -1.5F, 5.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(10, 17).addBox(3.5F, -1.0F, 6.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(24, 10).addBox(3.5F, -1.0F, 10.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(26, 22).addBox(3.5F, -1.0F, 10.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 26).addBox(4.5F, -1.5F, 12.0F, 1.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(18, 26).addBox(2.5F, -1.5F, 13.0F, 1.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(24, 26).addBox(2.5F, -1.5F, 12.0F, 2.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(20, 14).addBox(-1.5F, -1.0F, 12.0F, 4.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(18, 22).addBox(-5.5F, -1.5F, 11.0F, 3.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(26, 17).addBox(-3.5F, -1.5F, 12.0F, 1.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(12, 27).addBox(-4.5F, -1.5F, 13.0F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(24, 30).addBox(3.5F, -1.5F, 14.0F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(30, 10).addBox(-5.5F, -1.5F, 12.0F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(30, 14).addBox(-4.5F, -1.0F, 4.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 17).addBox(-4.5F, -1.0F, 7.0F, 1.0F, 2.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(6, 27).addBox(-2.5F, -1.0F, 11.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(4, 31).addBox(-3.5F, -1.0F, 10.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(-1.0F, 25.0F, 0.0F, -1.5708F, 0.0F, 3.1416F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight,
                               int packedOverlay, int color) {
        group.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}

