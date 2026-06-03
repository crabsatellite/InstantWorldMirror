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
 * First dream mirror item model.
 * Source: supplied Original Mirror Blockbench model.
 */
public class FirstDreamMirrorItemModel extends EntityModel<Entity> {
    private final ModelPart group;

    public FirstDreamMirrorItemModel(ModelPart root) {
        this.group = root.getChild("group");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        partdefinition.addOrReplaceChild("group", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.5F, -0.5F, 3.0F, 7.0F, 1.0F, 9.0F, new CubeDeformation(0.0F))
                .texOffs(0, 31).addBox(1.5F, -1.5F, 3.0F, 3.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(32, 0).addBox(3.5F, -1.0F, 4.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(28, 29).addBox(3.5F, -1.5F, 6.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 15).addBox(-5.5F, -2.0F, 8.0F, 2.0F, 4.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(16, 23).addBox(-3.5F, -1.5F, 10.0F, 1.0F, 3.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(10, 25).addBox(-6.5F, -1.5F, 11.0F, 1.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(24, 31).addBox(-4.5F, -1.5F, 7.0F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(28, 23).addBox(3.5F, -1.5F, 11.0F, 2.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(32, 4).addBox(5.5F, -1.0F, 12.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(16, 15).addBox(-4.5F, -1.5F, 2.0F, 9.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 10).addBox(-5.5F, -2.0F, 1.0F, 11.0F, 4.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(8, 31).addBox(-4.5F, -1.5F, 3.0F, 3.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(17, 19).addBox(-2.5F, -1.0F, 12.0F, 6.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(16, 31).addBox(0.5F, -1.0F, 14.0F, 3.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(32, 7).addBox(2.5F, -1.0F, 15.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(32, 7).addBox(-4.5F, -1.0F, 14.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(24, 10).addBox(3.5F, -1.0F, 8.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(1, 26).addBox(-4.5F, -1.0F, 4.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(-1.0F, 25.0F, 0.0F, -1.5708F, 0.0F, 3.1416F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        group.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
