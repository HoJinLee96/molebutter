package cc.ataglace.molebutter.imaging.internal.service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.render.NoticeImageRenderer;
import lombok.RequiredArgsConstructor;

/** Edits only values from the actor's observed supplier notice; never modifies source data. */
@Service
@RequiredArgsConstructor
public class NoticeImageService {
    private final NoticeImageRenderer renderer;
    private final GeneratedImageStore generated;

    public NoticeImageLayoutDto layout(ProductLookupDto product, NoticeImageRequestDto request) {
        return renderer.layout(editedFields(product, request));
    }
    public GeneratedNoticeImageDto generate(Long actor, ProductLookupDto product, NoticeImageRequestDto request) {
        byte[] png = renderer.renderPng(editedFields(product, request));
        String id = generated.put(actor, product, png, GeneratedImageStore.ImageKind.NOTICE);
        return new GeneratedNoticeImageDto(id, "상품정보고시",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
    }
    Map<String, String> editedFields(ProductLookupDto product, NoticeImageRequestDto request) {
        if (request == null) throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "상품정보고시 요청이 필요합니다.");
        ImagingInputs.notice(product.productCode(), request);
        Map<String, String> fields = new LinkedHashMap<>(product.notificationFields());
        if (request.fields() != null) {
            for (Map.Entry<String, String> field : request.fields().entrySet()) {
                if (!fields.containsKey(field.getKey())) {
                    throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "조회한 상품정보고시의 항목만 편집할 수 있습니다.");
                }
                fields.put(field.getKey(), field.getValue());
            }
        }
        return fields;
    }
}
