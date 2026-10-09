package cc.ataglace.molebutter.imaging.internal.service;

import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;

import cc.ataglace.molebutter.imaging.api.ProductLookupDto;
import cc.ataglace.molebutter.imaging.api.GeneratedSizeImageDto;
import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.api.SizeGuideLayoutDto;
import cc.ataglace.molebutter.imaging.api.SizeGuidePreviewRequestDto;
import cc.ataglace.molebutter.imaging.api.SizeGuidePreviewResponseDto;
import cc.ataglace.molebutter.imaging.api.SizeGuideSelectionDto;
import cc.ataglace.molebutter.imaging.api.SizeGuideTemplateDto;
import cc.ataglace.molebutter.imaging.internal.parse.SizeDimensionParser;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;
import cc.ataglace.molebutter.imaging.internal.render.PhotoSizeGuideRenderer;
import cc.ataglace.molebutter.imaging.internal.render.RenderSupport;
import cc.ataglace.molebutter.imaging.internal.render.TemplateSizeGuideRenderer;
import lombok.RequiredArgsConstructor;

/**
 * 사이즈 이미지 생성 진입점. 프리뷰와 목록 추가가 같은 selection을 사용하며,
 * 목록 추가 시 확정한 PNG 바이트를 보관해 다운로드까지 유지한다.
 */
@Service
@RequiredArgsConstructor
public class SizeGuideService {

    private final LookupCacheService lookupCacheService;
    private final TemplateSizeGuideRenderer templateSizeGuideRenderer;
    private final PhotoSizeGuideRenderer photoSizeGuideRenderer;
    private final GeneratedImageStore generatedSizeImageStore;

    public GeneratedSizeImageDto generate(Long actorId, ProductLookupDto product, SizeGuidePreviewRequestDto request) {
        RenderedSizeGuide rendered = render(product, request.toSelection());
        String id = generatedSizeImageStore.put(actorId, product, rendered.png(), GeneratedImageStore.ImageKind.SIZE);
        return new GeneratedSizeImageDto(id, rendered.template().displayName(),
                "data:image/png;base64," + Base64.getEncoder().encodeToString(rendered.png()));
    }

    /** 자동 분류가 실패한 상품도 직접 고를 수 있도록 지원 템플릿 전부를 내려준다. */
    public List<SizeGuideTemplateDto> listTemplates() {
        return SizeGuideTemplate.supportedTemplates().stream()
                .map(template -> new SizeGuideTemplateDto(template.key(), template.displayName(),
                        template.widthLabel(), template.depthLabel(), template.heightLabel(),
                        template.alwaysTwoDimensional()))
                .toList();
    }

    public SizeGuidePreviewResponseDto preview(ProductLookupDto product, SizeGuidePreviewRequestDto request) {
        RenderedSizeGuide rendered = render(product, request.toSelection());
        return new SizeGuidePreviewResponseDto(
                rendered.template().key(),
                rendered.template().displayName(),
                "data:image/png;base64," + Base64.getEncoder().encodeToString(rendered.png()),
                RenderSupport.OUTPUT_IMAGE_WIDTH,
                RenderSupport.outputHeight(RenderSupport.SIZE_IMAGE_WIDTH, RenderSupport.SIZE_IMAGE_HEIGHT),
                rendered.layout());
    }

    /** selection 에 따라 최종 사이즈 이미지 PNG 를 렌더한다. 다운로드 잡과 프리뷰가 공유. */
    public RenderedSizeGuide render(ProductLookupDto product, SizeGuideSelectionDto selection) {
        ImagingInputs.selection(selection);
        String sizeLabel = selection == null || selection.sizeLabel() == null ? product.sizeLabel() : selection.sizeLabel();
        SizeGuideTemplate template = resolveTemplate(product, selection);
        SizeDimensionsDto dimensions = SizeDimensionParser.merge(
                product.sizeDimensions(), selection == null ? null : selection.dimensions());
        if (!SizeDimensionParser.hasRequiredDimensions(template, dimensions)) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT,
                    "상품 고시에서 신뢰 가능한 가로/폭/높이 치수를 찾지 못했습니다. 치수를 직접 입력해주세요.");
        }

        if (selection != null && selection.photoMode()) {
            if (selection.baseImageUrl() == null || selection.baseImageUrl().isBlank()) {
                throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "베이스 이미지를 선택해주세요.");
            }
            if (!product.imageUrls().contains(selection.baseImageUrl())) {
                throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "조회한 상품의 사진을 선택해주세요.");
            }
            BufferedImage baseImage = lookupCacheService.baseImage(selection.baseImageUrl(), product.productCode());
            PhotoSizeGuideRenderer.PhotoRender render = photoSizeGuideRenderer.render(
                    template, dimensions, sizeLabel, baseImage, selection.layout(),
                    RenderSupport.OUTPUT_SCALE);
            return new RenderedSizeGuide(template, RenderSupport.encodePng(render.image()), render.layout());
        }

        byte[] png = templateSizeGuideRenderer.renderPng(template, dimensions, sizeLabel);
        return new RenderedSizeGuide(template, png, null);
    }

    private SizeGuideTemplate resolveTemplate(ProductLookupDto product, SizeGuideSelectionDto selection) {
        if (selection != null && selection.templateKey() != null && !selection.templateKey().isBlank()) {
            SizeGuideTemplate template = SizeGuideTemplate.fromKey(selection.templateKey())
                    .orElseThrow(() -> new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT,
                            "지원하지 않는 사이즈 이미지 템플릿입니다."));
            if (!template.supported()) {
                throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "지원하지 않는 사이즈 이미지 템플릿입니다.");
            }
            return template;
        }
        if (product.sizeGuideTemplateKey() != null) {
            return SizeGuideTemplate.fromKey(product.sizeGuideTemplateKey())
                    .filter(SizeGuideTemplate::supported)
                    .orElseThrow(() -> new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT,
                            "지원하지 않는 사이즈 이미지 템플릿입니다."));
        }
        throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT,
                "미지원 카테고리 상품입니다. 사이즈 템플릿을 직접 선택해주세요.");
    }

    public record RenderedSizeGuide(SizeGuideTemplate template, byte[] png, SizeGuideLayoutDto layout) {
    }
}
