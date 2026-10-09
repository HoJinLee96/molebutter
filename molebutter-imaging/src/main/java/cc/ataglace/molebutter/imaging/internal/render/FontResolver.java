package cc.ataglace.molebutter.imaging.internal.render;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 렌더링 폰트 해석기. 설정 폰트가 없으면 폴백 체인으로 내려간다.
 * - base: 정적 템플릿용 한글 폰트
 * - notice: 상품정보고시 전용 한글 폰트
 * - photoText: 사진 사이즈 이미지의 한글 폰트 — 원본 산출물과 구분되도록 다른 서체를 기본값으로 둔다
 * - number: 사진 사이즈 이미지의 숫자/라틴 폰트
 */
@Slf4j
@Component
public class FontResolver {

    // 기본값은 Windows(맑은 고딕/Segoe UI). 폴백 체인은 macOS 이름을 앞에 두어 개발기에서는 기존과 같은 서체가 잡힌다.
    private static final String[] BASE_FALLBACKS = {
            "Apple SD Gothic Neo", "AppleGothic", "Noto Sans KR", "NanumGothic", "Nanum Gothic",
            "Malgun Gothic", "Gulim", "Dotum" };
    private static final String[] PHOTO_TEXT_FALLBACKS = {
            "Nanum Gothic", "NanumGothic", "AppleGothic", "Apple SD Gothic Neo", "Noto Sans KR",
            "Malgun Gothic", "Gulim", "Dotum" };
    private static final String[] NUMBER_FALLBACKS = {
            "Avenir Next", "Avenir", "Futura", "Helvetica Neue", "Segoe UI", "Arial", "Verdana", "Trebuchet MS" };
    private static final String[] NOTICE_FALLBACKS = {
            "AppleGothic", "Noto Sans KR", "Nanum Gothic", "NanumGothic", "Apple SD Gothic Neo",
            "Malgun Gothic", "Gulim", "Dotum" };

    private final FamilyChoice base;
    private final FamilyChoice photoText;
    private final FamilyChoice number;
    private final FamilyChoice notice;

    public FontResolver(
            @Value("${imaging.render.font.family:Malgun Gothic}") String baseFamily,
            @Value("${imaging.render.font.photo-family:Malgun Gothic}") String photoTextFamily,
            @Value("${imaging.render.font.number-family:Segoe UI}") String numberFamily,
            @Value("${imaging.render.font.notice-family:Malgun Gothic}") String noticeFamily) {
        this.base = new FamilyChoice("base", baseFamily, BASE_FALLBACKS);
        this.photoText = new FamilyChoice("photoText", photoTextFamily, PHOTO_TEXT_FALLBACKS);
        this.number = new FamilyChoice("number", numberFamily, NUMBER_FALLBACKS);
        this.notice = new FamilyChoice("notice", noticeFamily, NOTICE_FALLBACKS);
    }

    /** 정적 템플릿 렌더용 기본 한글 폰트. */
    public Font font(int style, int size) {
        return new Font(base.family(), style, size);
    }

    /** 사진 사이즈 이미지의 한글 텍스트 폰트. */
    public Font photoTextFont(int style, int size) {
        return new Font(photoText.family(), style, size);
    }

    /** 사진 사이즈 이미지의 숫자/라틴 폰트. */
    public Font numberFont(int style, int size) {
        return new Font(number.family(), style, size);
    }

    /** 상품정보고시의 제목·항목·본문에만 적용하는 독립 서체. */
    public Font noticeFont(int style, int size) {
        return new Font(notice.family(), style, size);
    }

    private static final class FamilyChoice {
        private final String role;
        private final String preferred;
        private final String[] fallbacks;
        private volatile String resolved;

        private FamilyChoice(String role, String preferred, String[] fallbacks) {
            this.role = role;
            this.preferred = preferred;
            this.fallbacks = fallbacks;
        }

        private String family() {
            String family = resolved;
            if (family != null) {
                return family;
            }
            synchronized (this) {
                if (resolved == null) {
                    resolved = resolve();
                    log.info("렌더링 폰트 결정({}): {}", role, resolved);
                }
                return resolved;
            }
        }

        private String resolve() {
            // 한국어 로케일 Windows 는 기본 목록이 "맑은 고딕" 처럼 현지화된 이름이라 영문 이름과 합쳐서 찾는다.
            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Set<String> available = new LinkedHashSet<>(Arrays.asList(environment.getAvailableFontFamilyNames()));
            available.addAll(Arrays.asList(environment.getAvailableFontFamilyNames(Locale.ENGLISH)));
            if (preferred != null && !preferred.isBlank() && available.contains(preferred.trim())) {
                return preferred.trim();
            }
            for (String candidate : fallbacks) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
            log.warn("사용 가능한 폰트를 찾지 못해 논리 폰트로 폴백: role={}, preferred={}", role, preferred);
            return Font.SANS_SERIF;
        }
    }
}
