package com.agentplatform.core.multimodal;

import com.agentplatform.core.model.ModelCapability;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多模态能力探测器。
 * <p>根据消息 parts 探测所需模型能力：图片→VISION、音视频→AUDIO，据此路由到
 * 支持对应多模态能力的模型（配合 {@code ModelRouter} 的 Fallback）。</p>
 */
@Component
public class MultimodalResolver {

    /**
     * 从 parts 探测所需能力（缺省 TEXT）。
     */
    public ModelCapability resolve(List<ContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return ModelCapability.TEXT;
        }
        boolean hasImage = false;
        boolean hasAudioVideo = false;
        for (ContentPart part : parts) {
            switch (part) {
                case ContentPart.ImagePart i -> hasImage = true;
                case ContentPart.AudioPart a -> hasAudioVideo = true;
                case ContentPart.VideoPart v -> hasAudioVideo = true;
                case ContentPart.TextPart t -> { /* 不影响 */ }
                case ContentPart.FilePart f -> { /* 文件走解析管线 */ }
                case ContentPart.ToolResultPart tr -> { /* 不影响 */ }
            }
        }
        if (hasImage) {
            return ModelCapability.VISION;
        }
        if (hasAudioVideo) {
            return ModelCapability.AUDIO;
        }
        return ModelCapability.TEXT;
    }

    /**
     * 是否含图片（图生文）。
     */
    public boolean hasImage(List<ContentPart> parts) {
        return resolve(parts) == ModelCapability.VISION;
    }

    /**
     * 是否含音视频。
     */
    public boolean hasAudio(List<ContentPart> parts) {
        return resolve(parts) == ModelCapability.AUDIO;
    }
}