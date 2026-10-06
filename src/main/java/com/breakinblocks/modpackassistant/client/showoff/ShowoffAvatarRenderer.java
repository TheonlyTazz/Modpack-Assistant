package com.breakinblocks.modpackassistant.client.showoff;

import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/** A preview-only renderer; never modifies the renderer used by world entities. */
final class ShowoffAvatarRenderer extends AvatarRenderer<ClientMannequin> {
    ShowoffAvatarRenderer(EntityRendererProvider.Context context, boolean slim) {
        super(context, slim);
        model = new PosedModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
        layers.removeIf(layer -> layer instanceof HumanoidArmorLayer<?, ?, ?>);
        addLayer(new HumanoidArmorLayer<>(this,
                ArmorModelSet.bake(slim ? ModelLayers.PLAYER_SLIM_ARMOR : ModelLayers.PLAYER_ARMOR,
                        context.getModelSet(), part -> new PosedModel(part, slim)), context.getEquipmentRenderer()));
    }

    static final class State extends AvatarRenderState {
        private final float[][] rotations;

        State(float[][] rotations) {
            this.rotations = new float[PlayerShowoff.PARTS][];
            for (int part = 0; part < rotations.length; part++) {
                this.rotations[part] = rotations[part].clone();
            }
        }

        private void apply(ModelPart part, int index) {
            part.xRot = rotations[index][0] * Mth.DEG_TO_RAD;
            part.yRot = rotations[index][1] * Mth.DEG_TO_RAD;
            part.zRot = rotations[index][2] * Mth.DEG_TO_RAD;
        }
    }

    private static final class PosedModel extends PlayerModel {
        private PosedModel(ModelPart root, boolean slim) {
            super(root, slim);
        }

        @Override
        public void setupAnim(AvatarRenderState state) {
            if (!(state instanceof State pose)) {
                throw new IllegalArgumentException("Showoff models require a showoff pose state");
            }
            super.setupAnim(state);
            // Feature rendering calls setupAnim again, including on each armor model.
            pose.apply(head, 0);
            pose.apply(body, 1);
            pose.apply(leftArm, 2);
            pose.apply(rightArm, 3);
            pose.apply(leftLeg, 4);
            pose.apply(rightLeg, 5);
        }
    }
}
