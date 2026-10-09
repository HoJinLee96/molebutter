package cc.ataglace.molebutter.imaging.api;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Value overrides for the observed supplier notice keys; null fields keeps all original values. */
public record NoticeImageRequestDto(String brandCode, Map<String, String> fields) {
    public NoticeImageRequestDto {
        fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
