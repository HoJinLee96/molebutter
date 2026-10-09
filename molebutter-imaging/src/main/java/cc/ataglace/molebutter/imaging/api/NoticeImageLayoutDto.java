package cc.ataglace.molebutter.imaging.api;
import java.util.List;
/** Measured card order and row widths, shared with the PNG renderer. */
public record NoticeImageLayoutDto(List<NoticeImageCardDto> cards) {
    public NoticeImageLayoutDto { cards = List.copyOf(cards); }
}
