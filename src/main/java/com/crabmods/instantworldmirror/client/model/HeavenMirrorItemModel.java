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
 * Heaven mirror item model.
 * Source: supplied Heaven Mirror Blockbench model.
 */
public class HeavenMirrorItemModel extends EntityModel<Entity> {
    private final ModelPart group;

    public HeavenMirrorItemModel(ModelPart root) {
        this.group = root.getChild("group");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        partdefinition.addOrReplaceChild("group", CubeListBuilder.create()
                .texOffs(15, 24).addBox(-3.5F, -0.5F, 3.0F, 7.0F, 1.0F, 9.0F, new CubeDeformation(0.0F))
                .texOffs(24, 38).addBox(-1.5F, -1.0F, 1.0F, 5.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(24, 17).addBox(-1.5F, -1.0F, 12.0F, 5.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(17, 38).addBox(3.5F, -1.0F, 2.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(42, 38).addBox(-4.5F, -1.5F, 1.0F, 3.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(43, 18).addBox(-4.5F, -1.5F, 11.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(39, 10).addBox(-5.5F, -2.0F, 13.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(11, 30).addBox(3.5F, -1.5F, 4.0F, 2.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(12, 19).addBox(3.5F, -1.5F, 9.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(57, 38).addBox(-5.5F, -1.5F, 2.0F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(13, 25).addBox(3.5F, -1.0F, 7.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(13, 14).addBox(3.5F, -1.0F, 11.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(40, 26).addBox(-4.5F, -1.0F, 3.0F, 1.0F, 2.0F, 8.0F, new CubeDeformation(0.0F)),
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
