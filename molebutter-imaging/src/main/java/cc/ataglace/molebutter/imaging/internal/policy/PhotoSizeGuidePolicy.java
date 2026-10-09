package cc.ataglace.molebutter.imaging.internal.policy;

import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;

/**
 * 사진 기반 사이즈 이미지의 템플릿별 기본 사진 박스.
 * 치수 점선은 렌더러가 실제 제품 외곽을 감지해 자동 배치한다.
 */
public final class PhotoSizeGuidePolicy {

    private static final PhotoLayout DEFAULT_BAG_LAYOUT = new PhotoLayout(820, 500, 78);

    private PhotoSizeGuidePolicy() {
    }

    public static PhotoLayout resolve(SizeGuideTemplate template) {
        return switch (template) {
            case BACKPACK -> new PhotoLayout(760, 500, 74);
            case SLINGBAG -> new PhotoLayout(820, 480, 86);
            case CLUTCH, CARD_WALLET -> new PhotoLayout(820, 430, 116);
            case WALLET_OPEN_HALF, WALLET_OPEN_MEDIUM_LONG, WALLET_OPEN_MEDIUM_LONG_VERTICAL ->
                new PhotoLayout(820, 450, 104);
            case MALE_CROSS_MESSENGER -> new PhotoLayout(780, 500, 74);
            case BELT -> new PhotoLayout(960, 420, 110);
            case TOTE, SHOULDER_CROSS, UNSUPPORTED -> DEFAULT_BAG_LAYOUT;
        };
    }

    public record PhotoLayout(
            int photoBoxWidth,
            int photoBoxHeight,
            int photoBoxY) {
    }
}
