package cc.ataglace.molebutter.imaging.api;
import java.util.List;
/** Browser download metadata. Never exposes server filesystem locations. */
public record DownloadResultDto(String downloadName, List<String> savedFiles) {
    public DownloadResultDto { savedFiles = List.copyOf(savedFiles); }
}
