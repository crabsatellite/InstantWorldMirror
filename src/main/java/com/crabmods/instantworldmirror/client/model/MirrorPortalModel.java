package com.crabmods.instantworldmirror.client.model;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.resources.ResourceLocation;

/**
 * Mirror Portal Entity Model
 * Made with Blockbench - Converted for NeoForge 1.21
 */
public class MirrorPortalModel extends EntityModel<MirrorPortalEntity> {
    
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "mirror_portal"), "main"
    );
    
    private final ModelPart frame;

    public MirrorPortalModel(ModelPart root) {
        this.frame = root.getChild("frame");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // Mirror frame structure from Blockbench export (updated version)
        // Y offset set to 0, position handled in renderer
        PartDefinition frame = partdefinition.addOrReplaceChild("frame", CubeListBuilder.create()
                // Corner posts
                .texOffs(9, 25).addBox(4.0F, -1.0F, 2.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(9, 25).addBox(-5.0F, -1.0F, 2.0F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                // Top/bottom edges
                .texOffs(23, 42).addBox(-3.0F, -1.0F, 1.0F, 6.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                // Mirror surface (main reflective area)
                .texOffs(16, 25).mirror().addBox(-3.0F, -0.5F, 2.0F, 6.0F, 1.5F, 8.0F, new CubeDeformation(0.0F)).mirror(false)
                // Frame sides
                .texOffs(23, 46).addBox(-3.0F, -1.0F, 0.0F, 6.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(23, 20).addBox(-3.0F, -1.0F, 10.0F, 6.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(22, 15).mirror().addBox(-4.0F, -1.0F, 11.0F, 7.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)).mirror(false)
                // Handle/stand
                .texOffs(26, 10).addBox(-2.0F, -1.0F, 12.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(8, 24).addBox(4.0F, -1.0F, 7.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(8, 24).addBox(-5.0F, -1.0F, 8.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                // Side frames
                .texOffs(33, 24).addBox(3.0F, -1.0F, 1.0F, 1.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(8, 24).addBox(-4.0F, -1.0F, 1.0F, 1.0F, 2.0F, 10.0F, new CubeDeformation(0.0F)),
                // Rotation: 90° on X axis to make mirror face upward
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 1.5708F, 0.0F, 0.0F)
        );

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(MirrorPortalEntity entity, float limbSwing, float limbSwingAmount, 
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // No animation needed - handled in renderer
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, 
                               int packedOverlay, int color) {
        frame.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}
