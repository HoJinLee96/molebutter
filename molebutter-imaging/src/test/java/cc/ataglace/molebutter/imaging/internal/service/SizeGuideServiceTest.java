package cc.ataglace.molebutter.imaging.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import cc.ataglace.molebutter.imaging.api.GeneratedSizeImageDto;
import cc.ataglace.molebutter.imaging.api.ProductLookupDto;
import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.api.SizeGuidePreviewRequestDto;
import cc.ataglace.molebutter.imaging.api.SizeGuideTemplateDto;
import cc.ataglace.molebutter.imaging.api.SizeGuidePreviewResponseDto;
import cc.ataglace.molebutter.imaging.internal.render.FontResolver;
import cc.ataglace.molebutter.imaging.internal.render.PhotoSizeGuideRenderer;
import cc.ataglace.molebutter.imaging.internal.render.TemplateSizeGuideRenderer;

class SizeGuideServiceTest {
    private final LookupCacheService cache = mock(LookupCacheService.class);
    private final GeneratedImageStore store = new GeneratedImageStore();
    private final FontResolver fonts = new FontResolver("Apple SD Gothic Neo", "Nanum Gothic", "Avenir Next", "AppleGothic");
    private final SizeGuideService service = new SizeGuideService(cache,
            new TemplateSizeGuideRenderer(fonts), new PhotoSizeGuideRenderer(fonts), store);
    private final ProductLookupDto product = new ProductLookupDto("BAG", "DAKS", "닥스", "토트백", null, null,
            List.of("https://nimg.lfmall.co.kr/bag.jpg"), List.of("토트백"), Map.of(), "FREE", null, Map.of(),
            true, "토트백", "TOTE", new SizeDimensionsDto("30", "14", "22"));

    @Test
    void selectionListOffersEverySupportedTemplateWithDimensionLabels() {
        assertThat(service.listTemplates())
                .extracting(SizeGuideTemplateDto::templateKey)
                .contains("TOTE", "SHOULDER_CROSS", "CARD_WALLET", "BELT")
                .doesNotContain("UNSUPPORTED");
        SizeGuideTemplateDto belt = service.listTemplates().stream()
                .filter(template -> template.templateKey().equals("BELT")).findFirst().orElseThrow();
        assertThat(belt.displayName()).isEqualTo("벨트");
        assertThat(belt.widthLabel()).isEqualTo("총길이");
        assertThat(belt.heightLabel()).isEqualTo("너비");
        assertThat(belt.twoDimensional()).isTrue();
    }

    @Test
    void categoryGenerationReturnsActualPngIdenticalToStoredDownloadSnapshot() throws Exception {
        when(cache.product("BAG", "DAKS")).thenReturn(product);
        GeneratedSizeImageDto first = service.generate(1L, product, new SizeGuidePreviewRequestDto(
                "DAKS", "TEMPLATE", "TOTE", null, null, new SizeDimensionsDto("30", "14", "22")));
        byte[] firstBytes = pngBytes(first);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(firstBytes));
        assertThat(image.getWidth()).isEqualTo(780);
        assertThat(image.getHeight()).isEqualTo(509);
        assertThat(image.getRGB(300, 100)).isEqualTo(Color.WHITE.getRGB());
        assertThat(first.displayName()).isEqualTo("토트백");
        assertThat(store.get(1L, first.id(), product)).isEqualTo(firstBytes);
        GeneratedSizeImageDto second = service.generate(1L, product, new SizeGuidePreviewRequestDto(
                "DAKS", "TEMPLATE", "TOTE", null, null, new SizeDimensionsDto("99", "14", "22")));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(pngBytes(second)).isNotEqualTo(firstBytes);
        assertThat(store.get(1L, first.id(), product)).isEqualTo(firstBytes);
    }

    @Test
    void confirmingPhotoPreviewStoresExactlyThePreviewPixels() {
        when(cache.product("BAG", "DAKS")).thenReturn(product);
        BufferedImage base = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = base.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 600, 600);
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(150, 160, 300, 280);
        graphics.dispose();
        String baseUrl = product.imageUrls().getFirst();
        when(cache.baseImage(baseUrl, "BAG")).thenReturn(base);
        SizeGuidePreviewResponseDto preview = service.preview(product, new SizeGuidePreviewRequestDto(
                "DAKS", "PHOTO", "TOTE", baseUrl, null, product.sizeDimensions()));
        GeneratedSizeImageDto result = service.generate(1L, product, new SizeGuidePreviewRequestDto(
                "DAKS", "PHOTO", "TOTE", baseUrl, preview.layout(), product.sizeDimensions()));
        assertThat(result.imageDataUrl()).isEqualTo(preview.imageDataUrl());
        assertThat(store.get(1L, result.id(), product)).isEqualTo(pngBytes(result));
    }

    @Test
    void manualSizeLabelOverridesOnlyThisTemplateSnapshotAndLegacyConstructorKeepsSupplierLabel() {
        SizeGuidePreviewRequestDto legacy = new SizeGuidePreviewRequestDto("DAKS", "TEMPLATE", "TOTE", null, null, product.sizeDimensions());
        assertThat(legacy.sizeLabel()).isNull();
        GeneratedSizeImageDto original = service.generate(1L, product, legacy);
        SizeGuidePreviewRequestDto edited = new SizeGuidePreviewRequestDto("DAKS", "TEMPLATE", "TOTE", null, null, product.sizeDimensions(), "M(95)");
        assertThat(edited.toSelection().sizeLabel()).isEqualTo("M(95)");
        GeneratedSizeImageDto result = service.generate(1L, product, edited);
        assertThat(pngBytes(result)).isEqualTo(new TemplateSizeGuideRenderer(fonts).renderPng(
                cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate.TOTE, product.sizeDimensions(), "M(95)"));
        assertThat(pngBytes(result)).isNotEqualTo(pngBytes(original));
        assertThat(store.get(1L, original.id(), product)).isEqualTo(pngBytes(original));
        assertThat(product.sizeLabel()).isEqualTo("FREE");
        assertThat(service.preview(product, legacy).imageDataUrl()).isEqualTo(original.imageDataUrl());
    }

    @Test
    void photoSizeLabelIsSharedByPreviewAndConfirmedPng() {
        BufferedImage base = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = base.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 600, 600);
        graphics.setColor(Color.DARK_GRAY); graphics.fillRect(150, 160, 300, 280); graphics.dispose();
        String baseUrl = product.imageUrls().getFirst(); when(cache.baseImage(baseUrl, "BAG")).thenReturn(base);
        SizeGuidePreviewRequestDto request = new SizeGuidePreviewRequestDto("DAKS", "PHOTO", "TOTE", baseUrl, null, product.sizeDimensions(), "S(90)");
        SizeGuidePreviewResponseDto preview = service.preview(product, request);
        byte[] expected = cc.ataglace.molebutter.imaging.internal.render.RenderSupport.encodePng(
                new PhotoSizeGuideRenderer(fonts).render(cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate.TOTE,
                        product.sizeDimensions(), "S(90)", base, null, cc.ataglace.molebutter.imaging.internal.render.RenderSupport.OUTPUT_SCALE).image());
        assertThat(Base64.getDecoder().decode(preview.imageDataUrl().substring("data:image/png;base64,".length()))).isEqualTo(expected);
        GeneratedSizeImageDto generated = service.generate(1L, product, new SizeGuidePreviewRequestDto("DAKS", "PHOTO", "TOTE", baseUrl, preview.layout(), product.sizeDimensions(), "S(90)"));
        assertThat(generated.imageDataUrl()).isEqualTo(preview.imageDataUrl());
        assertThat(store.get(1L, generated.id(), product)).isEqualTo(expected);
    }

    @Test
    void sizeLabelLengthAndControlsAreRejectedBeforeRendering() {
        assertThatThrownBy(() -> service.generate(1L, product, new SizeGuidePreviewRequestDto("DAKS", "TEMPLATE", "TOTE", null, null, product.sizeDimensions(), "x".repeat(101))))
                .hasMessageContaining("100자");
        assertThatThrownBy(() -> service.preview(product, new SizeGuidePreviewRequestDto("DAKS", "TEMPLATE", "TOTE", null, null, product.sizeDimensions(), "M\n95")))
                .hasMessageContaining("100자");
    }

    private byte[] pngBytes(GeneratedSizeImageDto result) {
        assertThat(result.imageDataUrl()).startsWith("data:image/png;base64,");
        return Base64.getDecoder().decode(result.imageDataUrl().substring("data:image/png;base64,".length()));
    }
}
